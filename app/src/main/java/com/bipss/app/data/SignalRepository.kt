package com.bipss.app.data

import android.util.Log
import com.bipss.app.ble.BleGattManager
import com.bipss.app.data.db.dao.SignalEpochDao
import com.bipss.app.data.db.entity.SignalEpochEntity
import com.bipss.app.domain.signal.PulseReading
import com.bipss.app.domain.signal.SignalEpoch
import com.bipss.app.domain.signal.SignalPipeline
import com.bipss.app.domain.signal.SignalQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "SignalRepository"

/**
 * Runs the signal pipeline on the band's waveform for the life of the process
 * and publishes its results: each measured second of pulse (which
 * [SensorRepository] records as heart rate) and 30-second epochs (stored in
 * `signal_epochs`, and the source of PRV).
 *
 * Results are published as streams in sample order rather than matched to the
 * band's 1 Hz status packets by time: the two arrive on separate paths, and in
 * a fast replay they can be many seconds apart.
 *
 * Only a v2 band streams a waveform; with a v1 band nothing arrives here and
 * heart rate stays the band's own figure.
 */
class SignalRepository(
    private val ble:      BleGattManager,
    private val epochDao: SignalEpochDao,
    private val appScope: CoroutineScope
) {
    private val _latestPulse = MutableStateFlow<PulseReading?>(null)
    /** Most recent measured (not coasted, not Poor) pulse. */
    val latestPulse: StateFlow<PulseReading?> = _latestPulse.asStateFlow()

    private val _quality = MutableStateFlow<SignalQuality?>(null)
    /** Quality of the most recent pulse output; null while no waveform is streaming. */
    val quality: StateFlow<SignalQuality?> = _quality.asStateFlow()

    private val _pulses = MutableSharedFlow<PulseReading>(extraBufferCapacity = 256)
    /** Every measured second of pulse, in order. Coasted and Poor seconds are never emitted. */
    val pulses: SharedFlow<PulseReading> = _pulses.asSharedFlow()

    private val _epochs = MutableSharedFlow<SignalEpoch>(extraBufferCapacity = 64)
    val epochs: SharedFlow<SignalEpoch> = _epochs.asSharedFlow()

    private val resetRequested = AtomicBoolean(false)
    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        appScope.launch {
            // A new connection may be a rebooted band on a new clock: start a
            // fresh pipeline rather than join its samples to the old ones.
            ble.protocolVersion.collect { if (it == null) resetRequested.set(true) }
        }
        appScope.launch(Dispatchers.Default) {
            var pipeline = SignalPipeline()
            ble.waveform.collect { chunk ->
                if (resetRequested.getAndSet(false)) {
                    pipeline = SignalPipeline()
                    _latestPulse.value = null
                    _quality.value = null
                }
                val out = pipeline.accept(chunk)
                for (p in out.pulse) {
                    _quality.value = p.quality
                    if (p.isMeasured) {
                        _latestPulse.value = p
                        _pulses.emit(p)
                    }
                }
                for (epoch in out.epochs) {
                    runCatching { epochDao.upsert(SignalEpochEntity.from(epoch)) }
                        .onFailure { Log.e(TAG, "Epoch not stored", it) }
                    _epochs.emit(epoch)
                }
            }
        }
    }
}
