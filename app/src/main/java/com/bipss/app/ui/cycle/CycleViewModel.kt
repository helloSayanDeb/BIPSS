package com.bipss.app.ui.cycle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bipss.app.data.CycleRepository
import com.bipss.app.data.Gender
import com.bipss.app.data.UserPreferencesRepository
import com.bipss.app.domain.cycle.CyclePredictor
import com.bipss.app.domain.cycle.CycleToday
import com.bipss.app.domain.cycle.DayKind
import com.bipss.app.domain.cycle.Period
import com.bipss.app.domain.time.LocalDay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

/** A calendar cell. */
data class CycleDayUi(val epochDay: Long, val dayOfMonth: Int, val kind: DayKind, val hasNote: Boolean, val isToday: Boolean, val isFuture: Boolean)

data class CycleUi(
    val todayDay: Long = LocalDay.epochDay(System.currentTimeMillis()),
    val periods: List<Period> = emptyList(),
    val today: CycleToday? = null,
    val month: YearMonth = YearMonth.now(),
    val days: List<CycleDayUi> = emptyList(),
    val selectedDay: Long? = null,
    /** False until the logged periods have been read, so nothing is claimed before then. */
    val loaded: Boolean = false
) {
    /** The ongoing period today's "end" would close, if any. */
    val ongoing: Period? get() = CyclePredictor.periodToEnd(periods, todayDay)?.takeIf { it.endDay == null }
    val selectedPeriod: Period? get() = selectedDay?.let { d -> periods.firstOrNull { p ->
        d in p.startDay..(p.endDay ?: (p.startDay + (today?.stats?.periodLengthDays ?: CyclePredictor.DEFAULT_PERIOD) - 1))
    } }
}

/**
 * Cycle tracking, for the Home card and the detail page (spec 7). Shown only
 * for profiles that selected Female. Everything is local; predictions are
 * estimates from the user's own logged history.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CycleViewModel(
    private val repo: CycleRepository,
    prefs: UserPreferencesRepository,
    /** Application scope: a note being saved as the page closes still lands. */
    private val appScope: CoroutineScope
) : ViewModel() {

    val isFemale: StateFlow<Boolean> = prefs.userProfile.map { it?.gender == Gender.FEMALE }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val month = MutableStateFlow(YearMonth.now())
    private val selected = MutableStateFlow<Long?>(null)

    val ui: StateFlow<CycleUi> = combine(LocalDay.midnightTicker(), repo.periods, month, selected) { today, periods, m, sel ->
        Quad(today, periods, m, sel)
    }.flatMapLatest { (today, periods, m, sel) ->
        val from = m.atDay(1).toEpochDay()
        val to = m.atEndOfMonth().toEpochDay()
        repo.notesBetween(from, to).map { notes ->
            val kinds = CyclePredictor.dayKinds(periods, from, to, today)
            CycleUi(
                todayDay = today,
                periods = periods,
                today = CyclePredictor.today(periods, today),
                month = m,
                days = (from..to).map { d ->
                    CycleDayUi(d, LocalDate.ofEpochDay(d).dayOfMonth, kinds[d] ?: DayKind.NONE,
                               hasNote = d in notes, isToday = d == today, isFuture = d > today)
                },
                selectedDay = sel,
                loaded = true
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CycleUi())

    private val _message = MutableStateFlow<String?>(null)
    /** Why the last log was refused, in a sentence; cleared by the next action. */
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _note = MutableStateFlow("")
    val note: StateFlow<String> = _note.asStateFlow()
    private val _noteSaved = MutableStateFlow(true)
    val noteSaved: StateFlow<Boolean> = _noteSaved.asStateFlow()
    private var noteJob: Job? = null

    fun previousMonth() { month.value = month.value.minusMonths(1) }
    fun nextMonth() { month.value = month.value.plusMonths(1) }

    fun select(day: Long) {
        _message.value = null
        flushNote()
        selected.value = day
        viewModelScope.launch {
            _note.value = repo.note(day).orEmpty()
            _noteSaved.value = true
        }
    }

    fun logStart(day: Long) = act { repo.logStart(day) }
    fun logEnd(day: Long) = act { repo.logEnd(day) }
    fun removePeriod(id: Long) = act { repo.removePeriod(id); null }

    private fun act(block: suspend () -> String?) {
        viewModelScope.launch { _message.value = block() }
    }

    /** Typing updates the field at once; the note is saved half a second after the last keystroke. */
    fun onNoteChange(text: String) {
        val day = selected.value ?: return
        _note.value = text
        _noteSaved.value = false
        noteJob?.cancel()
        noteJob = appScope.launch {
            delay(500)
            repo.saveNote(day, text)
            _noteSaved.value = true
        }
    }

    /** Saves a note still waiting for its half second - on switching day or leaving the page. */
    fun flushNote() {
        val day = selected.value ?: return
        if (_noteSaved.value) return
        noteJob?.cancel()
        val text = _note.value
        appScope.launch { repo.saveNote(day, text) }
        _noteSaved.value = true
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
