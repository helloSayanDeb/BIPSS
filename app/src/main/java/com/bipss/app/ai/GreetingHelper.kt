package com.bipss.app.ai

import com.bipss.app.localization.AppText
import com.bipss.app.R

import java.util.Calendar

object GreetingHelper {
    /**
     * Returns a time-appropriate greeting (e.g., "Good morning", "Good afternoon", "Good evening").
     * If a name is provided, it extracts the first name and appends it (e.g., "Good morning, Sayan").
     */
    fun getGreeting(fullName: String? = null): String {
        val c = Calendar.getInstance()
        val hour = c.get(Calendar.HOUR_OF_DAY)

        val timeGreeting = when (hour) {
            in 4..11  -> AppText.text(R.string.copy_90a90a48e23d, "Good morning")
            in 12..16 -> AppText.text(R.string.copy_d325e1bbfd1c, "Good afternoon")
            in 17..21 -> AppText.text(R.string.copy_15a421e4cf76, "Good evening")
            else      -> AppText.text(R.string.copy_625d31f5ade6, "Good night")   // 22–03
        }

        if (fullName.isNullOrBlank()) {
            return timeGreeting
        }

        val firstName = fullName.trim().split("\\s+".toRegex()).firstOrNull()
        return if (!firstName.isNullOrBlank()) {
            "$timeGreeting, $firstName"
        } else {
            timeGreeting
        }
    }
}
