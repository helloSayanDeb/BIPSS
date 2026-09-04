package com.bipss.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

// Singleton DataStore instance — one per app process
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "bipss_prefs")
