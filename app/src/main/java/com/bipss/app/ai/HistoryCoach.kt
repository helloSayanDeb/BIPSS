package com.bipss.app.ai

import com.bipss.app.localization.AppText
import com.bipss.app.R

import com.bipss.app.data.DailyActivityData
import com.bipss.app.domain.time.LocalDay
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

/** Read-only answers; cycle records are deliberately absent from this general context. */
object HistoryCoach {
    data class Sleep(val day: Long, val minutes: Int, val bedMinute: Int, val wakeMinute: Int, val interruptions: Int)
    data class Context(val today: Long, val data: DailyActivityData, val sleeps: List<Sleep>,
        val fitness: com.bipss.app.domain.FitnessRecord? = null,
        val stages: com.bipss.app.data.SleepStages? = null)
    // Internal regression examples; recognition also accepts natural paraphrases.
    internal val questions = listOf(
        "Compare my steps over two weeks", "How often did I reach my step goal?",
        "Which day had the most steps?", "What is my typical step count?",
        "What was my longest activity?", "What activities did I log this week?",
        "Compare my activity minutes over two weeks", "How many workouts did I log?",
        "What meals did I log today?", "Which meals have missing nutrition?",
        "How many calories did I log today?", "How much protein did I log today?",
        "How consistent is my sleep schedule?", "How consistent is my wake time?",
        "Compare my sleep over two weeks", "How many sleep records do I have?",
        "Were there interruptions in my latest sleep?", "Does my planned sleep window match my recorded sleep?",
        "Compare my recovery over two weeks", "How many days have strain records?",
        "Compare my strain over two weeks", "When did my band last send steps?",
        "What can my band sensors measure?", "What does Autonomic Load mean?",
        "How can I improve my band readings?"
    )
    fun normalize(text: String) = text.lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
    fun isCycleRequest(text: String) = HistoryIntent.match(text) == HistoryIntent.CYCLE_NOTES
    fun isKnownRequest(text: String): Boolean = HistoryIntent.match(text) != null ||
        normalize(text) in listOf("mark my habit done", "undo today s habit", "undo todays habit", "clear my habit") ||
        habitCommand(text) != null
    fun habitCommand(text: String): String? = Regex("^set my weekly habit:\\s*(.{3,80})$", RegexOption.IGNORE_CASE)
        .matchEntire(text.trim())?.groupValues?.get(1)?.trim()
    fun circularDistance(a: Int, b: Int): Int = abs((a - b + 2160) % 1440 - 720)
    /** Circular medoid works across midnight and for daytime sleep. */
    fun timingVariation(minutes: List<Int>): Int? {
        if (minutes.size < 3) return null
        val center = minutes.minBy { candidate -> minutes.sumOf { circularDistance(candidate, it) } }
        return minutes.map { circularDistance(center, it) }.average().roundToInt()
    }
    fun compare(label: String, values: Map<Long, Double>, today: Long, unit: String): String {
        val before = values.filterKeys { it in (today - 14)..(today - 8) }.values.filter { it.isFinite() }
        val recent = values.filterKeys { it in (today - 7) until today }.values.filter { it.isFinite() }
        val coverage = AppText.text(R.string.copy_7071e5e5bae8, "%1\$s/7 recorded days in the latest week; %2\$s/7 in the previous week.", recent.size, before.size)
        if (before.size < 3 || recent.size < 3) return AppText.text(R.string.copy_138de9374fde, "%1\$s: %2\$s I need at least 3 recorded days in each week to compare.", label, coverage)
        return AppText.text(R.string.copy_dd4ad6464da7, "%1\$s averaged %2\$s %3\$s in the latest week and %4\$s %5\$s the week before. %6\$s Missing days are excluded; today is still in progress. This describes your records, not a cause.", label, fmt(recent.average()), unit, fmt(before.average()), unit, coverage)
    }
    private fun fmt(n: Double) = "%.1f".format(java.util.Locale.ROOT, n)
    fun respond(question: String, c: Context): String? {
        val intent = HistoryIntent.match(question) ?: return null
        val d = c.data
        val days = (c.today - 6)..c.today
        val activities = d.activities.filter { LocalDay.epochDay(it.atMs) in days }
        val foods = d.foods.filter { LocalDay.epochDay(it.atMs) == c.today }
        val sleeps = c.sleeps.filter { it.day in (c.today - 13)..c.today }
            .groupBy { it.day }.mapValues { (_, list) -> list.maxBy { it.minutes } }.values.toList()

        fun sleepCompare() = compare(AppText.text(R.string.copy_d466bcf52eb6, "Sleep"), c.sleeps.groupBy { it.day }.mapValues { (_, list) -> list.maxOf { it.minutes }.toDouble() }, c.today, "min")
        fun recoveryCompare() = compare(AppText.text(R.string.copy_48f6a8d5688b, "Recovery"), d.health.mapNotNull { (day, h) -> h.recovery?.let { day to it.toDouble() } }.toMap(), c.today, "/100")
        if (intent == HistoryIntent.HABIT) {
            val habit = d.weeklyHabit ?: return AppText.text(R.string.copy_e2a1ef566b4c, "Choose one small habit. Say ‘Set my weekly habit: Walk for 10 minutes’. Then use ‘Mark my habit done’ after you do it.")
            val start = habit.startDay + ((c.today - habit.startDay).coerceAtLeast(0) / 7) * 7
            val completed = habit.completedDays.count { it in start..minOf(c.today, start + 6) }
            return AppText.text(R.string.copy_0241ea8fe6e8, "Your habit: %1\$s. %2\$s %3\$s marked done in this 7-day period (%4\$s to %5\$s). ", habit.name, completed, if (completed == 1) "day" else "days", LocalDate.ofEpochDay(start), LocalDate.ofEpochDay(start + 6)) +
                AppText.text(R.string.copy_3bbdc2629959, "Say ‘Mark my habit done’, ‘Undo today's habit’ or ‘Clear my habit’.")
        }
        if (intent == HistoryIntent.TRENDS) return sleepCompare() + "\n\n" + recoveryCompare()
        return when (intent) {
            HistoryIntent.STEP_COMPARE -> compare(AppText.text(R.string.copy_1de3df70ddf4, "Steps"), d.bandSteps.mapValues { it.value.toDouble() }, c.today, "steps")
            HistoryIntent.STEP_GOALS -> d.bandSteps.filterKeys { it in days }.let { AppText.text(R.string.copy_6bf35030a54c, "%1\$s of %2\$s recorded days reached your current %3\$s-step goal in the last 7 days. This uses today's goal for the comparison.", it.values.count { n -> n >= d.goal }, it.size, d.goal) }
            HistoryIntent.STEP_BEST -> d.bandSteps.filterKeys { it in days }.maxByOrNull { it.value }?.let { AppText.text(R.string.copy_ff4bd6147fbb, "%1\$s had %2\$s steps, the highest recorded count in your last 7 days.", LocalDate.ofEpochDay(it.key), it.value) } ?: AppText.text(R.string.copy_139e59b322e0, "No band steps have been recorded in the last 7 days.")
            HistoryIntent.STEP_AVERAGE -> d.bandSteps.filterKeys { it in days }.values.let { if (it.isEmpty()) AppText.text(R.string.copy_139e59b322e0, "No band steps have been recorded in the last 7 days.") else AppText.text(R.string.copy_fdf3deab206e, "Your average is %1\$s steps across %2\$s recorded days in the last 7 days. Missing days are excluded and today may be incomplete.", it.average().roundToInt(), it.size) }
            HistoryIntent.ACTIVITY_LONGEST -> activities.maxByOrNull { it.minutes }?.let { AppText.text(R.string.copy_78cdc711c08e, "Your longest logged activity this week was %1\$s, %2\$s minutes.", it.name, it.minutes) } ?: AppText.text(R.string.copy_3964555ea85c, "No activities logged in the last 7 days.")
            HistoryIntent.ACTIVITY_LIST -> if (activities.isEmpty()) AppText.text(R.string.copy_3964555ea85c, "No activities logged in the last 7 days.") else activities.groupBy { it.name }.entries.joinToString("; ") { AppText.text(R.string.copy_281c8f436071, "%1\$s: %2\$s min", it.key, it.value.sumOf { a -> a.minutes }) } + AppText.text(R.string.copy_2089d8ea9f20, ". These are logged activities.")
            HistoryIntent.ACTIVITY_COMPARE -> compare(AppText.text(R.string.copy_b335ff34787a, "Logged activity"), d.activities.groupBy { LocalDay.epochDay(it.atMs) }.mapValues { (_, a) -> a.sumOf { it.minutes }.toDouble() }, c.today, AppText.text(R.string.copy_72d489f00131, "min per logged day"))
            HistoryIntent.ACTIVITY_COUNT -> AppText.text(R.string.copy_2bd135c46b13, "%1\$s activities and %2\$s minutes logged in the last 7 days. Unlogged activity isn't included.", activities.size, activities.sumOf { it.minutes })
            HistoryIntent.MEAL_LIST -> if (!d.nutritionEnabled) AppText.text(R.string.copy_91088916caa6, "Turn on Food journal in More to record meals.") else if (foods.isEmpty()) AppText.text(R.string.copy_0c70f3348bb9, "No meals logged today.") else AppText.text(R.string.copy_abcfd00754fc, "Today you logged: ") + foods.joinToString { it.name } + "."
            HistoryIntent.MEAL_MISSING -> if (!d.nutritionEnabled) AppText.text(R.string.copy_4a2a325d4375, "Food journal is off.") else foods.filter { it.calories == null || it.proteinG == null }.let { if (it.isEmpty()) AppText.text(R.string.copy_aa7d925043a5, "No incomplete nutrition entries in today's log.") else AppText.text(R.string.copy_55a4d944a4f5, "Missing calories or protein: %1\$s. Add values from labels if you want totals.", it.joinToString { f -> f.name }) }
            HistoryIntent.CALORIES, HistoryIntent.PROTEIN -> {
                if (!d.nutritionEnabled) AppText.text(R.string.copy_7f3eb18d657b, "Food journal is off. Enable it in More when you want to log meals.")
                else {
                    val values = foods.mapNotNull { if (intent == HistoryIntent.CALORIES) it.calories else it.proteinG }
                    if (values.isEmpty()) AppText.text(R.string.copy_9848f727b416, "No %1\$s recorded today.", if (intent == HistoryIntent.CALORIES) "calories" else "protein")
                    else AppText.text(R.string.copy_8024e65a6f36, "%1\$s %2\$s recorded across %3\$s/%4\$s entries. Missing values are excluded; this may not be your full daily intake.", values.sum(), if (intent == HistoryIntent.CALORIES) "kcal" else "g protein", values.size, foods.size)
                }
            }
            HistoryIntent.SLEEP_CONSISTENCY, HistoryIntent.WAKE_CONSISTENCY -> {
                val variation = timingVariation(sleeps.map { if (intent == HistoryIntent.SLEEP_CONSISTENCY) it.bedMinute else it.wakeMinute })
                if (variation == null) AppText.text(R.string.copy_39a33446bf75, "I need at least 3 recorded main sleep sessions in the last 14 days to compare timing.")
                else AppText.text(R.string.copy_b54f07fb92c5, "Your %1\$s time varied by about %2\$s minutes on average around a typical recorded time, across %3\$s main sleep sessions. Daytime sleep counts too. If your shifts rotate, compare sessions from the same shift; this isn't a sleep-quality grade.", if (intent == HistoryIntent.SLEEP_CONSISTENCY) "sleep start" else "wake", variation, sleeps.size)
            }
            HistoryIntent.SLEEP_COMPARE -> sleepCompare()
            HistoryIntent.SLEEP_COUNT -> AppText.text(R.string.copy_d6d1aa6ad143, "There are main sleep records on %1\$s of the last 14 days. A missing record does not mean you didn't sleep.", sleeps.size)
            HistoryIntent.SLEEP_INTERRUPTED -> sleeps.maxByOrNull { it.day }?.let { AppText.text(R.string.copy_4ccec4e4728b, "Your main sleep ending %1\$s had %2\$s detected interruptions. Wrist motion can affect detection; this isn't a diagnosis.", LocalDate.ofEpochDay(it.day), it.interruptions) } ?: AppText.text(R.string.copy_65731dc72555, "No recent sleep session to review.")
            HistoryIntent.SLEEP_PLAN -> AppText.text(R.string.copy_356a3a3d1d32, "Your planned window is %1\$s minutes. ", (d.wakeMinutes - d.bedtimeMinutes + 1440) % 1440) + (sleeps.maxByOrNull { it.day }?.let { AppText.text(R.string.copy_43081156a092, "Your main sleep ending %1\$s recorded %2\$s minutes asleep. A planned window includes time awake; the two aren't the same measure.", LocalDate.ofEpochDay(it.day), it.minutes) } ?: AppText.text(R.string.copy_a22cbeae38ff, "No recent sleep session is available for comparison."))
            HistoryIntent.RECOVERY_COMPARE -> recoveryCompare()
            HistoryIntent.STRAIN_COUNT -> AppText.text(R.string.copy_f405a257f221, "%1\$s of the last 14 days have Strain records. Missing days are not zero-load days.", d.health.count { it.key in (c.today - 13)..c.today && it.value.strain != null })
            HistoryIntent.STRAIN_COMPARE -> compare(AppText.text(R.string.copy_fcde25fd44fb, "Strain"), d.health.mapNotNull { (day, h) -> h.strain?.let { day to it.toDouble() } }.toMap(), c.today, "/21")
            HistoryIntent.STEP_SYNC -> d.bandStepAnchor?.let { AppText.text(R.string.copy_0e2118acf114, "Band steps were last received at %1\$s. Open Activity for the daily count. An overnight disconnected interval may be incomplete.", java.time.Instant.ofEpochMilli(it.receivedMs).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()) } ?: AppText.text(R.string.copy_b66d6528a1b9, "No band step packet received yet. Connect your band after installing the step tracking firmware.")
            HistoryIntent.SENSORS -> AppText.text(R.string.copy_eadff0f2af61, "Your band combines optical pulse and oxygen estimates, wrist movement, skin temperature, ambient temperature/humidity and a gas-based air-quality category. Pulse variability, activity and sleep/wake patterns can be derived from these signals. Insights also offers experimental pulse-based sleep stages and a profile/activity-based VO₂ max estimate. Those estimates are not direct band measurements or validated results for this device.")
            HistoryIntent.AUTONOMIC_LOAD -> AppText.text(R.string.copy_486314158258, "Autonomic Load compares pulse variability and heart rate during still periods with your own usual range. A higher result can have several causes, including recent exertion, poor sleep or heat. It is an experimental wellness indicator, not a diagnosis or a measurement of emotional stress. See Insights for the current result.")
            HistoryIntent.SIGNAL_QUALITY -> AppText.text(R.string.copy_3241bc0d3684, "Wear the band snugly without discomfort, keep the optical window clean, and sit still for a resting reading. Check fit if readings drop out. Movement, poor contact and cold skin can reduce signal quality; missing readings shouldn't be treated as normal values.")
            HistoryIntent.VO2 -> c.fitness?.let {
                AppText.text(R.string.copy_150ca3ff8998, "Your saved VO₂ max estimate from %1\$s is %2\$s mL/kg/min. It uses your age, height, weight, activity answer and selected reference. It isn't measured oxygen consumption. Open Insights → Cardio fitness to update it; small changes needn't mean your fitness changed.", LocalDate.ofEpochDay(LocalDay.epochDay(it.atMs)), fmt(it.vo2))
            } ?: AppText.text(R.string.copy_c67b91486997, "Open Insights → Cardio fitness to make an estimate from your profile and usual activity. A band oxygen-saturation reading alone cannot measure VO₂ max.")
            HistoryIntent.FITNESS_AGE -> c.fitness?.let {
                AppText.text(R.string.copy_aca29d8cda84, "Your saved estimated fitness age is %1\$s, compared with age-group fitness averages. This is a rough fitness comparison, not your biological age or a prediction of lifespan. It was saved on %2\$s.", it.ageBand, LocalDate.ofEpochDay(LocalDay.epochDay(it.atMs)))
            } ?: AppText.text(R.string.copy_e12bc27df9d3, "BIPSS offers an estimated fitness-age comparison in Insights → Cardio fitness. It does not measure biological age. Add your usual activity to make an estimate.")
            HistoryIntent.SLEEP_STAGES -> c.stages?.let { night ->
                AppText.text(R.string.copy_54da5ee8b888, "For the recording starting %1\$s, estimated stages were ", LocalDate.ofEpochDay(LocalDay.epochDay(night.startMs))) +
                    listOf(AppText.text(R.string.copy_33a7e908344d, "Wake"), AppText.text(R.string.copy_dbcd5e7bb7a0, "Light"), AppText.text(R.string.copy_c54e3625467b, "Deep"), "REM").mapIndexed { stage, label -> AppText.text(R.string.copy_8bb95d31228c, "%1\$s %2\$s min", label, fmt(night.stages.count { it == stage } / 2.0)) }.joinToString(", ") +
                    AppText.text(R.string.copy_92803149526f, ". %1\$s min remain Unknown. These experimental pulse-based estimates haven't been validated on your band. View the timeline in Insights → Sleep stages.", fmt(night.stages.count { it == -1 } / 2.0))
            } ?: AppText.text(R.string.copy_4055e67843c2, "Enable recording in Insights → Sleep stages, then wear your connected band overnight. Pulse-based Wake, Light, Deep and REM estimates are experimental; uncertain sections stay Unknown.")
            else -> null
        }
    }
}
