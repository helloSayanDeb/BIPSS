package com.bipss.app.ai

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
            in 4..11  -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..21 -> "Good evening"
            else      -> "Good night"   // 22–03
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
