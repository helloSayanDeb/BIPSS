package com.bipss.app.ble

import java.util.UUID

/**
 * GATT identifiers - the app/firmware contract. The packet layouts are in
 * docs/ble-protocol-v2.md and ble/protocol/BandPackets.kt.
 *
 * The band MUST advertise [BIPSS_SERVICE_UUID] in its advertisement packet, not
 * merely expose it after connecting, or the ScanFilter will not match. Any
 * change here has to be made in the firmware at the same time.
 */
object BleConstants {

    /** Nordic UART-style vendor service. */
    val BIPSS_SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")

    /** NOTIFY, 1 Hz. v2: 20-byte status packet (header 0x21). v1: 24-byte legacy frame. */
    val CHAR_STATUS: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

    /** NOTIFY, 12.5 Hz, v2 only: 20-byte waveform packet (header 0x11). Its presence marks a v2 band. */
    val CHAR_WAVEFORM: UUID = UUID.fromString("6E400004-B5A3-F393-E0A9-E50E24DCCA9E")

    /** WRITE. Reserved for future use (time sync, buffered-history request). */
    val CHAR_CONTROL: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")

    /** Standard Battery Service - a SEPARATE service, not part of the vendor one. */
    val BATTERY_SERVICE: UUID    = UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB")
    val CHAR_BATTERY_LEVEL: UUID = UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB")

    /** Client Characteristic Configuration Descriptor - standard. */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    /**
     * Requested ATT MTU. v2 packets fit the default MTU, so this is no longer
     * required for correctness; a larger MTU only helps a v1 band, whose
     * 24-byte frame does not fit in 20 bytes of payload.
     */
    const val PREFERRED_MTU = 247

    /** Subscribe anyway if the stack never answers the MTU request. */
    const val MTU_ANSWER_TIMEOUT_MS = 3_000L

    /** Reconnect backoff ceiling. */
    const val MAX_RECONNECT_DELAY_MS = 60_000L
}
