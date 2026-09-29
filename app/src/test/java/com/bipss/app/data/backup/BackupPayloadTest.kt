package com.bipss.app.data.backup

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPayloadTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `a backup made before this release still restores`() {
        // The eight fields schema 1 had, with a baseline under the pre-v7 key.
        val v1 = """{"backedUpAtMs":1790000000000,"profileName":"A","profileAge":30,"profileHeightCm":170.0,
            "profileWeightKg":62.0,"themePreferenceOrdinal":2,
            "emergencyContacts":[{"name":"B","phoneNumber":"+919800000001","addedAtMs":1}],
            "baselineStats":[{"metricType":"HRV","mean":40.0,"m2":10.0,"count":5,"stdDev":1.5}]}"""
        val p = json.decodeFromString(BackupPayload.serializer(), v1)
        assertEquals(1, p.schemaVersion)
        assertEquals(null, p.profileGender)
        assertTrue(p.cyclePeriods.isEmpty() && p.waterLog.isEmpty() && p.ewmaBaselines.isEmpty())
        assertEquals("HRV", p.baselineStats.single().metricType)   // mapped to PRV on restore
    }

    @Test
    fun `everything in schema 2 survives the round trip`() {
        val p = BackupPayload(
            backedUpAtMs = 1L, profileName = "A", profileAge = 30, profileHeightCm = 170f, profileWeightKg = 62f,
            themePreferenceOrdinal = 2, emergencyContacts = listOf(BackupContact("B", "+919800000001", 1L)),
            baselineStats = emptyList(), schemaVersion = 2, profileGender = "FEMALE",
            ewmaBaselines = listOf(BackupEwma("PRV_night", 42.0, 9.0, 12, 5L, 6L)),
            dataDays = listOf(BackupDataDay(20000, 300)),
            waterLog = listOf(BackupWater(7L, 250)),
            cyclePeriods = listOf(BackupPeriod(20100, 20104), BackupPeriod(20128, null)),
            cycleNotes = listOf(BackupNote(20101, "tired", 8L))
        )
        val back = json.decodeFromString(BackupPayload.serializer(), json.encodeToString(BackupPayload.serializer(), p))
        assertEquals(p, back)
    }
}
