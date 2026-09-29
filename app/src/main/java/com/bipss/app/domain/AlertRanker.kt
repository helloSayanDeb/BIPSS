package com.bipss.app.domain

import com.bipss.app.domain.environmental.ImdAlert
import com.bipss.app.domain.environmental.RankedAlert
import com.bipss.app.domain.environmental.Relevance
import com.bipss.app.domain.environmental.haversineKm

/**
 * Orders disaster alerts by how much they matter to this particular user.
 *
 * This is the app's central claim and the only genuinely novel part of it: a
 * heat warning does not mean the same thing to everyone. Someone whose Heat
 * Stress already reads 82 is nearer the edge than someone sitting at 20, so
 * the same feed item ranks higher for the first person and carries a line
 * saying why. Severity and proximity are what any alert reader already does;
 * the vulnerability term is what this app adds, and it is therefore the part
 * that has to be least willing to guess.
 *
 * Pure Kotlin, no Android dependencies - fully unit-testable.
 */
object AlertRanker {

    /** Within this distance of the alert centre the alert counts as the user's own area. */
    private const val DIRECT_RADIUS_KM = 60.0

    /**
     * Assumed reach when the feed publishes a centroid but no covered area.
     * Sachet's district-level alerts run roughly this wide. It bounds a piece
     * of geometry, not a measurement - no reading is ever defaulted this way.
     */
    private const val DEFAULT_RADIUS_KM = 150.0

    private const val PROXIMITY_DIRECT   = 1.0f
    private const val PROXIMITY_NEARBY   = 0.6f
    private const val PROXIMITY_REGIONAL = 0.25f

    private const val VULNERABLE_HIGH_FROM     = 70
    private const val VULNERABLE_MODERATE_FROM = 40

    private const val WEIGHT_HIGH     = 1.0f
    private const val WEIGHT_MODERATE = 0.75f
    private const val WEIGHT_LOW      = 0.4f

    /** Applied whenever we do not actually know where the user stands in that domain. */
    private const val WEIGHT_UNKNOWN = 0.5f

    /** Top of the severity ladder, used to normalise severity to 0f..1f. */
    private const val MAX_SEVERITY_RANK = 4f

    /**
     * Which of our scored domains an alert speaks to, or null when none.
     *
     * Matching is substring-based over category and title together because the
     * feed's category field is almost always the single word "Met" - the
     * hazard is only ever named in the title. The first branch wins, so an
     * alert naming two hazards is attributed to the earlier one in this list.
     */
    fun hazardToDomain(category: String, title: String): RiskScoreType? {
        val text = "$category $title".lowercase()
        fun mentions(vararg keys: String): Boolean = keys.any { text.contains(it) }

        return when {
            mentions("heat", "temperature", "heatwave", "warm")   -> RiskScoreType.HEAT_STRESS
            mentions("rain", "flood", "cyclone", "storm",
                     "thunder", "wind", "tide", "surge")          -> RiskScoreType.FLOOD
            mentions("air", "pollution", "smog", "dust", "haze")  -> RiskScoreType.RESPIRATORY
            // A cold wave has no scored domain of its own; it is ranked on
            // severity and distance alone rather than borrowing one.
            else                                                  -> null
        }
    }

    /**
     * Scores and orders every alert given.
     *
     * Nothing is dropped here, REGIONAL entries included. Ranking and
     * filtering are separate concerns: this function says how much each alert
     * matters and the caller decides how many to show and whether a distant
     * one is worth a card. [topRelevant] is the usual answer, but a "see all
     * alerts" screen wants the whole list and should get it.
     */
    fun rank(
        alerts:       List<ImdAlert>,
        riskScores:   List<RiskScore>,
        userDistrict: String?,
        userState:    String?,
        userLat:      Double?,
        userLon:      Double?
    ): List<RankedAlert> = alerts
        .map { alert ->
            val placement       = place(alert, userDistrict, userState, userLat, userLon)
            val severityWeight  = alert.severity.rank / MAX_SEVERITY_RANK
            val proximityWeight = when (placement.relevance) {
                Relevance.DIRECT   -> PROXIMITY_DIRECT
                Relevance.NEARBY   -> PROXIMITY_NEARBY
                Relevance.REGIONAL -> PROXIMITY_REGIONAL
            }
            val vulnerability   = vulnerabilityFor(alert, riskScores)

            RankedAlert(
                alert        = alert,
                relevance    = placement.relevance,
                score        = severityWeight * proximityWeight * vulnerability.weight,
                distanceKm   = placement.distanceKm,
                personalNote = vulnerability.note
            )
        }
        .sortedWith(
            compareByDescending<RankedAlert> { it.score }
                .thenByDescending { it.alert.severity.rank }
                .thenByDescending { it.alert.pubDateMs }
        )

