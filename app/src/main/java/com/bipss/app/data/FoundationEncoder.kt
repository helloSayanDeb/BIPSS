package com.bipss.app.data

import android.content.Context
import ai.onnxruntime.*
import com.bipss.app.domain.sleep.PpgEpoch
import com.bipss.app.domain.signal.FoundationInput
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlinx.serialization.Serializable

@Serializable data class FoundationFeatures(val model: String = "papagei-s-13983110",
    val preprocessing: String = "archive34-cheby2-fourier-zscore-v1",
    val windowStarts: List<Long>, val vectors: List<List<Float>>)

/** Frozen encoder, deliberately no disease, age, sleep or recovery prediction head.
 * Debug-only checkpoint until its publisher clarifies redistribution terms.
 * These features never enter alerts, Coach advice or consumer scores.
 */
class FoundationEncoder(private val context: Context) {
    val available: Boolean get() = context.assets.list("biosignal")?.contains("papagei_s.onnx") == true
    private fun session(): OrtSession {
        val bytes = context.assets.open("biosignal/papagei_s.onnx").use { it.readBytes() }
        val meta = org.json.JSONObject(context.assets.open("biosignal/papagei_s.json").bufferedReader().use { it.readText() })
        require(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } == meta.getString("sha256"))
        return OrtSession.SessionOptions().use { options ->
            // Pad+MaxPool fusion changes explicit zero padding to -infinity and fails parity.
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
            options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1)
            OrtEnvironment.getEnvironment().createSession(bytes, options)
        }
    }
    private fun encode(session: OrtSession, input: FloatArray): FloatArray {
        require(input.size == 1250 && input.all { it.isFinite() })
        OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), FloatBuffer.wrap(input), longArrayOf(1, 1, 1250)).use { tensor ->
            session.run(mapOf("ppg" to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val values = (result[0].value as Array<FloatArray>)[0]
                require(values.size == 512 && values.all { it.isFinite() })
                return values
            }
        }
    }
    fun encodePrepared(input: FloatArray): FloatArray = session().use { encode(it, input) }
    fun extract(epochs: List<PpgEpoch>): FoundationFeatures? {
        if (!available) return null
        val candidates = epochs.filter { it.valid }.sortedBy { it.startMs }
        if (candidates.isEmpty()) return null
        // Bounded nightly compute/storage; evenly spaced windows, with provenance.
        val chosen = (0 until minOf(12, candidates.size)).map { candidates[it * candidates.size / minOf(12, candidates.size)] }
        val starts = mutableListOf<Long>(); val vectors = mutableListOf<List<Float>>()
        session().use { session -> chosen.forEach { epoch ->
            FoundationInput.prepare(epoch.samples)?.let { input ->
                starts += epoch.startMs + 10_000; vectors += encode(session, input).toList()
            }
        } }
        return FoundationFeatures(windowStarts = starts, vectors = vectors)
    }
}
