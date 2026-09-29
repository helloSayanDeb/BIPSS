package com.bipss.app.debug.sim

import android.util.Log
import com.bipss.app.ble.BleGattManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.Random

private const val TAG = "SimulatedBand"

/**
 * Feeds a [SyntheticBand] into [BleGattManager] as if it were a connected v2
 * band. Debug builds only, and only in simulation mode (separate database,
 * banner on every screen).
 *
 * With [backfillMinutes] > 0 the first part of the scenario is generated as
 * fast as the app can absorb it, stamped as if it had arrived over the last
 * N minutes - a whole night can be replayed in minutes instead of hours. The
 * rest then plays in real time.
 */
class SimulatedBand(
    private val ble: BleGattManager,
    private val appScope: CoroutineScope,
    private val context: android.content.Context
) {
    private var job: Job? = null

    private val _running = MutableStateFlow<SimScenario?>(null)
    val running: StateFlow<SimScenario?> = _running

    fun start(scenario: SimScenario, backfillMinutes: Int = 0, periodScale: Double = 1.0) {
        stop()
        val sink = ble.attachSimulatedBand() ?: return
        _running.value = scenario
        // A real connection runs the band service; so does the simulated one
        // (its notification says "Simulated band"), so what the service does
        // - the indoor/outdoor checks - can be exercised too.
        com.bipss.app.ble.BleForegroundService.start(context)
        job = appScope.launch(Dispatchers.Default) {
            val band = SyntheticBand(scenario, seed = System.nanoTime(), periodScale = periodScale)
            val jitter = Random()
            val backfillMs = backfillMinutes.coerceAtLeast(0) * 60_000L
            val wallAtStart = System.currentTimeMillis() - backfillMs
            val uptimeAtStart = 5_000L
            fun arrivalOf(uptime: Long) = wallAtStart + (uptime - uptimeAtStart) + 15 + jitter.nextInt(40)

            Log.i(TAG, "Starting ${scenario.key}: backfill $backfillMinutes min, period x$periodScale")
            var steps = 0
            // Stops by itself if the app detaches the simulated band.
            while (isActive && ble.isSimulated.value) {
                for (p in band.step()) {
                    val arrival = arrivalOf(p.bandUptimeMs)
                    val wait = arrival - System.currentTimeMillis()
                    if (wait > 0) delay(wait)
                    if (p.isStatus) sink.status(p.bytes, arrival) else sink.waveform(p.bytes, arrival)
                }
                if (++steps % 250 == 0) yield()
                if (band.elapsedSeconds > scenario.defaultMinutes * 60.0 && scenario.defaultMinutes > 0 &&
                    backfillMs >= scenario.defaultMinutes * 60_000L
                ) {
                    // A fully backfilled scenario (e.g. a whole night) ends where it ends.
                    break
                }
            }
            Log.i(TAG, "Scenario ${scenario.key} finished after ${band.elapsedSeconds.toInt()} s")
            _running.value = null
            ble.detachSimulatedBand()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        if (_running.value != null) {
            _running.value = null
            ble.detachSimulatedBand()
        }
    }
}
