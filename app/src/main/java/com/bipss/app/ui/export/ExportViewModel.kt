package com.bipss.app.ui.export

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bipss.app.R
import com.bipss.app.export.DataExporter
import com.bipss.app.localization.AppText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class ExportKind { HEALTH_REPORT, OXYGEN_RECORD, CSV }

data class ExportUi(val busy: ExportKind? = null, val error: String? = null)

class ExportViewModel(private val exporter: DataExporter) : ViewModel() {
    private val _ui = MutableStateFlow(ExportUi())
    val ui: StateFlow<ExportUi> = _ui

    /** Builds the file, then hands the share sheet to [onReady]. */
    fun run(kind: ExportKind, days: Int, onReady: (Intent) -> Unit) {
        if (_ui.value.busy != null) return
        _ui.value = ExportUi(busy = kind)
        viewModelScope.launch {
            try {
                val intent = when (kind) {
                    ExportKind.HEALTH_REPORT -> exporter.shareIntent(exporter.healthReportPdf(days), "application/pdf")
                    ExportKind.OXYGEN_RECORD -> exporter.shareIntent(exporter.breathingRecordPdf(), "application/pdf")
                    ExportKind.CSV -> exporter.shareIntent(exporter.csvBundle(), "application/zip")
                }
                _ui.value = ExportUi()
                onReady(intent)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _ui.value = ExportUi(error = AppText.text(R.string.copy_b21f42c63729, "Couldn't create the file. Please try again."))
            }
        }
    }
}
