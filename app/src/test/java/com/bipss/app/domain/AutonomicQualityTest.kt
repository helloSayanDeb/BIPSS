package com.bipss.app.domain

import com.bipss.app.domain.ali.*
import org.junit.Assert.*
import org.junit.Test

class AutonomicQualityTest {
    private val reference = (0 until 240).map { StillEpoch(it * 30000L, 60.0, 50.0) }
    private val recent = (0 until 10).map { StillEpoch(10_000_000L + it * 30000, 80.0, 25.0) }
    @Test fun needsBothSignalsForEveryRequiredWindow() {
        assertNull(AutonomicLoad.evaluate(recent.map { it.copy(rmssdMs=null) }, reference,30).level)
        assertNull(AutonomicLoad.evaluate(recent, reference.map { it.copy(meanHr=null) },30).level)
        assertNull(AutonomicLoad.evaluate(recent.take(3) + recent.drop(3).map { it.copy(rmssdMs=null) },reference,30).level)
    }
    @Test fun duplicateOrInvalidEpochsCannotSatisfyCoverage() {
        assertNull(AutonomicLoad.evaluate(List(10) { recent.first() },reference,30).level)
        assertNull(AutonomicLoad.evaluate(recent.map { it.copy(meanHr=Double.NaN) },reference,30).level)
    }
    @Test fun sufficientPairedCleanSignalCanYieldALevel() {
        assertEquals(AliLevel.HIGH, AutonomicLoad.evaluate(recent,reference,30).level)
        assertNull(AutonomicLoad.evaluate(recent,reference,1).level)
    }
}
