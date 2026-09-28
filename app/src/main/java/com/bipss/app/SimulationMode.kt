package com.bipss.app

import android.content.Context
import java.io.File

/**
 * Whether this process is running against the debug band simulator.
 *
 * Simulated data must never mix with real data, so simulation mode is a
 * process-wide switch decided once at start-up: the debug-only receiver writes
 * or deletes a flag file and then ends the process, and on the next launch the
 * app opens a separate database and a separate preferences file. Sharing,
 * Drive backup and notifications are off while it is on, and every screen
 * carries a "Simulated band - not real data" banner.
 *
 * Release builds cannot enter it: [BuildConfig.DEBUG] is false there, and the
 * receiver that creates the flag only exists in the debug source set.
 */
object SimulationMode {

    const val FLAG_FILE = "band_simulation_mode"
    /** Written into the flag file to ask for a ready-made profile so onboarding can be skipped. */
    const val FLAG_SKIP_ONBOARDING = "skip_onboarding"

    private const val REAL_DB = "bipss_db"
    private const val SIM_DB = "bipss_sim_db"
    private const val REAL_PREFS = "bipss_prefs"
    private const val SIM_PREFS = "bipss_sim_prefs"

    @Volatile private var cached: Boolean? = null

    fun flagFile(context: Context): File = File(context.noBackupFilesDir, FLAG_FILE)

    fun isActive(context: Context): Boolean =
        cached ?: (BuildConfig.DEBUG && flagFile(context).exists()).also { cached = it }

    fun databaseName(context: Context): String = if (isActive(context)) SIM_DB else REAL_DB

    fun preferencesName(context: Context): String = if (isActive(context)) SIM_PREFS else REAL_PREFS

    fun wantsSeededProfile(context: Context): Boolean =
        isActive(context) && runCatching { flagFile(context).readText().contains(FLAG_SKIP_ONBOARDING) }.getOrDefault(false)
}
