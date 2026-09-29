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

    /**
     * What the contacts' message says about hospitals, sorted by what really
     * happened: a text the network confirmed, a text with no verdict yet, or
     * no text at all (no mobile number listed, or the send failed) - in which
     * case the hospital is given for the contacts to call.
     */
    data class HospitalNotice(
        val alerted:      List<Hospital> = emptyList(),
        val beingAlerted: List<Hospital> = emptyList(),
        val toCall:       List<Hospital> = emptyList()
    ) {
        val isEmpty: Boolean get() = alerted.isEmpty() && beingAlerted.isEmpty() && toCall.isEmpty()
    }

    /** The message sent to the user's own emergency contacts. */
    fun contactMessage(
        userName: String,
        timestamp: String,
        location: SosLocation?,
        hospitals: HospitalNotice? = null
    ): String =
        "EMERGENCY: ${gsm(userName)} triggered an SOS alert at $timestamp. " +
            locationClause(location) + " " +
            "They may need help. " +
            hospitalClause(hospitals) +
            "Automated alert from the BIPSS app."

    /**
     * The message sent to nearby hospitals: the same alert, plus the user's
     * emergency contacts, since the user may not be able to answer a call.
     */
    fun hospitalMessage(
        userName: String,
        timestamp: String,
        location: SosLocation?,
        contacts: List<SmsRecipient>
    ): String =
        "EMERGENCY: ${gsm(userName)} triggered an SOS alert at $timestamp. " +
            locationClause(location) + " " +
            "This number is listed for a hospital near them. " +
            (if (contacts.isEmpty()) "" else
                "Their emergency contacts: " + contacts.joinToString(", ") { "${gsm(it.name)} ${it.phoneNumber}" } + ". " +
                    "Please also contact these people if you're able to assist, as the user may not be able to respond by phone. ") +
            "Automated alert from the BIPSS app."

    internal fun hospitalClause(h: HospitalNotice?): String {
        if (h == null || h.isEmpty) return ""
        return buildString {
            // "Were sent" only for a send the network confirmed.
            if (h.alerted.isNotEmpty()) append("Nearby hospitals were also sent this alert: ${list(h.alerted)}. ")
            if (h.beingAlerted.isNotEmpty()) append("Nearby hospitals are also being alerted: ${list(h.beingAlerted)}. ")
            if (h.toCall.isNotEmpty()) {
                append(if (h.alerted.isEmpty() && h.beingAlerted.isEmpty()) "Nearest hospitals: " else "Also near them: ")
                append(list(h.toCall)).append(". ")
            }
            append("If you're not nearby, please call them directly to confirm and coordinate help. ")
        }
    }

    private fun list(hospitals: List<Hospital>): String =
        hospitals.joinToString(", ") { h -> gsm(h.name) + (h.callNumber?.let { " $it" } ?: "") }

    /**
     * Swaps punctuation outside the GSM alphabet (curly quotes, dashes) for
     * ASCII. One such character switches the whole message to UCS-2, which
     * fits 70 characters per SMS part instead of 160. Scripts other than
     * Latin are left alone - a name is not transliterated.
     */
    fun gsm(text: String): String = text
        .replace('\u2018', '\'').replace('\u2019', '\'')
        .replace('\u201C', '"').replace('\u201D', '"')
        .replace('\u2013', '-').replace('\u2014', '-')
        .replace('\u00A0', ' ')

    /** True when [text] needs only the GSM 03.38 alphabet (basic set plus extension table). */
    fun isGsm7(text: String): Boolean = text.all { it in GSM_BASIC || it in GSM_EXTENSION }

    private const val GSM_BASIC =
        "@\u00A3\$\u00A5\u00E8\u00E9\u00F9\u00EC\u00F2\u00C7\n\u00D8\u00F8\r\u00C5\u00E5" +
            "\u0394_\u03A6\u0393\u039B\u03A9\u03A0\u03A8\u03A3\u0398\u039E\u00C6\u00E6\u00DF\u00C9" +
            " !\"#\u00A4%&'()*+,-./0123456789:;<=>?" +
            "\u00A1ABCDEFGHIJKLMNOPQRSTUVWXYZ\u00C4\u00D6\u00D1\u00DC\u00A7" +
            "\u00BFabcdefghijklmnopqrstuvwxyz\u00E4\u00F6\u00F1\u00FC\u00E0"
    private const val GSM_EXTENSION = "^{}\\[~]|\u20AC\u000C"
}
