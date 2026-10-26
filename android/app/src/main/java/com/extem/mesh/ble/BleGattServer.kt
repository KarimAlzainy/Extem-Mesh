package com.extem.mesh.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.extem.mesh.protocol.ChunkCodec
import com.extem.mesh.protocol.FrameAssembler
import com.extem.mesh.protocol.MeshFrame

/**
 * The "server" half of a link.
 *
 * It publishes our own GATT service. When another phone connects and writes chunks into
 * TO_SERVER, we rebuild the frames and hand them to [onFrame]. Replies go back as
 * notifications on FROM_SERVER, one chunk at a time.
 *
 * Only one phone is handled at a time: Stage 1 is about proving that a real link works,
 * not about handling a crowd.
 */
class BleGattServer(
    private val context: Context,
    private val onFrame: (MeshFrame) -> Unit,
    private val onConnection: (peer: String, connected: Boolean) -> Unit,
    private val log: (event: String, details: String) -> Unit
) {

    private val manager: BluetoothManager? =
        context.getSystemService(BluetoothManager::class.java)

    private var server: BluetoothGattServer? = null
    private var toServer: BluetoothGattCharacteristic? = null
    private var fromServer: BluetoothGattCharacteristic? = null

    private var device: BluetoothDevice? = null
    private var subscribed = false
    private var mtu = 23

    private val assembler = FrameAssembler()
    private val outgoing = ArrayDeque<ByteArray>()
    private var sending = false
    private var frameCounter = 0

    private val callback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            val peer = device?.address ?: "unknown"
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                this@BleGattServer.device = device
                assembler.reset()
                log("server", "peer connected ($peer)")
                onConnection(peer, true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                this@BleGattServer.device = null
                subscribed = false
                assembler.reset()
                synchronized(this@BleGattServer) {
                    outgoing.clear()
                    sending = false
                }
                log("server", "peer disconnected ($peer)")
                onConnection(peer, false)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            this@BleGattServer.mtu = mtu
            log("server", "mtu=$mtu")
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            val isCccd = descriptor?.uuid == BleConstants.CCCD_UUID
            val turnOn = value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true
            if (isCccd) {
                subscribed = turnOn
                log("server", "notifications ${if (turnOn) "enabled" else "disabled"}")
            }
            if (responseNeeded && device != null) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            // Every write is acknowledged so the sender can move on to the next chunk.
            if (responseNeeded && device != null) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
            if (characteristic?.uuid != BleConstants.TO_SERVER_UUID || value == null) return

            val frameBytes = assembler.accept(value) ?: return
            val frame = MeshFrame.decode(frameBytes) ?: run {
                log("server", "dropped malformed frame (${frameBytes.size} bytes)")
                return
            }
            log("server", "rx ${frame.kind} from ${frame.senderId}")
            onFrame(frame)
        }

        override fun onNotificationSent(device: BluetoothDevice?, status: Int) {
            synchronized(this@BleGattServer) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    log("server", "notification failed status=$status")
                    outgoing.clear()
                    sending = false
                    return
                }
                sending = false
                pumpLocked()
            }
        }
    }

    /** Opens the GATT server and registers the service. Returns an error message or null. */
    @SuppressLint("MissingPermission")
    fun start(): String? {
        val mgr = manager ?: return "BluetoothManager unavailable"
        val gattServer = mgr.openGattServer(context, callback)
            ?: return "Could not open a GATT server"

        val service = BluetoothGattService(
            BleConstants.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val writeCharacteristic = BluetoothGattCharacteristic(
            BleConstants.TO_SERVER_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val notifyCharacteristic = BluetoothGattCharacteristic(
            BleConstants.FROM_SERVER_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        notifyCharacteristic.addDescriptor(
            BluetoothGattDescriptor(
                BleConstants.CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )

        service.addCharacteristic(writeCharacteristic)
        service.addCharacteristic(notifyCharacteristic)

        if (!gattServer.addService(service)) {
            gattServer.close()
            return "Could not register the mesh service"
        }

        server = gattServer
        toServer = writeCharacteristic
        fromServer = notifyCharacteristic
        log("server", "service registered")
        return null
    }

    /** Queues frames and sends them as chunked notifications. */
    fun sendFrames(frames: List<MeshFrame>) {
        if (frames.isEmpty()) return
        synchronized(this) {
            if (device == null) {
                log("server", "no peer connected, ${frames.size} frame(s) not sent")
                return
            }
            if (!subscribed) {
                log("server", "peer did not subscribe, ${frames.size} frame(s) not sent")
                return
            }
            for (frame in frames) {
                frameCounter = (frameCounter + 1) and 0xFF
                val chunks = ChunkCodec.encode(
                    frameCounter,
                    frame.encode(),
                    BleConstants.packetSize(mtu)
                )
                outgoing.addAll(chunks)
            }
            if (!sending) pumpLocked()
        }
    }

    /** Sends the next queued chunk. Must be called while holding the lock. */
    @SuppressLint("MissingPermission")
    private fun pumpLocked() {
        val gattServer = server
        val characteristic = fromServer
        val peer = device
        val chunk = outgoing.removeFirstOrNull()

        if (gattServer == null || characteristic == null || peer == null || chunk == null) {
            sending = false
            return
        }

        sending = true
        characteristic.value = chunk

        // Android 13+ returns a status code (0 = success), older versions return a boolean.
        val queued: Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gattServer.notifyCharacteristicChanged(peer, characteristic, false, chunk) == 0
        } else {
            @Suppress("DEPRECATION")
            gattServer.notifyCharacteristicChanged(peer, characteristic, false)
        }

        if (!queued) {
            sending = false
            outgoing.clear()
            log("server", "could not queue notification")
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        synchronized(this) {
            outgoing.clear()
            sending = false
        }
        try {
            server?.close()
        } catch (e: SecurityException) {
            log("server", "close denied: ${e.message}")
        }
        server = null
        device = null
        subscribed = false
        log("server", "stopped")
    }
}
