package com.bipss.app.ai

import com.bipss.app.localization.AppText
import com.bipss.app.R

import com.bipss.app.domain.DailyWindows

/** Deterministic answers grounded in the caller's current local records. */
object DailyCoach {
    data class Context(val steps: Int?, val stepGoal: Int, val waterMl: Int, val waterGoal: Int?,
        val yesterdayWaterMl: Int, val sleepMinutes: Int?, val activityMinutes: Int,
        val bmi: Float?, val bedtimeMinutes: Int, val wakeMinutes: Int, val nowMinute: Int,
        val aqi: Int?, val uv: Float?, val recovery: Int?)
    data class Reply(val text: String, val topic: String)
    fun respond(question: String, c: Context, previousTopic: String? = null): Reply? {
        val q = com.bipss.app.localization.CoachLanguage.canonicalize(question).trim().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        fun has(vararg terms: String) = terms.any { Regex("\\b" + Regex.escape(it) + "\\b").containsMatchIn(q) }
        val topic = when {
            q in listOf("tell me more", "what next", "why", "more", "how") -> previousTopic
            has("yesterday") && has("water", "drank", "drink", "hydration") -> "water_compare"
            has("steps", "step count", "step goal", "walking goal") -> "steps"
            has("water goal", "drink goal", "hydration goal", "water left", "how much more water", "drunk enough", "drank enough", "how much water", "water target") -> "water_goal"
            has("sleep last night", "how did i sleep", "how long did i sleep", "last night s sleep", "slept last night", "hours did i sleep") -> "sleep"
            has("bedtime", "sleep routine", "sleep schedule", "wake time", "go to bed", "wind down") -> "routine"
            has("coffee", "caffeine", "tea now") -> "caffeine"
            has("screens", "screen time", "phone before bed") -> "screens"
            has("sun", "uv", "sunlight", "vitamin d") -> "sun"
            has("breathing exercise", "breathing break", "help me relax", "calm down", "need to relax", "breathing timer") -> "breathing"
            has("bmi", "body mass index") -> "bmi"
            has("exercise today", "workout today", "run outside", "walk outside", "outdoor exercise", "go for a walk", "go for a run", "good time to exercise") -> "move"
            has("activity log", "active minutes", "workout minutes", "exercise this week") -> "activity"
            has("meal", "food", "nutrition", "protein", "calorie") -> "food"
            has("day summary", "summarize my day", "summarise my day", "plan my day", "what should i do today") -> "day"
            has("what can you do", "help", "ideas") -> "help"
            q in listOf("hi", "hello", "hey", "good morning", "good evening") -> "hello"
            q in listOf("thanks", "thank you", "thankyou") -> "thanks"
            else -> null
        } ?: return null
        fun clock(m: Int) = "%02d:%02d".format(m / 60, m % 60)
        val windows = DailyWindows.build(c.nowMinute, c.bedtimeMinutes, c.wakeMinutes, c.uv, c.aqi, c.recovery, c.waterMl)
        fun window(id: String): String = windows.first { it.id == id }.let { "${it.state}. ${it.detail}" }
        val answer = when(topic) {
            "steps" -> c.steps?.let { AppText.text(R.string.copy_6b145ffa2100, "%1\$s steps recorded today against your %2\$s-step goal. ", it, c.stepGoal) +
                if (it >= c.stepGoal) AppText.text(R.string.copy_9678154178af, "You've reached your goal. Take any extra movement at a comfortable pace.")
                else AppText.text(R.string.copy_bcff0318cd13, "%1\$s steps to go. A short walk is one way to add a little movement if you feel well.", c.stepGoal - it) }
                ?: AppText.text(R.string.copy_4b6a1cfe8ca1, "No band steps have been recorded today. Connect your band with step tracking firmware, then check More → Activity & steps.")
            "water_goal" -> c.waterGoal?.let { AppText.text(R.string.copy_3a25a4938b81, "You've logged %1\$s mL toward your %2\$s mL goal. ", c.waterMl, it) +
                if (c.waterMl >= it) AppText.text(R.string.copy_47e6f61dd766, "Your logging goal is met; there is no need to force extra water.")
                else AppText.text(R.string.copy_7750d2c32c13, "%1\$s mL remains in your log. Spread drinks through the day and follow any prescribed fluid limit.", it - c.waterMl) }
                ?: AppText.text(R.string.copy_276f4aea2a8b, "You've logged %1\$s mL today. Choose a daily goal in Hydration; your needs depend on your health, activity, weather and food.", c.waterMl)
            "water_compare" -> AppText.text(R.string.copy_eed9013b2332, "You logged %1\$s mL yesterday and %2\$s mL today so far. These are logged drinks, not a measurement of hydration.", c.yesterdayWaterMl, c.waterMl)
            "sleep" -> c.sleepMinutes?.let { AppText.text(R.string.copy_1a1aea279812, "You recorded %1\$sh %2\$sm of sleep. Open your Snapshot to see timing and interruptions. How rested do you feel?", it / 60, it % 60) }
                ?: AppText.text(R.string.copy_356aabaf5c26, "There isn't a sleep record for last night. Wear your band overnight, and keep your sleep and wake times as regular as you can.")
            "routine" -> AppText.text(R.string.copy_8b2f72bdc958, "Your planned bedtime is %1\$s and wake time is %2\$s. ", clock(c.bedtimeMinutes), clock(c.wakeMinutes)) +
                AppText.text(R.string.copy_184f19c4a600, "Start winding down about an hour before bed. Change these in More → Sleep routine to fit your schedule.")
            "caffeine" -> window("caffeine")
            "screens" -> window("screens")
            "sun" -> window("sun")
            "move" -> window("move") + if (c.aqi == null) AppText.text(R.string.copy_edc732d665b1, " I don't have current outdoor air-quality data, so check local conditions before going out.") else AppText.text(R.string.copy_52d341923b97, " Current AQI: %1\$s.", c.aqi)
            "breathing" -> AppText.text(R.string.copy_c65efa5de99b, "Sit comfortably. Breathe in gently for about 4 seconds, then out for about 6, without holding your breath. Repeat at a comfortable pace for one minute. Stop if uncomfortable or dizzy. Open More → Breathing break for a timer.")
            "bmi" -> c.bmi?.let { AppText.text(R.string.copy_7363e618cee1, "Your BMI is %.1f, calculated from your saved height and weight. It doesn't measure body fat or overall fitness. Update measurements in More → Body measurements.").format(it) }
                ?: AppText.text(R.string.copy_47cce07c1ad0, "Add your height, weight and age in your profile to calculate adult BMI. This view is for ages 20 and over.")
            "activity" -> AppText.text(R.string.copy_0e871911d083, "You've logged %1\$s activity minutes in the last 7 days. Open Activity to review or add a workout. Try ‘Log a 20 minute walk’ to add one here.", c.activityMinutes)
            "food" -> AppText.text(R.string.copy_2e0f6a151f56, "The optional Food journal lets you record meals and add calories or protein from labels. I can't infer a meal's nutrients from its name. Enable it under More when you want to use it.")
            "day" -> buildString {
                append(AppText.text(R.string.copy_f3fbf0a775fa, "Today: %1\$s, %2\$s mL of drinks logged", c.steps?.let { "$it steps" } ?: "steps not recorded", c.waterMl))
                c.sleepMinutes?.let { append(AppText.text(R.string.copy_6f2a22b8b5c2, ", %1\$sh %2\$sm sleep", it / 60, it % 60)) }
                append(". ")
                append(window("move"))
                append(AppText.text(R.string.copy_8ea6faf6f758, " Keep a little time for a break and your usual bedtime."))
            }
            "help" -> AppText.text(R.string.copy_cc4149353a60, "Ask about steps, your water goal, last night's sleep, a bedtime routine, caffeine, outdoor activity, BMI or a breathing break. You can also ask about sleep consistency, weekly trends and getting better band readings. Ask ‘My cycle notes’ or ‘My weekly habit’. You can also say ‘Log 250 mL water’ or ‘Log a 20 minute walk’.")
            "hello" -> AppText.text(R.string.copy_dafbabf2e187, "Hello! Would you like to plan your day, review a reading, or take a short breathing break?")
            "thanks" -> AppText.text(R.string.copy_0c31432f25b6, "You're welcome. What would be useful next: your daily plan, sleep routine, or water goal?")
            else -> return null
        }
        return Reply(answer, topic)
    }

    fun waterCommand(text: String): Int? {
        val match = Regex("^(?:log|add)\\s+(\\d{2,4})\\s*(?:ml|millilitres|milliliters)(?:\\s+(?:of\\s+)?water)?[.!]?$", RegexOption.IGNORE_CASE).matchEntire(com.bipss.app.localization.CoachLanguage.canonicalize(text).trim()) ?: return null
        return match.groupValues[1].toIntOrNull()?.takeIf { it in 50..2000 }
    }
    fun activityCommand(text: String): Pair<String, Int>? {
        val match = Regex("^log\\s+(?:a\\s+)?(\\d{1,3})[ -]*(?:minute|min)(?:s)?\\s+(walk|run|cycle|workout|yoga)[.!]?$", RegexOption.IGNORE_CASE).matchEntire(com.bipss.app.localization.CoachLanguage.canonicalize(text).trim()) ?: return null
        val minutes = match.groupValues[1].toIntOrNull()?.takeIf { it in 1..600 } ?: return null
        return match.groupValues[2].lowercase().replaceFirstChar { it.uppercase() } to minutes
    }
}
