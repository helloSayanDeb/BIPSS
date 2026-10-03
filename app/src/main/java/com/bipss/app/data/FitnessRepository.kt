package com.bipss.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.bipss.app.domain.*
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class FitnessRepository(private val store: DataStore<Preferences>) {
    private val key = stringPreferencesKey("fitness_estimates_v1")
    private val json = Json { ignoreUnknownKeys = true }
    private fun read(p: Preferences): List<FitnessRecord> = p[key]?.let {
        runCatching { json.decodeFromString<List<FitnessRecord>>(it) }.getOrNull()
    } ?: emptyList()
    val records = store.data.map(::read)
    suspend fun save(input: FitnessInput): Boolean {
        val record = FitnessEstimate.calculate(input, System.currentTimeMillis()) ?: return false
        store.edit { it[key] = json.encodeToString((read(it) + record).takeLast(90)) }
        return true
    }
    suspend fun clear() { store.edit { it.remove(key) } }
}
