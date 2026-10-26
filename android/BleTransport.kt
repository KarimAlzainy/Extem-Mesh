package org.extem.mesh

// BLE plumbing: advertise, scan, and do a "connect -> exchange -> disconnect"
// rendezvous with one peer at a time.
//
// This file is the part that can only be verified on real hardware. It is written
// against the current Android BLE APIs, but range, connection success rate and
// timing MUST be measured on at least 3 phones before any number is claimed.

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class BleTransport(
    private val context: Context,
    private val selfId: String,
    private val node: MeshNode,
    private val key: ByteArray,
    private val onDelivered: (String) -> Unit
) {
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6b1f0001-3c4d-4e5f-9a8b-0c1d2e3f4a5b")
        val SUMMARY_UUID: UUID = UUID.fromString("6b1f0002-3c4d-4e5f-9a8b-0c1d2e3f4a5b")
        val PAYLOAD_UUID: UUID = UUID.fromString("6b1f0003-3c4d-4e5f-9a8b-0c1d2e3f4a5b")
        const val MAX_MESSAGES_PER_RENDEZVOUS = 32
    }

    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = manager.adapter
    private var server: BluetoothGattServer? = null
    private var clientGatt: BluetoothGatt? = null
    private var busy = false

    var onLog: (String) -> Unit = {}

    fun start() {
        val a = adapter ?: return
        if (!a.isEnabled) {
            onLog("bluetooth is off")
            return
        }
        openServer()
        startAdvertising()
        startScanning()
    }

    fun stop() {
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        runCatching { clientGatt?.close() }
        runCatching { server?.close() }
        clientGatt = null
        server = null
        busy = false
    }

    // ------------------------------------------------------------------ server

    private fun openServer() {
        server = manager.openGattServer(
            context,
            object : BluetoothGattServerCallback() {
                override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                    onLog("peer ${device.address.takeLast(5)} connected to us")
                }

                override fun onCharacteristicReadRequest(
                    device: BluetoothDevice,
                    requestId: Int,
                    offset: Int,
                    characteristic: BluetoothGattCharacteristic
                ) {
                    val value = if (characteristic.uuid == SUMMARY_UUID) {
                        JSONArray(node.summary()).toString().toByteArray()
                    } else ByteArray(0)
                    server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
                }

                override fun onCharacteristicWriteRequest(
                    device: BluetoothDevice,
                    requestId: Int,
                    characteristic: BluetoothGattCharacteristic,
                    preparedWrite: Boolean,
                    responseNeeded: Boolean,
                    offset: Int,
                    value: ByteArray
                ) {
                    if (characteristic.uuid == PAYLOAD_UUID) {
                        receive(value)
                    }
                    if (responseNeeded) {
                        server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    }
                }
            }
        )

        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                SUMMARY_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                PAYLOAD_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
        )
        server?.addService(service)
    }

    // ------------------------------------------------------------- advertise

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            onLog("advertising as $selfId")
        }

        override fun onStartFailure(errorCode: Int) {
            onLog("advertise failed: $errorCode")
        }
    }

    private fun startAdvertising() {
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            // our node id, so a peer knows who it just met before connecting
            .addManufacturerData(0xFFFF, selfId.take(8).toByteArray())
            .build()

        advertiser.startAdvertising(settings, data, advertiseCallback)
    }

    // ------------------------------------------------------------------ scan

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            if (busy) return
            // never connect to ourselves: our own advertiser is visible to our scanner
            val advertisedId = result.scanRecord?.getManufacturerSpecificData(0xFFFF)
                ?.toString(Charsets.UTF_8)
            if (advertisedId != null && selfId.startsWith(advertisedId)) return
            rendezvous(device, advertisedId ?: device.address)
        }

        override fun onScanFailed(errorCode: Int) {
            onLog("scan failed: $errorCode")
        }
    }

    private fun startScanning() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        scanner.startScan(null, settings, scanCallback)
    }

    // ------------------------------------------------------------ rendezvous

    private fun rendezvous(device: BluetoothDevice, peerId: String) {
        busy = true
        clientGatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    gatt.discoverServices()
                } else {
                    finish(gatt)
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val service = gatt.getService(SERVICE_UUID) ?: return finish(gatt)
                val summary = service.getCharacteristic(SUMMARY_UUID) ?: return finish(gatt)
                gatt.readCharacteristic(summary)
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (characteristic.uuid != SUMMARY_UUID) return finish(gatt)
                val peerKnown = runCatching {
                    val arr = JSONArray(String(characteristic.value ?: ByteArray(0)))
                    (0 until arr.length()).map { arr.getString(it) }.toSet()
                }.getOrDefault(emptySet())

                val now = System.currentTimeMillis() / 1000
                val offers = node.offersTo(peerKnown, now).take(MAX_MESSAGES_PER_RENDEZVOUS)
                val payloadChar = gatt.getService(SERVICE_UUID)?.getCharacteristic(PAYLOAD_UUID)
                if (payloadChar == null || offers.isEmpty()) return finish(gatt)

                for (msg in offers) {
                    val give = node.spendCopies(msg, peerId)
                    if (give <= 0) continue
                    payloadChar.value = encode(msg, give)
                    gatt.writeCharacteristic(payloadChar)
                    // NOTE: for a production build, chain the writes on
                    // onCharacteristicWrite instead of firing them in a loop.
                }
                onLog("sent ${offers.size} message(s) to ${peerId.take(5)}")
                finish(gatt)
            }

            private fun finish(gatt: BluetoothGatt) {
                runCatching { gatt.close() }
                clientGatt = null
                busy = false
            }
        }, BluetoothDevice.TRANSPORT_LE)
    }

    // ---------------------------------------------------------------- frames

    private fun encode(msg: MeshMessage, copies: Int): ByteArray {
        val encrypted = Crypto.encrypt(key, msg.payload)
        return JSONObject()
            .put("id", msg.id)
            .put("src", msg.src)
            .put("dst", msg.dst)
            .put("createdAt", msg.createdAt)
            .put("ttl", msg.ttlSeconds)
            .put("copies", copies)
            .put("body", android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP))
            .toString()
            .toByteArray()
    }

    private fun receive(value: ByteArray) {
        val json = runCatching { JSONObject(String(value)) }.getOrNull() ?: return
        val id = json.optString("id")
        if (id.isEmpty() || node.known.contains(id)) return

        val body = android.util.Base64.decode(json.getString("body"), android.util.Base64.NO_WRAP)
        val plain = Crypto.decrypt(key, body) ?: return // tampered: drop silently

        val msg = MeshMessage(
            id = id,
            src = json.getString("src"),
            dst = json.getString("dst"),
            createdAt = json.getLong("createdAt"),
            ttlSeconds = json.getInt("ttl"),
            payload = plain,
            copies = json.getInt("copies")
        )
        node.store(msg)
        if (msg.dst == selfId) onDelivered(String(plain))
    }
}
