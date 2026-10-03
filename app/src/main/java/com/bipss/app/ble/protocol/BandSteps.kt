package com.bipss.app.ble.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.Serializable

/** Optional v2 extension, multiplexed on the status characteristic. */
data class BandSteps(val bootId: Long, val uptimeMs: Long, val total: Long)
data class BandStepEvent(val device: String, val packet: BandSteps, val receivedMs: Long)
object BandStepsParser {
    fun parse(bytes: ByteArray): BandSteps? {
        if (bytes.size != 20 || bytes[0] != 0x31.toByte() || bytes[1] != 1.toByte() ||
            bytes[14] != 1.toByte() || bytes.drop(15).any { it != 0.toByte() }) return null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply { position(2) }
        return BandSteps(b.int.toUInt().toLong(), b.int.toUInt().toLong(), b.int.toUInt().toLong())
    }
}

@Serializable data class BandStepAnchor(val device: String, val bootId: Long, val uptimeMs: Long,
    val total: Long, val day: Long, val receivedMs: Long)
object BandStepAccounting {
    /** Null means duplicate/out-of-order: keep the existing anchor. */
    fun delta(previous: BandStepAnchor?, device: String, packet: BandSteps, day: Long,
              nowMs: Long, sinceMidnightMs: Long): Long? {
        if (previous == null || previous.device != device || previous.bootId != packet.bootId) {
            if (previous != null && previous.device != device) return 0
            // The session's entire count belongs to today only when it started today.
            return packet.total.takeIf { packet.uptimeMs <= sinceMidnightMs && it <= packet.uptimeMs / 250 + 4 } ?: 0
        }
        val elapsed = (packet.uptimeMs - previous.uptimeMs) and 0xffffffffL
        if (elapsed == 0L || elapsed > 0x7fffffffL || nowMs < previous.receivedMs) return null
        // A cumulative counter has no per-day timestamps. Never attribute an overnight gap to today.
        if (day != previous.day) return 0
        val delta = (packet.total - previous.total) and 0xffffffffL
        return delta.takeIf { it <= elapsed / 250 + 4 }
    }
}
