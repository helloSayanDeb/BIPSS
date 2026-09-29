package com.bipss.app.domain.recovery

import com.bipss.app.domain.BaselineSchedule
import com.bipss.app.domain.BaselineStage
import com.bipss.app.domain.baseline.Ewma
import com.bipss.app.domain.baseline.EwmaState
import com.bipss.app.domain.baseline.normalCdf
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** What one night contributes to recovery. Every field is nullable: nothing is substituted. */
data class NightInputs(
    val nightStartMs:  Long,
    /** Median RMSSD over clean, still epochs of the sleep session, ms. */
    val rmssdMs:       Double?,
    /** Mean heart rate over the same still epochs, bpm. */
    val restingHr:     Double?,
    val sleepMinutes:  Double?,
    /** Mean skin temperature during the session, C. */
    val skinTempC:     Double?
)

enum class RecoveryInput(
    val key: String,
    val label: String,
    /** Weight in the composite; documented in docs/research/scoring-references.md. */
    val weight: Double,
    /** Smallest SD the baseline is allowed to claim, so a few similar nights cannot inflate z. */
    val minSd: Double
) {
    PRV("recovery_rmssd", "Pulse rate variability", 0.45, 5.0),
    RESTING_HR("recovery_rhr", "Resting heart rate", 0.30, 2.0),
    SLEEP_DURATION("recovery_sleep_min", "Sleep duration", 0.15, 30.0),
    SKIN_TEMP("recovery_skin_temp", "Skin temperature", 0.10, 0.2)
}

/** One input's part in the verdict: its value, its baseline and its standard score. */
data class RecoveryComponent(
    val input: RecoveryInput,
    val value: Double,
    val baselineMean: Double?,
    /** Raw z against the baseline; null while that baseline is too young. */
    val z: Double?,
    /** z oriented so that positive = better recovered. */
    val signedZ: Double?
)

data class RecoveryResult(
    val nightStartMs: Long,
    /** 0-100, or null while calibrating or without enough inputs. */
    val score: Int?,
    val stage: BaselineStage,
    val dayN: Int,
    val components: List<RecoveryComponent>,
    /** Why there is no score, when there is none. */
    val reason: String?
)

/**
 * Nightly recovery against a personal EWMA baseline.
 *
 * Each input becomes a standard score against its own baseline and is
 * oriented so positive means better recovered: higher PRV, lower resting heart
 * rate, longer sleep, and skin temperature close to usual (a deviation either
 * way counts against, as it does in illness and after heavy load, measured
 * relative to an ordinary night's deviation). The composite
 *
 *     z_c = sum(w_i * s_i) / sqrt(sum(w_i^2))
 *
 * is normalised so it stays standard-normal when the inputs are independent,
 * and the score is 100 * Phi(z_c): 50 is an ordinary night for this person,
 * about 84 is one standard deviation better.
 *
 * The components are measurements against the person's own history; the
 * single number combining them is an estimate, and is labelled as one.
 */
object RecoveryEngine {

    /** z-scores are clamped here: beyond +-3 the difference is artefact more often than physiology. */
    private const val Z_CLAMP = 3.0

    fun evaluate(night: NightInputs, baselines: Map<RecoveryInput, EwmaState>, dayN: Int): RecoveryResult {
        val stage = BaselineSchedule.stage(dayN)
        val components = RecoveryInput.entries.mapNotNull { input ->
            val value = valueOf(night, input) ?: return@mapNotNull null
            val base = baselines[input]
            // Nothing is compared with the personal baseline before day 14:
            // while calibrating, the values are reported without a verdict.
            val z = if (stage == BaselineStage.CALIBRATING) null
                    else Ewma.z(base, value, input.minSd)?.coerceIn(-Z_CLAMP, Z_CLAMP)
            RecoveryComponent(input, value, base?.mean, z, z?.let { orient(input, it) })
        }

        fun result(score: Int?, reason: String?) = RecoveryResult(night.nightStartMs, score, stage, dayN, components, reason)

        if (stage == BaselineStage.CALIBRATING) return result(null, BaselineSchedule.missingLabel(dayN))
        val scored = components.filter { it.signedZ != null }
        // PRV or resting heart rate must be among them: sleep length and skin
        // temperature alone say too little about recovery to carry a number.
        if (scored.none { it.input == RecoveryInput.PRV || it.input == RecoveryInput.RESTING_HR }) {
            return result(null, "Needs a few nights of pulse data from sleep")
        }
        val num = scored.sumOf { it.input.weight * it.signedZ!! }
        val den = sqrt(scored.sumOf { it.input.weight * it.input.weight })
        val zc = (num / den).coerceIn(-Z_CLAMP, Z_CLAMP)
        return result((100 * normalCdf(zc)).roundToInt().coerceIn(0, 100), null)
    }

    /** The baselines after this night: each present input folded into its EWMA. */
    fun updatedBaselines(night: NightInputs, baselines: Map<RecoveryInput, EwmaState>): Map<RecoveryInput, EwmaState> =
        RecoveryInput.entries.associateWith { input ->
            val value = valueOf(night, input)
            if (value == null) baselines[input] else Ewma.update(baselines[input], value)
        }.filterValues { it != null }.mapValues { it.value!! }

    private fun valueOf(n: NightInputs, input: RecoveryInput): Double? = when (input) {
        RecoveryInput.PRV            -> n.rmssdMs
        RecoveryInput.RESTING_HR     -> n.restingHr
        RecoveryInput.SLEEP_DURATION -> n.sleepMinutes
        RecoveryInput.SKIN_TEMP      -> n.skinTempC
    }

    private fun orient(input: RecoveryInput, z: Double): Double = when (input) {
        RecoveryInput.PRV            -> z
        RecoveryInput.RESTING_HR     -> -z
        RecoveryInput.SLEEP_DURATION -> z
        // Either direction counts against, but centred on the expected |z|
        // of an ordinary night (sqrt(2/pi) = 0.80), so a typical night still
        // contributes zero rather than dragging every score below 50.
        RecoveryInput.SKIN_TEMP      -> -(abs(z) - EXPECTED_ABS_Z)
    }

    private val EXPECTED_ABS_Z = sqrt(2.0 / Math.PI)
}
