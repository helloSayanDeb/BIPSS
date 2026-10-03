package com.bipss.app.data

import android.content.Context
import ai.onnxruntime.*
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.bipss.app.domain.sleep.PpgSleepInput
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.FloatBuffer
import java.security.MessageDigest

@Serializable data class SleepStages(val startMs: Long, val stages: List<Int>,
    val capturedPercent: Int, val model: String = "wav2sleep-8b6e46ab-ppg", val createdMs: Long = System.currentTimeMillis())

class SleepStageRepository(private val context: Context, private val store: DataStore<Preferences>,
    private val archive: PpgArchive, scope: CoroutineScope, private val foundation: FoundationEncoder) {
    private val enabledKey = booleanPreferencesKey("sleep_stages_enabled")
    private val recordsKey = stringPreferencesKey("sleep_stage_records_v1")
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    val enabled = store.data.map { it[enabledKey] ?: false }
    val records = store.data.map { readRecords(it) }
    private fun readRecords(p: Preferences): List<SleepStages> = p[recordsKey]?.let {
        runCatching { json.decodeFromString<List<SleepStages>>(it) }.getOrNull()
    } ?: emptyList()
    init { scope.launch { enabled.collect { archive.setEnabled(it) } } }
    suspend fun setEnabled(value: Boolean) {
        archive.setEnabled(value)
        store.edit { it[enabledKey] = value }
    }
    suspend fun clear() = mutex.withLock {
        archive.clear()
        store.edit { it.remove(enabledKey); it.remove(recordsKey) }
    }

    suspend fun analyse(from: Long, to: Long): String = mutex.withLock {
        if (!enabled.first()) return@withLock "Enable sleep-stage estimates before recording a night."
        withContext(Dispatchers.Default) {
            if (to - from !in 3_600_000L..36_000_000L) return@withContext "Sleep-stage analysis currently supports recordings between 1 and 10 hours."
            val epochs = withContext(Dispatchers.IO) { archive.read(from, to) }.associateBy { it.startMs }
            val start = ((from + 29_999) / 30_000) * 30_000
            val count = ((to - start) / 30_000).toInt()
            val valid = BooleanArray(count) { epochs[start + it * 30_000]?.valid == true }
            val coverage = valid.count { it } * 100 / count
            if (coverage < 90) return@withContext "Only $coverage% of this night has usable pulse recordings. Keep your band connected overnight; at least 90% is needed."
            val good = epochs.values.filter { it.valid }
            val mean = good.sumOf { it.samples.sumOf(Float::toDouble) } / (good.size * 1024.0)
            val input = FloatArray(count * 1024) { index ->
                val epoch = epochs[start + (index / 1024) * 30_000]
                if (epoch?.valid == true) epoch.samples[index % 1024] else mean.toFloat()
            }
            val normalized = PpgSleepInput.normalize(input) ?: return@withContext "The pulse recording is too flat to analyse."
            ensureActive()
            val logits = runModel(normalized)
            require(logits.size == count)
            val stages = logits.mapIndexed { i, scores ->
                // Missing sections and their immediate neighbours stay unclassified.
                val nearbyValid = (maxOf(0, i - 2)..minOf(count - 1, i + 2)).all { valid[it] }
                PpgSleepInput.stage(scores, nearbyValid)
            }
            ensureActive()
            if (!enabled.first()) return@withContext "Sleep-stage estimates were turned off."
            // Independent research output: no untrained head is allowed to affect the stage labels.
            val features = try { foundation.extract(epochs.values.toList()) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { android.util.Log.w("FoundationEncoder", "Feature extraction unavailable", e); null }
            ensureActive()
            if (!enabled.first()) return@withContext "Sleep-stage estimates were turned off."
            if (features != null) withContext(Dispatchers.IO) { archive.saveFeatures(start, json.encodeToString(features)) }
            val result = SleepStages(start, stages, coverage)
            store.edit { p -> p[recordsKey] = json.encodeToString((readRecords(p).filter { it.startMs != start } + result).sortedBy { it.startMs }.takeLast(90)) }
            "Sleep-stage estimate saved."
        }
    }

    /** Whole-night inference preserves the checkpoint's instance-normalization context. */
    fun runModel(normalized: FloatArray): Array<FloatArray> {
        require(normalized.size % 1024 == 0 && normalized.size in 1024..1_228_800 && normalized.all { it.isFinite() })
        val bytes = context.assets.open("biosignal/wav2sleep_ppg.onnx").use { it.readBytes() }
        val manifest = org.json.JSONObject(context.assets.open("biosignal/wav2sleep.json").bufferedReader().use { it.readText() })
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(hash == manifest.getString("sha256")) { "Sleep model integrity check failed" }
        val env = OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1)
            options.setMemoryPatternOptimization(false)
            env.createSession(bytes, options).use { session ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(normalized), longArrayOf(1, normalized.size.toLong())).use { tensor ->
                    session.run(mapOf("ppg" to tensor)).use { result ->
                        @Suppress("UNCHECKED_CAST")
                        return (result[0].value as Array<Array<FloatArray>>)[0]
                    }
                }
            }
        }
    }
}
