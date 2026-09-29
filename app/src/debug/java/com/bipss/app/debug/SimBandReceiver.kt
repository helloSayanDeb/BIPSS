package com.bipss.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.bipss.app.SimulationMode
import com.bipss.app.debug.sim.SimScenario
import com.bipss.app.debug.sim.SimulatedBand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
 *   # run the (normally every 15 min) environment sync now, once
 *   adb shell am broadcast -n com.bipss.app/.debug.SimBandReceiver -a com.bipss.app.debug.ENV_SYNC
 *
 *   # run the (normally 10:00) sleep analysis now
 *   adb shell am broadcast -n com.bipss.app/.debug.SimBandReceiver -a com.bipss.app.debug.ANALYSE_SLEEP
 *
 *   # pretend onboarding was finished by an older version, to walk the catch-up
 *   # flow (applies to whichever preferences are active; the process restarts)
 *   adb shell am broadcast -n com.bipss.app/.debug.SimBandReceiver \
 *       -a com.bipss.app.debug.ONBOARDING_VERSION --ei version 1 [--ez clear_gender true]
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
            ACTION_ONBOARDING_VERSION -> {
                val version = intent.getIntExtra("version", 1)
                val clearGender = intent.getBooleanExtra("clear_gender", false)
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    // Key names as in UserPreferencesRepository (private there).
                    get<DataStore<Preferences>>().edit {
                        it[intPreferencesKey("onboarding_version")] = version
                        if (clearGender) it.remove(stringPreferencesKey("profile_gender"))
                    }
                    Log.i(TAG, "onboarding_version = $version${if (clearGender) ", gender cleared" else ""} - restarting process")
                    pending.finish()
                    Handler(Looper.getMainLooper()).postDelayed({ exitProcess(0) }, 300)
                }
            }
            ACTION_ENV_SYNC -> androidx.work.WorkManager.getInstance(context).enqueue(
                androidx.work.OneTimeWorkRequestBuilder<com.bipss.app.worker.EnvDataSyncWorker>().build()
            )
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
        const val ACTION_ONBOARDING_VERSION = "com.bipss.app.debug.ONBOARDING_VERSION"
        const val ACTION_ENV_SYNC = "com.bipss.app.debug.ENV_SYNC"
    }
}