    /**
     * The subset worth putting on the dashboard.
     *
     * Drops REGIONAL entries once anything DIRECT or NEARBY is running, so a
     * Kolkata user with a local warning active is not also handed Madhya
     * Pradesh cards. When nothing local is running the regional entries stay,
     * because an empty list reads as "all clear" and that is a claim we have
     * not earned.
     */
    fun topRelevant(ranked: List<RankedAlert>, limit: Int = 5): List<RankedAlert> {
        val hasLocal = ranked.any { it.relevance != Relevance.REGIONAL }
        val kept     = if (hasLocal) ranked.filter { it.relevance != Relevance.REGIONAL } else ranked
        return kept.take(limit)
    }

    // -- Proximity --------------------------------------------------------------

    private class Placement(
        val relevance:  Relevance,
        val distanceKm: Double?
    )

    /**
     * Coordinates first, names second.
     *
     * A published centroid and covered area beat a name match, because the
     * same district name recurs across states and the feed's area strings are
     * free text. When the circle puts the user outside it we still try the
     * names: that circle stands in for a polygon the feed did not publish, so
     * a feed explicitly listing the user's district is the better evidence.
     * The distance is kept either way once it is computable.
     */
    private fun place(
        alert:        ImdAlert,
        userDistrict: String?,
        userState:    String?,
        userLat:      Double?,
        userLon:      Double?
    ): Placement {
        val alertLat   = alert.centroidLat
        val alertLon   = alert.centroidLon
        val distanceKm = if (userLat != null && userLon != null &&
            alertLat != null && alertLon != null) {
            haversineKm(userLat, userLon, alertLat, alertLon)
        } else null

        if (distanceKm != null) {
            val radiusKm = alert.effectiveRadiusKm ?: DEFAULT_RADIUS_KM
            if (distanceKm <= radiusKm) {
                val relevance =
                    if (distanceKm <= DIRECT_RADIUS_KM) Relevance.DIRECT else Relevance.NEARBY
                return Placement(relevance, distanceKm)
            }
        }

        val byName = when {
            alert.mentions(userDistrict) -> Relevance.DIRECT
            alert.mentions(userState)    -> Relevance.NEARBY
            else                         -> Relevance.REGIONAL
        }
        return Placement(byName, distanceKm)
    }

    /** True when the feed's area strings name this place. A blank place never matches. */
    private fun ImdAlert.mentions(place: String?): Boolean {
        val needle = place?.trim().orEmpty()
        if (needle.isEmpty()) return false
        return areas.any { it.contains(needle, ignoreCase = true) }
    }

    // -- Vulnerability ----------------------------------------------------------

    private class Vulnerability(
        val weight: Float,
        val note:   String?
    )

    /**
     * How exposed this user is in the domain the alert belongs to.
     *
     * The note is the strongest thing the app ever says and it must never sit
     * on top of a score the app could not actually compute. Any of the
     * following means we do not know enough to say it, and the note stays
     * null: the alert names no domain we score, we hold no score for that
     * domain, or the score we hold is not AVAILABLE - that is, it was capped
     * as PARTIAL, or it is the zero placeholder standing in for NEEDS_BAND or
     * NEEDS_MORE_HISTORY. A missing band is not a low reading, and telling
     * someone an alert affects them more than most on the strength of a number
     * we invented is precisely the failure this app exists to avoid.
     */
    private fun vulnerabilityFor(alert: ImdAlert, riskScores: List<RiskScore>): Vulnerability {
        val domain = hazardToDomain(alert.category, alert.title)
            ?: return Vulnerability(WEIGHT_UNKNOWN, null)

        val match = riskScores.firstOrNull { it.type == domain }
            ?: return Vulnerability(WEIGHT_UNKNOWN, null)

        if (match.availability != ScoreAvailability.AVAILABLE) {
            return Vulnerability(WEIGHT_UNKNOWN, null)
        }

        val weight = when {
            match.score >= VULNERABLE_HIGH_FROM     -> WEIGHT_HIGH
            match.score >= VULNERABLE_MODERATE_FROM -> WEIGHT_MODERATE
            else                                    -> WEIGHT_LOW
        }

        // The availability re-check below is deliberately redundant. The note's
        // precondition is restated in full at the point of writing it, so that
        // widening the weight bands later cannot quietly hand a note to a score
        // the app never computed.
        val note = if (weight == WEIGHT_HIGH &&
            match.availability == ScoreAvailability.AVAILABLE) {
            "Your ${domain.displayName} is already ${match.score}/100 - " +
                "this alert affects you more than most"
        } else null

        return Vulnerability(weight, note)
    }
}
