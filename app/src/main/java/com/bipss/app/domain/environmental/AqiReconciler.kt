package com.bipss.app.domain.environmental

import com.bipss.app.domain.BaselineSchedule
import com.bipss.app.domain.BaselineStage
import java.time.Instant
import java.time.ZoneId

/** One stored context check: where the user was judged to be, and both air readings at that moment. */
data class AqiContextSample(
    val takenAtMs:  Long,
    val kind:       EnvContextKind,
    val confidence: Float,
    /** Band ENS160 air-quality index, 1 (excellent) to 5 (unhealthy). */
    val bandUba:    Int?,
    /** CPCB station AQI, 0-500, from the latest environment sync. */
    val cpcbAqi:    Int?
)

/** Median and interquartile range of station AQI seen alongside one band category. */
data class AqiSpread(val median: Double, val q1: Double, val q3: Double, val n: Int)

/** Outdoors: which station AQI each band category has lined up with, for this user, here. */
data class OutdoorModel(val byUba: Map<Int, AqiSpread>, val samples: Int)

/** Indoors: the user's own usual band reading. */
data class IndoorModel(val medianUba: Double, val p90Uba: Double, val samples: Int)

data class Reconciliation(
    /** Days with at least one confident check that had a band reading - the shared baseline count. */
    val dayN:    Int,
    val stage:   BaselineStage,
    val outdoor: OutdoorModel?,
    val indoor:  IndoorModel?
) {
    companion object {
        val EMPTY = Reconciliation(0, BaselineStage.CALIBRATING, null, null)
    }
}

/**
 * The two learned correlations of spec 5.9, on the same day-1-to-90 schedule
 * as every other baseline (BaselineSchedule):
 *
 *  - Outdoor: when the user was confidently outdoors, which CPCB AQI each
 *    band category (1-5) came with - median and interquartile range per
 *    category, from at least [MIN_PER_CATEGORY] checks.
 *  - Indoor: when the user was confidently indoors, the band's own usual
 *    range (median and 90th percentile), from at least [MIN_INDOOR] checks.
 *    Self-referential by design: indoor air has no station to compare with.
 *
 * Context comes from GNSS and movement only (EnvContextClassifier); the two
 * air readings are never used to decide it. Pure Kotlin.
 */
object AqiReconciler {

    const val MIN_PER_CATEGORY = 5
    const val MIN_INDOOR = 20

    fun build(samples: List<AqiContextSample>, zone: ZoneId = ZoneId.systemDefault()): Reconciliation {
        val confident = samples.filter { it.confidence >= EnvContextClassifier.MIN_CONFIDENCE && it.kind != EnvContextKind.UNKNOWN }
        val withBand = confident.filter { it.bandUba != null }
        val dayN = withBand.map { Instant.ofEpochMilli(it.takenAtMs).atZone(zone).toLocalDate() }.distinct().size

        val outdoorPairs = withBand.filter { it.kind == EnvContextKind.OUTDOOR && it.cpcbAqi != null }
        val byUba = outdoorPairs.groupBy { it.bandUba!! }
            .filterValues { it.size >= MIN_PER_CATEGORY }
            .mapValues { (_, rows) ->
                val aqi = rows.map { it.cpcbAqi!!.toDouble() }.sorted()
                AqiSpread(quantile(aqi, 0.5), quantile(aqi, 0.25), quantile(aqi, 0.75), aqi.size)
            }
        val indoorUba = withBand.filter { it.kind == EnvContextKind.INDOOR }.map { it.bandUba!!.toDouble() }.sorted()

        return Reconciliation(
            dayN    = dayN,
            stage   = BaselineSchedule.stage(dayN),
            outdoor = byUba.takeIf { it.isNotEmpty() }?.let { OutdoorModel(it, outdoorPairs.size) },
            indoor  = indoorUba.takeIf { it.size >= MIN_INDOOR }
                ?.let { IndoorModel(quantile(it, 0.5), quantile(it, 0.9), it.size) }
        )
    }

    /** Linear-interpolated quantile of a sorted list. */
    internal fun quantile(sorted: List<Double>, q: Double): Double {
        if (sorted.size == 1) return sorted[0]
        val pos = q * (sorted.size - 1)
        val lo = pos.toInt()
        val hi = (lo + 1).coerceAtMost(sorted.size - 1)
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
    }
}

/** What the air-quality section shows, decided by context - never by blending the two readings. */
sealed interface AqiDisplay {
    /** No band air reading: the station only, as before. */
    data class StationOnly(val cpcbAqi: Int?) : AqiDisplay
    /** Outdoors: the station AQI is the headline; the band category corroborates. */
    data class Outdoor(val cpcbAqi: Int?, val bandUba: Int, val expected: AqiSpread?) : AqiDisplay
    /** Indoors: the band against the user's own indoor range; the station is labelled as outdoors. */
    data class Indoor(val bandUba: Int, val usual: IndoorModel?, val cpcbAqi: Int?) : AqiDisplay
    /** Context unclear: both shown, each labelled, nothing combined. */
    data class Unclear(val bandUba: Int, val cpcbAqi: Int?) : AqiDisplay

    companion object {
        /** A context check older than this no longer says where the user is. */
        const val CONTEXT_MAX_AGE_MS = 20 * 60_000L

        fun decide(context: EnvContext?, contextAgeMs: Long?, bandUba: Int?, cpcbAqi: Int?, rec: Reconciliation): AqiDisplay {
            if (bandUba == null) return StationOnly(cpcbAqi)
            val current = context?.takeIf { it.isConfident && contextAgeMs != null && contextAgeMs <= CONTEXT_MAX_AGE_MS }
            return when (current?.kind) {
                EnvContextKind.OUTDOOR -> Outdoor(cpcbAqi, bandUba,
                    rec.outdoor?.byUba?.get(bandUba)?.takeIf { BaselineSchedule.isUsable(rec.dayN) })
                EnvContextKind.INDOOR  -> Indoor(bandUba,
                    rec.indoor?.takeIf { BaselineSchedule.isUsable(rec.dayN) }, cpcbAqi)
                else                   -> Unclear(bandUba, cpcbAqi)
            }
        }

        /** The ENS160's own wording for its 1-5 index. */
        fun ubaLabel(uba: Int): String = when (uba) {
            1 -> "Excellent"
            2 -> "Good"
            3 -> "Moderate"
            4 -> "Poor"
            5 -> "Unhealthy"
            else -> "Unknown"
        }
    }
}
