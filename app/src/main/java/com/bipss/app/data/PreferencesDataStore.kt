package com.bipss.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.bipss.app.SimulationMode

/**
 * The app's single preferences store. Created once, by Koin, because DataStore
 * forbids two instances on one file in a process.
 *
 * The file is chosen at runtime rather than by a `preferencesDataStore`
 * delegate so simulation mode gets its own: the simulator's onboarding,
 * profile and paired-band state never touch the real ones.
 */
fun createPreferencesDataStore(context: Context): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(
        produceFile = { context.preferencesDataStoreFile(SimulationMode.preferencesName(context)) }
    )
