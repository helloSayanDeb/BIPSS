package com.bipss.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.bipss.app.SimulationMode
import com.bipss.app.debug.sim.SimScenario
import com.bipss.app.debug.sim.SimulatedBand
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.system.exitProcess

private const val TAG = "SimBandReceiver"

/**
 * adb control for the band simulator. Debug builds only, and guarded by
 * android.permission.DUMP in the debug manifest: the adb shell holds it, no
 * installable app can.
 *
 *   # switch to simulation mode (separate database; the process restarts)
 *   adb shell am broadcast -n com.bipss.app/.debug.SimBandReceiver \
 *       -a com.bipss.app.debug.SIM_MODE --ez enabled true [--ez skip_onboarding true]
 *
 *   # run a scenario: clean | walking | dropout | sitting_elevated_hr | overnight
 *   adb shell am broadcast -n com.bipss.app/.debug.SimBandReceiver \
 *       -a com.bipss.app.debug.SIM_START --es scenario overnight \
 *       [--ei backfill_minutes 480] [--ef period_scale 1.02]
 *
 *   adb shell am broadcast -n com.bipss.app/.debug.SimBandReceiver -a com.bipss.app.debug.SIM_STOP
 *
 *   # run the (normally 10:00) sleep analysis now
 *   adb shell am broadcast -n com.bipss.app/.debug.SimBandReceiver -a com.bipss.app.debug.ANALYSE_SLEEP
 *
 *   # back to real data (the simulated database is left untouched)
 *   adb shell am broadcast -n com.bipss.app/.debug.SimBandReceiver \
 *       -a com.bipss.app.debug.SIM_MODE --ez enabled false
 */
class SimBandReceiver : BroadcastReceiver(), KoinComponent {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_MODE -> {
                val enable = intent.getBooleanExtra("enabled", false)
                val flag = SimulationMode.flagFile(context)
                if (enable) {
                    val skip = intent.getBooleanExtra("skip_onboarding", false)
                    flag.writeText(if (skip) SimulationMode.FLAG_SKIP_ONBOARDING else "on")
                } else {
                    flag.delete()
                }
                Log.i(TAG, "Simulation mode ${if (enable) "on" else "off"} - restarting process")
                // The database and preferences are chosen once per process.
                Handler(Looper.getMainLooper()).postDelayed({ exitProcess(0) }, 300)
            }
            ACTION_START -> {
                if (!SimulationMode.isActive(context)) {
                    Log.e(TAG, "Refusing to simulate outside simulation mode - send SIM_MODE first")
                    return
                }
                val scenario = SimScenario.fromKey(intent.getStringExtra("scenario")) ?: run {
                    Log.e(TAG, "Unknown scenario; use one of ${SimScenario.entries.joinToString { it.key }}")
                    return
                }
                val backfill = intent.getIntExtra("backfill_minutes", 0)
                val scale = intent.getFloatExtra("period_scale", 1.0f).toDouble()
                get<SimulatedBand>().start(scenario, backfill, scale)
            }
            ACTION_STOP -> get<SimulatedBand>().stop()
            // Sleep analysis normally runs at 10:00; this runs it now, once.
            ACTION_ANALYSE_SLEEP -> androidx.work.WorkManager.getInstance(context).enqueue(
                androidx.work.OneTimeWorkRequestBuilder<com.bipss.app.worker.SleepAnalysisWorker>().build()
            )
        }
    }

    companion object {
        const val ACTION_MODE  = "com.bipss.app.debug.SIM_MODE"
        const val ACTION_START = "com.bipss.app.debug.SIM_START"
        const val ACTION_STOP  = "com.bipss.app.debug.SIM_STOP"
        const val ACTION_ANALYSE_SLEEP = "com.bipss.app.debug.ANALYSE_SLEEP"
    }
}
