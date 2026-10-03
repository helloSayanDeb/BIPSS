package com.bipss.app.domain

import kotlinx.serialization.Serializable

/** A saved calculation, not a daily average. Inputs stay attached to their original result. */
@Serializable data class InsightInput(
    val key: String, val label: String, val value: Double? = null,
    val unit: String = "", val detail: String = "", val baseline: Double? = null,
    val isNumeric: Boolean = true
)
@Serializable data class InsightRecord(
    val type: String, val atMs: Long, val value: Double?, val display: String,
    val scale: Int, val status: String, val available: Boolean, val partial: Boolean,
    val missing: List<String>, val factors: List<String>, val inputs: List<InsightInput>,
    val method: String, val provisional: Boolean = false
) {
    companion object {
        fun from(score: RiskScore) = com.bipss.app.localization.AppText.inEnglish { InsightRecord(
            score.type.name, score.computedAt,
            if (!score.hasNumber || score.scaleMax == 0) null else if (score.type == RiskScoreType.STRAIN)
                score.displayValue?.replace(',', '.')?.let(com.bipss.app.localization.LocalNumbers::doubleOrNull) else score.score.toDouble(),
            if (score.hasNumber) score.valueWithScale else "—", score.scaleMax,
            if (score.hasNumber) score.levelWord else score.reasonLine,
            score.hasNumber, score.availability == ScoreAvailability.PARTIAL,
            score.missingInputs, score.factors.map { it.label }, score.inputs, score.type.method, score.provisional
        ) }
    }
}

/** Retain the last usable daily result when a later reading becomes unavailable. */
fun mergeInsightRecords(old: Map<Long, Map<String, InsightRecord>>, day: Long, records: List<InsightRecord>): Map<Long, Map<String, InsightRecord>> {
    val next = old[day].orEmpty().toMutableMap()
    records.forEach { record ->
        val previous = next[record.type]
        if (previous == null || (record.atMs >= previous.atMs && (record.available || !previous.available))) next[record.type] = record
    }
    return old.filterKeys { it in (day - 89)..day } + (day to next)
}
