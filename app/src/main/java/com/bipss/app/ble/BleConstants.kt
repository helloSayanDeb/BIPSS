package com.bipss.app.ble

import java.util.UUID

object BleConstants {
    /**
     * TODO: Replace with the actual GATT service UUID once band firmware defines it.
     * This UUID is a placeholder used for BLE device scanning and filtering.
     */
    val BIPSS_SERVICE_UUID: UUID = UUID.fromString("0000180D-0000-1000-8000-00805F9B34FB")

    /**
     * TODO: Replace with actual characteristic UUIDs once band firmware defines the GATT profile.
     * These UUIDs are placeholders — the exact byte format depends on firmware and
     * needs confirming once hardware is flashed.
     */
    val CHAR_HEART_RATE:    UUID = UUID.fromString("00002A37-0000-1000-8000-00805F9B34FB") // Standard HR
    val CHAR_SPO2:          UUID = UUID.fromString("00002A5F-0000-1000-8000-00805F9B34FB") // Placeholder
    val CHAR_SKIN_TEMP:     UUID = UUID.fromString("00002A1C-0000-1000-8000-00805F9B34FB") // Placeholder
    val CHAR_ACCELEROMETER: UUID = UUID.fromString("00002A56-0000-1000-8000-00805F9B34FB") // Placeholder

    /** CCCD (Client Characteristic Configuration Descriptor) — standard BLE UUID */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    /** Connection timeout in milliseconds */
    const val GATT_CONNECT_TIMEOUT_MS = 10_000L

    /** Maximum GATT write attempts per operation */
    const val MAX_GATT_RETRIES = 3
}
