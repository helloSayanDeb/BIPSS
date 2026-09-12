package com.bipss.app.domain

/**
 * What data the app actually has right now.
 *
 * This exists because the app previously substituted invented vitals
 * (HR 72, SpO2 98, skin temp 36.5) whenever the band was absent, then
 * displayed confident risk scores derived from them. Every score now
 * carries the mode it was computed under, so the UI can never present a
 * fabricated number as a measurement.
 */
enum class DataMode(val label: String) {
    /** Band streaming and network up. */
    FULL("Live"),

    /** No band. Environment sensors/APIs only. */
    ENVIRONMENT_ONLY("Environment only"),

    /** Band streaming, network down. */
    BAND_ONLY("Offline - band only"),

    /** Nothing live; last known values. */
    CACHED("Offline - cached")
}

/**
 * Whether a given score could be computed at all, and how completely.
 *
 * The distinction between [NEEDS_BAND] and a score of 0 is the whole point:
 * "0 - Normal" reads as "your heart is fine", when it actually meant
 * "we have no idea". Those are different statements and only one is true.
 */
enum class ScoreAvailability {
    /** Computed from real data, all inputs present. */
    AVAILABLE,

    /** Computed, but some inputs missing - normalised against what was measurable. */
    PARTIAL,

    /** Cannot be computed without body vitals from the band. */
    NEEDS_BAND,

    /** Needs a personal baseline that does not exist yet. */
    NEEDS_MORE_HISTORY;

    /** True when a numeric score may be shown. Otherwise the UI must render an em dash. */
    val hasNumber: Boolean get() = this == AVAILABLE || this == PARTIAL
}
