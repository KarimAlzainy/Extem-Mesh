package com.extem.mesh.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.extem.mesh.protocol.ChunkCodec
import com.extem.mesh.protocol.FrameAssembler
import com.extem.mesh.protocol.FrameKind
import com.extem.mesh.protocol.MeshFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The "client" half of a link.
 *
 * One exchange looks like this:
 *   1. connect to the peer
 *   2. find our service and switch notifications on
 *   3. write our frames to TO_SERVER, chunk by chunk, waiting for each acknowledgement
 *   4. listen on FROM_SERVER until the peer says BYE (or we run out of patience)
 *   5. disconnect
 *
 * Because the peer advertises itself as soon as it starts, either phone could connect.
 * To avoid two connections fighting each other, the service only calls this class when
 * its own node ID is alphabetically smaller than the peer's (see MeshService).
 */
class BleLink(
    private val context: Context,
    private val log: (event: String, details: String) -> Unit
) {

    @SuppressLint("MissingPermission")
    suspend fun exchange(
        device: BluetoothDevice,
        frames: List<MeshFrame>,
        connectTimeoutMs: Long = 15_000,
        replyTimeoutMs: Long = 12_000
    ): List<MeshFrame> = withContext(Dispatchers.IO) {

        val steps = Channel<String>(Channel.UNLIMITED)
        val inbox = ArrayList<MeshFrame>()
        val lock = Any()
        val assembler = FrameAssembler()
        var mtu = 23

        val callback = object : BluetoothGattCallback() {

            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> steps.trySend(STEP_CONNECTED)
                    BluetoothProfile.STATE_DISCONNECTED -> steps.trySend(STEP_DISCONNECTED)
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                steps.trySend(if (status == BluetoothGatt.GATT_SUCCESS) STEP_SERVICES_OK else STEP_SERVICES_FAIL)
            }

            override fun onMtuChanged(gatt: BluetoothGatt, newMtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
                steps.trySend(STEP_MTU)
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                steps.trySend(if (status == BluetoothGatt.GATT_SUCCESS) STEP_NOTIFY_OK else STEP_NOTIFY_FAIL)
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                steps.trySend(if (status == BluetoothGatt.GATT_SUCCESS) STEP_WRITE_OK else STEP_WRITE_FAIL)
            }

            // Android 12 and older deliver the value inside the characteristic.
            @Deprecated("Required for API < 33")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                onChunk(characteristic.value ?: return)
            }

            // Android 13+ delivers the value directly.
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                onChunk(value)
            }

            private fun onChunk(chunk: ByteArray) {
                val bytes = assembler.accept(chunk) ?: return
                val frame = MeshFrame.decode(bytes) ?: return
                synchronized(lock) { inbox.add(frame) }
                log("client", "rx ${frame.kind} from ${frame.senderId}")
                if (frame.kind == FrameKind.BYE) steps.trySend(STEP_BYE)
            }
        }

        val gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: run {
                log("client", "connectGatt returned null")
                return@withContext emptyList()
            }

        try {
            if (await(steps, connectTimeoutMs, STEP_CONNECTED) != STEP_CONNECTED) {
                log("client", "connect timeout")
                return@withContext emptyList()
            }
            log("client", "connected to ${device.address}")
            gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)

            if (!gatt.discoverServices()) {
                log("client", "discoverServices refused")
                return@withContext emptyList()
            }
            if (await(steps, 10_000, STEP_SERVICES_OK, STEP_SERVICES_FAIL) != STEP_SERVICES_OK) {
                log("client", "service discovery failed")
                return@withContext emptyList()
            }

            val service = gatt.getService(BleConstants.SERVICE_UUID) ?: run {
                log("client", "mesh service missing on peer")
                return@withContext emptyList()
            }
            val writeCharacteristic = service.getCharacteristic(BleConstants.TO_SERVER_UUID) ?: run {
                log("client", "TO_SERVER missing on peer")
                return@withContext emptyList()
            }
            val notifyCharacteristic = service.getCharacteristic(BleConstants.FROM_SERVER_UUID) ?: run {
                log("client", "FROM_SERVER missing on peer")
                return@withContext emptyList()
            }

            // Ask for a bigger packet. If the peer refuses we simply keep the default.
            gatt.requestMtu(BleConstants.DESIRED_MTU)
            await(steps, 4_000, STEP_MTU)
            log("client", "mtu=$mtu")

            val cccd = notifyCharacteristic.getDescriptor(BleConstants.CCCD_UUID) ?: run {
                log("client", "notification descriptor missing")
                return@withContext emptyList()
            }
            gatt.setCharacteristicNotification(notifyCharacteristic, true)
            writeDescriptor(gatt, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)

            if (await(steps, 6_000, STEP_NOTIFY_OK, STEP_NOTIFY_FAIL) != STEP_NOTIFY_OK) {
                log("client", "could not enable notifications")
                return@withContext emptyList()
            }

            // Send our frames, chunk by chunk. Every write waits for its acknowledgement.
            val packetSize = BleConstants.packetSize(mtu)
            var frameCounter = 0
            for (frame in frames) {
                frameCounter = (frameCounter + 1) and 0xFF
                for (chunk in ChunkCodec.encode(frameCounter, frame.encode(), packetSize)) {
                    if (!writeChunk(gatt, writeCharacteristic, chunk)) {
                        log("client", "write refused, aborting")
                        return@withContext snapshot(inbox, lock)
                    }
                    if (await(steps, 8_000, STEP_WRITE_OK, STEP_WRITE_FAIL) != STEP_WRITE_OK) {
                        log("client", "write failed, aborting")
                        return@withContext snapshot(inbox, lock)
                    }
                }
                log("client", "tx ${frame.kind} (${frame.text.length} chars)")
            }

            // Now wait for the peer's replies until it says BYE.
            val bye = await(steps, replyTimeoutMs, STEP_BYE, STEP_DISCONNECTED)
            if (bye != STEP_BYE) log("client", "no BYE from peer, closing anyway")

            return@withContext snapshot(inbox, lock)
        } finally {
            try {
                gatt.disconnect()
                gatt.close()
            } catch (e: SecurityException) {
                log("client", "disconnect denied: ${e.message}")
            }
            steps.close()
        }
    }

    private fun snapshot(inbox: List<MeshFrame>, lock: Any): List<MeshFrame> =
        synchronized(lock) { inbox.toList() }

    @SuppressLint("MissingPermission")
    private fun writeChunk(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        chunk: ByteArray
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // The Android 13+ overload returns a status code: 0 means "queued".
            return gatt.writeCharacteristic(
                characteristic,
                chunk,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == 0
        }
        characteristic.value = chunk
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        @Suppress("DEPRECATION")
        val queued = gatt.writeCharacteristic(characteristic)
        return queued
    }

    @SuppressLint("MissingPermission")
    private fun writeDescriptor(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // The Android 13+ overload returns a status code: 0 means "queued".
            return gatt.writeDescriptor(descriptor, value) == 0
        }
        descriptor.value = value
        @Suppress("DEPRECATION")
        val queued = gatt.writeDescriptor(descriptor)
        return queued
    }

    /** Waits until one of [wanted] arrives, ignoring everything else. Null on timeout. */
    private suspend fun await(
        steps: ReceiveChannel<String>,
        timeoutMs: Long,
        vararg wanted: String
    ): String? = withTimeoutOrNull(timeoutMs) {
        var found: String? = null
        while (found == null) {
            val step = steps.receive()
            if (wanted.contains(step)) found = step
        }
        found
    }

    private companion object {
        const val STEP_CONNECTED = "connected"
        const val STEP_DISCONNECTED = "disconnected"
        const val STEP_SERVICES_OK = "services_ok"
        const val STEP_SERVICES_FAIL = "services_fail"
        const val STEP_MTU = "mtu"
        const val STEP_NOTIFY_OK = "notify_ok"
        const val STEP_NOTIFY_FAIL = "notify_fail"
        const val STEP_WRITE_OK = "write_ok"
        const val STEP_WRITE_FAIL = "write_fail"
        const val STEP_BYE = "bye"
    }
}
