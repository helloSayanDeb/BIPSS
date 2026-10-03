package com.bipss.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bipss.app.data.SleepStageRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.DataInputStream
import kotlin.math.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import androidx.datastore.preferences.core.PreferenceDataStoreFactory

@RunWith(AndroidJUnit4::class)
class BiosignalRuntimeTest {
    private fun fixture(name: String, n: Int): FloatArray = DataInputStream(
        InstrumentationRegistry.getInstrumentation().context.assets.open("biosignal/$name")).use { stream -> FloatArray(n) { stream.readFloat() } }

    @Test fun sleepOnAndroidMatchesOriginalPyTorch() {
        val repo = GlobalContext.get().get<SleepStageRepository>()
        val input = fixture("sleep-input.bin", 16 * 1024)
        val expected = fixture("sleep-output.bin", 16 * 4)
        val actual = repo.runModel(input)
        assertEquals(16, actual.size)
        actual.forEachIndexed { i, row -> row.forEachIndexed { c, value -> assertEquals(expected[i * 4 + c], value, 0.002f) } }
    }

    @Test fun wholeNightFitsAndProducesOneResultPerEpoch() {
        val repo = GlobalContext.get().get<SleepStageRepository>()
        val input = FloatArray(960 * 1024) { i -> (sin(i * .2) + .3 * cos(i * .031)).toFloat() }
        val started = android.os.SystemClock.elapsedRealtime()
        val actual = repo.runModel(input)
        assertEquals(960, actual.size)
        assertTrue(actual.all { it.size == 4 && it.all(Float::isFinite) })
        android.util.Log.i("BiosignalTest", "8-hour inference: ${android.os.SystemClock.elapsedRealtime() - started} ms")
    }
    @Test fun foundationEncoderMatchesOriginalPyTorch() {
        val encoder = GlobalContext.get().get<com.bipss.app.data.FoundationEncoder>()
        assertTrue(encoder.available)
        val actual = encoder.encodePrepared(fixture("papagei-input.bin", 1250))
        assertArrayEquals(fixture("papagei-output.bin", 512), actual, 0.002f)
    }
    @Test fun capturedNightRunsBothModelsPersistsAndClears() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val root = java.io.File(target.cacheDir, "model-test-${System.nanoTime()}").apply { mkdirs() }
        val context = object : android.content.ContextWrapper(target) {
            override fun getNoBackupFilesDir() = root
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope) { java.io.File(root, "test.preferences_pb") }
        val archive = com.bipss.app.data.PpgArchive(context, scope)
        val encoder = com.bipss.app.data.FoundationEncoder(target)
        val repo = SleepStageRepository(target, store, archive, scope, encoder)
        try {
            repo.setEnabled(true)
            repo.enabled.first { it }
            delay(50)
            val start = (System.currentTimeMillis() / 30_000 - 240) * 30_000
            withContext(Dispatchers.Default) {
                for (offset in 0L..3_600_000L step 80L) {
                    val samples = List(4) { i -> (50000 + 800 * sin(2 * PI * 1.1 * (offset + i * 20) / 1000)).toInt() }
                    archive.accept(com.bipss.app.ble.WaveformChunk(offset / 20, samples, null, true, false,
                        offset == 0L, null, start + offset, 20.0))
                    if (offset % 30_000 == 0L) delay(5)
                }
            }
            withTimeout(5_000) {
                while (archive.read(start, start + 3_600_000).size < 120) delay(25)
            }
            assertEquals("Sleep-stage estimate saved.", repo.analyse(start, start + 3_600_000))
            val saved = repo.records.first().single()
            assertEquals(120, saved.stages.size)
            assertEquals(100, saved.capturedPercent)
            assertTrue(archive.root.listFiles()!!.any { it.extension == "features" })
            repo.clear()
            com.bipss.app.data.UserPreferencesRepository(target, store).clearLocalDataPrefs()
            assertTrue(repo.records.first().isEmpty())
            assertFalse(archive.enabled)
            assertTrue(archive.root.listFiles().isNullOrEmpty())
        } finally {
            repo.clear()
            scope.cancel()
        }
    }
}
