package com.bipss.app.ble

import com.bipss.app.ble.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BandStepsTest {
    private val old = BandStepAnchor("band", 7, 100000, 200, 10, 1000000)
    private fun delta(p: BandSteps, previous: BandStepAnchor? = old, day: Long = 10) =
        BandStepAccounting.delta(previous, "band", p, day, 1005000, 500000)
    @Test fun parserPinsFirmwareLayout() {
        val bytes = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
            .put(0x31).put(1).putInt(-1).putInt(100000).putInt(200).put(1).array()
        assertEquals(BandSteps(0xffffffffL,100000,200), BandStepsParser.parse(bytes))
        bytes[14] = 0; assertNull(BandStepsParser.parse(bytes))
        assertNull(BandStepsParser.parse(ByteArray(24)))
    }
    @Test fun duplicatesAndOldPacketsAreIgnored() {
        assertNull(delta(BandSteps(7,100000,200)))
        assertNull(delta(BandSteps(7,99000,199)))
        assertEquals(8L,delta(BandSteps(7,105000,208)))
    }
    @Test fun implausibleJumpKeepsAnchor() { assertNull(delta(BandSteps(7,105000,2000))) }
    @Test fun rebootDoesNotSubtractOldCounter() { assertEquals(4L,delta(BandSteps(8,2000,4))) }
    @Test fun overnightGapIsNotAssignedToNewDay() { assertEquals(0L,delta(BandSteps(7,105000,208),day=11)) }
    @Test fun firstConnectionFromAnOlderBootDoesNotImportLifetimeSteps() {
        assertEquals(0L,delta(BandSteps(7,600000,200),previous=null))
    }
    @Test fun firstConnectionSinceTodaysBootCanRecoverSteps() {
        assertEquals(200L,delta(BandSteps(7,100000,200),previous=null))
    }
    @Test fun counterAndUptimeWrapAreUnsigned() {
        val previous=old.copy(uptimeMs=0xffffff00L,total=0xfffffffeL)
        assertEquals(4L,delta(BandSteps(7,1000,2),previous))
    }
    @Test fun switchingBandsDoesNotReimportSessionTotals() {
        assertEquals(0L,delta(BandSteps(7,100000,200),old.copy(device="other")))
    }
}
