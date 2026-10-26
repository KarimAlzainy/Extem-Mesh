package com.extem.mesh.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import com.extem.mesh.core.NodeIdentity

/**
 * Looks for other EXTEM-Mesh phones.
 *
 * The scan is filtered on our service UUID, so the system only reports phones that run
 * this app. The node ID comes from the scan response (manufacturer data 0xFFFF),
 * which lets us decide who should connect to whom before any connection is made.
 */
class BleScanner(
    context: Context,
    private val onPeer: (peerId: String, device: BluetoothDevice, rssi: Int) -> Unit,
    private val log: (event: String, details: String) -> Unit
) {

    private val manager: BluetoothManager? =
        context.getSystemService(BluetoothManager::class.java)

    private var scanning = false
    private val seen = HashMap<String, Long>()   // peer id -> last time reported (ms)

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val record = result?.scanRecord ?: return
            val manufacturer = record.getManufacturerSpecificData(BleConstants.MANUFACTURER_ID)
            if (manufacturer == null || manufacturer.size < 4) return

            val peerId = NodeIdentity.toHex(manufacturer.copyOfRange(0, 4))
            val device = result.device ?: return

            // A phone is reported many times per second; only forward it every few seconds.
            val now = System.currentTimeMillis()
            val last = seen[peerId] ?: 0L
            if (now - last < 3000L) return
            seen[peerId] = now

            onPeer(peerId, device, result.rssi)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            log("scan", "scan failed code=$errorCode")
        }
    }

    /** Starts scanning. Returns an error message, or null when the request was sent. */
    @SuppressLint("MissingPermission")
    fun start(): String? {
        val adapter = manager?.adapter ?: return "This phone has no Bluetooth adapter"
        if (!adapter.isEnabled) return "Bluetooth is turned off"
        if (scanning) return null

        val filter = ScanFilter.Builder()
            .setServiceUuid(BleConstants.SERVICE_PARCEL)
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        return try {
            adapter.bluetoothLeScanner?.startScan(listOf(filter), settings, callback)
                ?: return "BLE scanning is not available on this phone"
            scanning = true
            null
        } catch (e: SecurityException) {
            "Scanning denied: ${e.message}"
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!scanning) return
        scanning = false
        seen.clear()
        try {
            manager?.adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (e: SecurityException) {
            log("scan", "stop denied: ${e.message}")
        }
    }
}
