package com.bipss.app.ui.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bipss.app.data.WeeklyReportData
import com.bipss.app.data.WeeklyReportRepository
import com.bipss.app.domain.report.WeeklyReport
import com.bipss.app.domain.time.LocalDay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ReportViewModel(
    private val repo: WeeklyReportRepository,
    private val exporter: com.bipss.app.export.DataExporter
) : ViewModel() {
    private val thisWeek = WeeklyReport.weekStart(LocalDay.epochDay(System.currentTimeMillis()))

    /** Monday of the week shown; opens on the last complete week. */
    val startDay = MutableStateFlow(thisWeek - 7)

    val report: StateFlow<WeeklyReportData?> = startDay.flatMapLatest { repo.week(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val canGoNext: Boolean get() = startDay.value < thisWeek

    fun previous() { startDay.value = startDay.value - 7 }
    fun next() { if (canGoNext) startDay.value = startDay.value + 7 }
    fun isCurrentWeek(day: Long) = day == thisWeek

    /** The week on screen as a PDF, handed to [onReady] as a share sheet. */
    fun share(onReady: (android.content.Intent) -> Unit) {
        val week = startDay.value
        viewModelScope.launch {
            runCatching { exporter.shareIntent(exporter.weeklyReportPdf(week), "application/pdf") }.onSuccess(onReady)
        }
    }
}
