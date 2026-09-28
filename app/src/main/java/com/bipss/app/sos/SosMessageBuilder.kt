package com.bipss.app.sos

import java.util.Locale

/** Where the SOS says the user is, and how much that position can be trusted. */
data class SosLocation(
    val lat: Double,
    val lng: Double,
    /** True when this is an earlier fix reused because no fresh one arrived in time. */
    val isLastKnown: Boolean = false,
    /** True when only approximate (coarse) location permission is granted. */
    val isApproximate: Boolean = false,
    /** For a last-known fix: how old it was when the SOS was triggered. */
    val ageMinutes: Long? = null
)

/**
 * Builds SOS message bodies. Pure (no Android), so the exact wording is pinned
 * by tests - in an emergency message every clause is a claim someone may act on.
 */
object SosMessageBuilder {

    fun mapsLink(loc: SosLocation): String =
        "https://maps.google.com/?q=${"%.6f".format(Locale.ROOT, loc.lat)},${"%.6f".format(Locale.ROOT, loc.lng)}"

    /** "Location: <link>" with the caveats that apply, or an explicit "unavailable". */
    fun locationClause(loc: SosLocation?): String = when {
        loc == null -> "Location unavailable."
        else -> buildString {
            append(if (loc.isLastKnown) "Last known location" else "Location")
            // A reused fix says how old it is: someone acting on a map link
            // needs to know whether it shows where the user is or was.
            val notes = listOfNotNull(
                "approximate".takeIf { loc.isApproximate },
                loc.ageMinutes?.takeIf { loc.isLastKnown }?.let { "about ${ageLabel(it)} old" }
            )
            if (notes.isNotEmpty()) append(" (").append(notes.joinToString(", ")).append(")")
            append(": ").append(mapsLink(loc)).append(".")
        }
    }

    fun ageLabel(minutes: Long): String = when {
        minutes < 60      -> "${minutes.coerceAtLeast(1)} min"
        minutes < 48 * 60 -> "${(minutes + 30) / 60} h"
        else              -> "${minutes / 1440} days"
    }

    /** The message sent to the user's own emergency contacts. */
    fun contactMessage(userName: String, timestamp: String, location: SosLocation?): String =
        "EMERGENCY: $userName triggered an SOS alert at $timestamp. " +
            locationClause(location) + " " +
            "They may need help. Automated alert from the BIPSS app."
}
