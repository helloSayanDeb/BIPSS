package com.bipss.app.ui.bioage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bipss.app.data.BioAgeRepository
import com.bipss.app.data.BioAgeSnapshot
import com.bipss.app.data.UserPreferencesRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BioAgeViewModel(
    repo: BioAgeRepository,
    private val prefs: UserPreferencesRepository
) : ViewModel() {
    /** Null until the first read. */
    val snapshot: StateFlow<BioAgeSnapshot?> = repo.snapshot
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setLeanMass(pct: Double?) {
        viewModelScope.launch { runCatching { prefs.setLeanMassPct(pct) } }
    }
}
