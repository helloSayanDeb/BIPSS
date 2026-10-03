package com.bipss.app.domain

import com.bipss.app.localization.AppText
import com.bipss.app.R

data class DailyWindow(val id: String, val title: String, val state: String, val detail: String, val tone: Int = 0)

/** Lifestyle suggestions from chosen sleep times and available readings, not inferred body clocks. */
object DailyWindows {
    fun build(nowMinute: Int, bedtime: Int, wake: Int, uv: Float?, aqi: Int?, recovery: Int?, waterMl: Int): List<DailyWindow> {
        val toBed = (bedtime - nowMinute + 1440) % 1440
        val sinceWake = (nowMinute - wake + 1440) % 1440
        val awakeLength = (bedtime - wake + 1440) % 1440
        val restTime = sinceWake >= awakeLength
        return listOf(
            DailyWindow("caffeine", AppText.text(R.string.copy_a9a15db4d1c3, "Caffeine"), if (toBed <= 360 || restTime) AppText.text(R.string.copy_b58eb52c8810, "Skip for now") else AppText.text(R.string.copy_e40854fc4bf2, "Earlier is better"),
                AppText.text(R.string.copy_6780c66e7627, "Caffeine can disturb sleep. Aim to avoid it in the six hours before your chosen bedtime; caffeine-free drinks are an option."), if (toBed <= 360 || restTime) -1 else 0),
            DailyWindow("move", AppText.text(R.string.copy_49ba63e89af6, "Movement"), when { restTime -> AppText.text(R.string.copy_5861da645e40, "Rest time"); aqi != null && aqi > 200 -> AppText.text(R.string.copy_31158aabd2a0, "Choose indoors"); recovery != null && recovery < 40 -> AppText.text(R.string.copy_4b3aff144325, "Keep it gentle"); else -> AppText.text(R.string.copy_4b619fdeb691, "Make some time") },
                if (aqi != null && aqi > 200) AppText.text(R.string.copy_385a41f03446, "Outdoor air quality is poor. Consider a comfortable indoor activity and reduce outdoor exertion.")
                else AppText.text(R.string.copy_4135c2903f24, "Choose movement that suits how you feel. A low recovery day is a reason to ease back, not a target to push through."), if (restTime) 0 else 1),
            DailyWindow("screens", AppText.text(R.string.copy_3fc8f5eeb926, "Screen break"), if (toBed <= 60 || restTime) AppText.text(R.string.copy_b5f78ced41bf, "Wind down") else AppText.text(R.string.copy_bb814dee7c5e, "Take breaks"),
                AppText.text(R.string.copy_ac8949a69ecb, "Put screens aside during the hour before your planned bedtime. During the day, regular breaks can give your eyes and attention a rest."), if (toBed <= 60) -1 else 0),
            DailyWindow("sun", AppText.text(R.string.copy_30dfa1dd5d12, "Daylight"), if (uv == null) AppText.text(R.string.copy_e1b4f23bb1a8, "Check outside") else if (uv >= 3) AppText.text(R.string.copy_502486c36143, "Use protection") else AppText.text(R.string.copy_9622c5f558e5, "Lower UV now"),
                AppText.text(R.string.copy_236a5d82f73b, "When UV is 3 or above, use shade, clothing and sun protection. This is not a vitamin D measurement or a recommendation to sunbathe."), if (uv != null && uv >= 3) -1 else 0),
            DailyWindow("water", AppText.text(R.string.copy_1d9605ff4a17, "Hydration"), if (waterMl == 0) AppText.text(R.string.copy_b8cbbf50ae2a, "Log a drink") else AppText.text(R.string.copy_d1ae64c36056, "%1\$s mL logged", waterMl),
                AppText.text(R.string.copy_ad30f9137770, "Drink regularly through the day and log what you have. Your log can't tell whether you're dehydrated. Follow any fluid limit you've been given."), 1),
            DailyWindow("recovery", AppText.text(R.string.copy_48f6a8d5688b, "Recovery"), if (recovery == null) AppText.text(R.string.copy_6128a051ef41, "Listen to your body") else if (recovery < 40) AppText.text(R.string.copy_007d6d01bbd6, "A gentler day") else AppText.text(R.string.copy_ffabd2d08ffa, "Find your balance"),
                AppText.text(R.string.copy_fb268ebf8114, "Balance activity with breaks, food and sleep. Your recovery score adds context, but how you feel matters too."), 0),
            DailyWindow("sleep", AppText.text(R.string.copy_45f1768a5fe9, "Sleep preparation"), if (toBed <= 60 || restTime) AppText.text(R.string.copy_417c2be23508, "Quiet time") else AppText.text(R.string.copy_fdcc1bc9cbd8, "Plan tonight"),
                AppText.text(R.string.copy_8a78a63ad2f4, "Keep a regular sleep schedule and leave time to unwind. These windows follow the bedtime and wake time you choose."), if (toBed <= 60) 1 else 0)
        )
    }
}
