package com.bipss.app.data

import android.content.Context
import com.bipss.app.SimulationMode
import com.bipss.app.ble.WaveformChunk
import com.bipss.app.domain.sleep.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.*

/** Private, bounded waveform cache. Never sent to Drive/sharing. 72-hour retention.
 * A dropped writer queue item remains a missing epoch; acquisition never waits on disk.
 */
class PpgArchive(context: Context, scope: CoroutineScope) {
    val root = File(context.noBackupFilesDir, if (SimulationMode.isActive(context)) "sleep_model_sim" else "sleep_model")
    private val lock = Any()
    @Volatile var enabled = false
        private set
    private var builder = PpgEpochBuilder()
    private var generation = 0
    private val queue = Channel<Pair<Int, PpgEpoch>>(32)
    init { scope.launch(Dispatchers.IO) {
        synchronized(lock) { prune(System.currentTimeMillis()) }
        for ((gen, epoch) in queue) synchronized(lock) {
            if (enabled && gen == generation) runCatching {
                root.mkdirs()
                val file = File(root, "${epoch.startMs / 3_600_000}.ppg")
                DataOutputStream(BufferedOutputStream(FileOutputStream(file, true))).use { stream ->
                    stream.writeLong(epoch.startMs); stream.writeBoolean(epoch.valid)
                    epoch.samples.forEach { stream.writeFloat(it) }
                }
                prune(epoch.startMs)
            }
        }
    } }
    fun setEnabled(value: Boolean) = synchronized(lock) {
        if (enabled != value) { generation++; builder = PpgEpochBuilder() }
        enabled = value
    }
    fun accept(chunk: WaveformChunk) = synchronized(lock) {
        if (enabled) builder.accept(chunk).forEach { queue.trySend(generation to it) }
    }
    private fun prune(now: Long) {
        root.listFiles()?.filter { it.extension == "ppg" &&
            (it.nameWithoutExtension.toLongOrNull() ?: Long.MAX_VALUE) < now / 3_600_000 - 72 }
            ?.forEach { it.delete() }
    }
    fun read(fromMs: Long, toMs: Long): List<PpgEpoch> = synchronized(lock) {
        require(toMs > fromMs && toMs - fromMs <= 10 * 3_600_000L)
        prune(System.currentTimeMillis())
        val result = sortedMapOf<Long, PpgEpoch>()
        for (hour in fromMs / 3_600_000..toMs / 3_600_000) {
            val file = File(root, "$hour.ppg")
            if (!file.exists()) continue
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { stream ->
                while (stream.available() >= 8 + 1 + 4096) {
                    val at = stream.readLong(); val valid = stream.readBoolean()
                    val samples = FloatArray(1024) { stream.readFloat() }
                    if (at >= fromMs && at + 30_000 <= toMs) result[at] = PpgEpoch(at, samples, valid)
                }
            }
        }
        result.values.toList()
    }
    fun clear() = synchronized(lock) {
        enabled = false; generation++; builder = PpgEpochBuilder()
        root.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
    }
    fun saveFeatures(startMs: Long, json: String) = synchronized(lock) {
        if (enabled) {
            root.mkdirs()
            val file = android.util.AtomicFile(File(root, "$startMs.features"))
            val stream = file.startWrite()
            try { stream.write(json.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
            catch (e: Exception) { file.failWrite(stream); throw e }
            root.listFiles()?.filter { it.extension == "features" }?.sortedByDescending { it.name }
                ?.drop(90)?.forEach { it.delete() }
        }
    }
}
