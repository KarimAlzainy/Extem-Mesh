package com.extem.mesh.ble

import android.os.ParcelUuid
import java.util.UUID

/**
 * All the fixed numbers that make "EXTEM-Mesh" recognizable over Bluetooth.
 *
 * The 128-bit service UUID is chosen at random and is unique to this app:
 * two phones only talk to each other if they both advertise and search for it.
 *
 * The advertisement itself contains ONLY this UUID plus a 4-byte node ID inside the
 * scan-response manufacturer data. No message text is ever put inside an
 * advertisement: advertisements are broadcast to everyone nearby and are limited
 * to 31 bytes, so they are used as a "handshake", not as a transport.
 */
object BleConstants {

    /** Custom service: e7b10000-4c2f-4a1b-9d3e-5f6a7b8c9d01 */
    val SERVICE_UUID: UUID = UUID.fromString("e7b10000-4c2f-4a1b-9d3e-5f6a7b8c9d01")

    /** Client -> server. Write-with-response, chunked frames. */
    val TO_SERVER_UUID: UUID = UUID.fromString("e7b10001-4c2f-4a1b-9d3e-5f6a7b8c9d01")

    /** Server -> client. Notifications, chunked frames. */
    val FROM_SERVER_UUID: UUID = UUID.fromString("e7b10002-4c2f-4a1b-9d3e-5f6a7b8c9d01")

    /** Standard descriptor that switches notifications on. */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    val SERVICE_PARCEL: ParcelUuid = ParcelUuid(SERVICE_UUID)

    /** 0xFFFF is the "test / prototype" manufacturer ID used in the scan response. */
    const val MANUFACTURER_ID = 0xFFFF

    /** We ask for 185 bytes so a frame needs far fewer chunks; 23 is the BLE minimum. */
    const val DESIRED_MTU = 185

    /** Bytes available for one chunk once the 3-byte ATT header is subtracted. */
    fun packetSize(mtu: Int): Int = (mtu - 3).coerceIn(20, 200)
}
