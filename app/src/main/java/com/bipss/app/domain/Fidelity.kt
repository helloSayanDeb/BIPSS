package com.bipss.app.domain

/**
 * How much weight a derived number can bear. Every metric that is not a raw
 * sensor reading carries one, shown as a badge next to the value, so a
 * standard, quality-checked computation and a rough estimate never look alike.
 *
 * Raw readings (skin and ambient temperature, humidity, band battery) carry
 * none: they are what the sensor reported.
 */
enum class Fidelity(val label: String, val explanation: String) {
    /** A standard method applied to this band's signal, with its limits reported (signal quality, coverage). */
    PRODUCTION(
        "Production",
        "Computed with an established method from your band's signal. Readings from poor-quality signal are not recorded."
    ),

    /** Indirect, uncalibrated or population-derived: useful as a guide, not as a measurement. */
    ESTIMATE(
        "Estimate",
        "Derived indirectly or without calibration for this band. Use it as a guide, not as a measurement."
    ),

    /** An early method still being evaluated; may change or be withdrawn. */
    EXPERIMENTAL(
        "Experimental",
        "An early method still being evaluated. It may change, and it is never used for alerts."
    ),

    /** Shown only as a placeholder for something not built yet. */
    ROADMAP(
        "Roadmap",
        "Not available yet."
    )
}
