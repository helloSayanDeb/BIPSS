package com.bipss.app.ai

/** Internal offline intent bank. Never displayed as a menu or used to authorize writes. */
internal enum class HistoryIntent {
    STEP_COMPARE, STEP_GOALS, STEP_BEST, STEP_AVERAGE,
    ACTIVITY_LONGEST, ACTIVITY_LIST, ACTIVITY_COMPARE, ACTIVITY_COUNT,
    MEAL_LIST, MEAL_MISSING, CALORIES, PROTEIN,
    SLEEP_CONSISTENCY, WAKE_CONSISTENCY, SLEEP_COMPARE, SLEEP_COUNT,
    SLEEP_INTERRUPTED, SLEEP_PLAN, RECOVERY_COMPARE, STRAIN_COUNT,
    STRAIN_COMPARE, STEP_SYNC, SENSORS, AUTONOMIC_LOAD, SIGNAL_QUALITY,
    HABIT, TRENDS, CYCLE_NOTES, VO2, FITNESS_AGE, SLEEP_STAGES;

    companion object {
        fun match(text: String): HistoryIntent? {
            val q = HistoryCoach.normalize(com.bipss.app.localization.CoachLanguage.canonicalize(text).replace("VO₂", "vo2", ignoreCase = true))
            fun has(vararg phrases: String) = phrases.any {
                Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(q)
            }
            if (has("don t", "do not", "never show", "hide my", "without my")) return null
            if (has("fitness age", "biological age", "bioage", "bio age", "pace of aging", "body age")) return FITNESS_AGE
            if (has("vo2", "vo2max", "vo 2", "cardio fitness", "cardiorespiratory fitness")) return VO2
            if (has("sleep stages", "sleep stage", "rem sleep", "deep sleep", "light sleep")) return SLEEP_STAGES
            // Sensitive notes need a direct personal request, not a general cycle question.
            if (has("cycle", "period", "menstrual") && has("my") &&
                has("notes", "records", "review", "summarize", "summarise", "summary") && !has("not", "no", "without", "hide")) return CYCLE_NOTES
            if (has("habit", "habits") && has("my", "weekly", "progress")) return HABIT
            if (has("my trends", "my multiweek trends", "compare my weeks", "weekly overview", "weekly trends")) return TRENDS
            if (has("autonomic load", "ali")) return AUTONOMIC_LOAD
            if (has("band", "sensor", "sensors", "readings", "signal") &&
                has("improve", "better", "poor", "inaccurate", "inconsistent", "missing readings", "drop out", "dropping out", "fit", "wear")) return SIGNAL_QUALITY
            if (has("band", "sensor", "sensors") && has("measure", "measures", "detect", "sensors", "capable", "track")) return SENSORS
            val steps = has("steps", "step count", "step goal", "step target", "walking goal")
            val sleep = has("sleep", "sleeping", "bedtime", "bed time")
            val activity = has("activity", "activities", "workout", "workouts", "exercise", "exercise minutes")
            val comparison = has("compare", "comparison", "versus", "vs", "two weeks", "last week", "previous week", "trend", "trends", "improving", "improved", "changed")
            if (steps && has("last send", "last sent", "last sync", "last synced", "last update", "last updated", "last received", "fresh", "up to date")) return STEP_SYNC
            if (steps && !has("today") && has("goal", "target") && has("how often", "how many days", "reach", "reached", "hit", "met")) return STEP_GOALS
            if (steps && has("most", "highest", "best", "record")) return STEP_BEST
            if (steps && has("typical", "average", "usual", "normally")) return STEP_AVERAGE
            if (steps && comparison) return STEP_COMPARE
            if (activity && has("longest", "lengthiest")) return ACTIVITY_LONGEST
            if (activity && comparison) return ACTIVITY_COMPARE
            if (activity && has("how many", "number", "count")) return ACTIVITY_COUNT
            if (activity && has("logged", "log", "this week", "list", "done", "did i do")) return ACTIVITY_LIST
            if (has("meal", "meals", "food", "nutrition", "entries") && has("missing", "incomplete", "without")) return MEAL_MISSING
            if (has("calories", "calorie", "kcal") && !has("should", "need", "recommended") && has("logged", "log", "recorded", "today", "total", "how many")) return CALORIES
            if (has("protein") && !has("should", "need", "recommended") && has("logged", "log", "recorded", "today", "total", "how much")) return PROTEIN
            if (has("meal", "meals", "food", "eaten", "eat") && !has("should", "need", "recommended") && has("today", "logged", "log", "recorded")) return MEAL_LIST
            val consistency = has("consistent", "consistency", "regular", "irregular", "vary", "varied", "variation", "same time")
            if (has("wake", "waking", "wake up") && consistency) return WAKE_CONSISTENCY
            if (sleep && consistency) return SLEEP_CONSISTENCY
            if (sleep && has("planned", "plan", "window", "target") && has("match", "recorded", "actual", "versus", "compare")) return SLEEP_PLAN
            if (sleep && has("interruptions", "interrupted", "wake ups", "wakeups", "woke up", "broken")) return SLEEP_INTERRUPTED
            if (sleep && has("how many", "number", "count", "coverage") && has("records", "recorded", "sessions", "nights", "days")) return SLEEP_COUNT
            if (sleep && comparison) return SLEEP_COMPARE
            if (has("recovery", "recovering") && comparison) return RECOVERY_COMPARE
            if (has("strain") && has("how many", "count", "coverage", "records")) return STRAIN_COUNT
            if (has("strain") && comparison) return STRAIN_COMPARE
            return null
        }
    }
}
