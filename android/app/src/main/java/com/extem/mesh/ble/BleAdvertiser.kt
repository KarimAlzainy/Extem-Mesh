package com.extem.mesh.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.content.Context

/**
 * Publishes "I am an EXTEM-Mesh node" over Bluetooth LE.
 *
 * Two packets are broadcast:
 *  - advertisement: our 128-bit service UUID (so other phones can filter for us)
 *  - scan response: 0xFFFF manufacturer data holding the 4-byte node ID
 *
 * The scan response is only sent when another phone actively scans, so the node ID is
 * not shouted at every device in the room. Still, this is public metadata, not a secret.
 */
class BleAdvertiser(
    context: Context,
    private val nodeIdBytes: ByteArray,
    private val log: (event: String, details: String) -> Unit
) {

    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    private var advertisingSet: AdvertisingSet? = null

    private val callback = object : AdvertisingSetCallback() {

        override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
            // status 0 means success (the AdvertisingSetCallback result codes are not
            // exposed as public constants on every API level, so compare with 0).
            if (status == 0) {
                advertisingSet = set
                log("advertise", "started txPower=$txPower dBm")
            } else {
                log("advertise", "start failed status=$status")
            }
        }

        override fun onAdvertisingSetStopped(set: AdvertisingSet?) {
            advertisingSet = null
            log("advertise", "stopped")
        }

        override fun onAdvertisingDataSet(set: AdvertisingSet?, status: Int) {
            log("advertise", "advertisement data status=$status")
        }

        override fun onScanResponseDataSet(set: AdvertisingSet?, status: Int) {
            log("advertise", "scan response status=$status")
        }
    }

    /** Starts advertising. Returns a human readable error, or null when the request was sent. */
    @SuppressLint("MissingPermission")
    fun start(): String? {
        val ad = adapter ?: return "This phone has no Bluetooth adapter"
        if (!ad.isEnabled) return "Bluetooth is turned off"
        if (!ad.isMultipleAdvertisementSupported) return "This phone cannot act as a BLE advertiser"

        val parameters = AdvertisingSetParameters.Builder()
            .setConnectable(true)                       // other phones may connect to us
            .setScannable(true)                         // we also send a scan response
            .setLegacyMode(true)                        // works with the widest range of phones
            .setInterval(AdvertisingSetParameters.INTERVAL_MEDIUM)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
            .build()

        val advertisement = AdvertiseData.Builder()
            .setIncludeDeviceName(false)                // no need to leak the phone name
            .addServiceUuid(BleConstants.SERVICE_PARCEL)
            .build()

        val scanResponse = AdvertiseData.Builder()
            .addManufacturerData(BleConstants.MANUFACTURER_ID, nodeIdBytes)
            .build()

        ad.bluetoothLeAdvertiser?.startAdvertisingSet(
            parameters,
            advertisement,
            scanResponse,
            null,          // periodic parameters: not used
            null,          // duration: advertise until stopped
            callback
        ) ?: return "BLE advertising is not available on this phone"

        return null
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        try {
            adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(callback)
        } catch (e: SecurityException) {
            log("advertise", "stop denied: ${e.message}")
        }
        advertisingSet = null
    }
}
