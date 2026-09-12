package com.bipss.app.ble

import java.util.UUID

/**
 * GATT identifiers, frozen as the app/firmware data contract.
 *
 * These replace the previous placeholders, which used the standard Heart Rate
 * service 0x180D. Because the scan filters on the service UUID, a placeholder
 * meant no BIPSS band could ever be discovered - the scan would run forever and
 * find nothing.
 *
 * The band MUST advertise [BIPSS_SERVICE_UUID] in its advertisement packet, not
 * merely expose it after connecting, or the ScanFilter will not match. Any
 * change here has to be made in the firmware at the same time.
 */
object BleConstants {

    /** Nordic UART-style vendor service carrying the packed sensor frame. */
    val BIPSS_SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")

    /** 24-byte sensor frame, NOTIFY, 1 Hz. See SensorFrameParser for the layout. */
    val CHAR_SENSOR_FRAME: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

    /** WRITE. Reserved for future use (time sync, buffered-history request). */
    val CHAR_CONTROL: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")

    /** Standard Battery Service - a SEPARATE service, not part of the vendor one. */
    val BATTERY_SERVICE: UUID    = UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB")
    val CHAR_BATTERY_LEVEL: UUID = UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB")

    /** Client Characteristic Configuration Descriptor - standard. */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    const val GATT_CONNECT_TIMEOUT_MS = 10_000L
    const val MAX_GATT_RETRIES = 3

    /** Requested ATT MTU. 247 lets a 24-byte frame fit with room to spare. */
    const val PREFERRED_MTU = 247

    /** Reconnect backoff ceiling. */
    const val MAX_RECONNECT_DELAY_MS = 60_000L
}
