package com.bipss.app.data.environmental

import android.util.Log
import com.bipss.app.data.UserPreferencesRepository
import com.bipss.app.domain.environmental.*
import com.bipss.app.sos.LocationHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock

private const val TAG = "DisasterAlertRepo"

/** Display names of the alert feeds, used verbatim in "Checked … " copy. */
const val SOURCE_SACHET = "NDMA Sachet"
const val SOURCE_IMD    = "IMD"

/**
 * Assembles the environmental picture: disaster alerts, air quality, weather
 * and health advisories, with everything cached for offline use.
 *
 * Four bugs are fixed here relative to the previous version.
 *
 *  1. **Duplicates.** The IMD feed emits one item per district and every item
 *     carries an identical title, which produced a wall of indistinguishable
 *     cards. Alerts are now de-duplicated on a stable identifier.
 *  2. **No geo-filter.** A Kolkata user was shown Madhya Pradesh warnings.
 *     Alerts are now filtered by distance from the published centroid where
 *     one exists, falling back to district/state name matching.
 *  3. **Uniform tint.** Every card was coloured by the feed-wide maximum
 *     severity. Severity is now carried per alert.
 *  4. **Alerts vanished offline.** The cache stored only a title string, so
 *     going offline emptied the list at exactly the moment a warning matters
 *     most. The full alert list now round-trips through the cache.
 */
class DisasterAlertRepository(
    private val sachetFetcher:   SachetJsonFetcher,
    private val imdParser:       ImdRssParser,
    private val advisoryFetcher: HealthAdvisoryFetcher,
    private val cpcbFetcher:     CpcbAqiFetcher,
    private val openMeteo:       OpenMeteoFetcher,
    private val locationHelper:  LocationHelper,
    private val prefsRepo:       UserPreferencesRepository
) {
    /** Alerts centred further away than this are not shown, absent a name match. */
    private val MAX_RELEVANT_KM = 250.0

    /** A remembered position older than this is not "where the user is". */
    private val MAX_CACHED_LOCATION_AGE_MS = 24 * 3_600_000L

    /**
     * Assumed coverage radius for an alert that publishes no area.
     *
     * This is a filtering policy, not a measurement: it decides how far from an
     * alert's centre we still treat it as relevant, and it is never displayed
     * or fed into a score. Roughly a large district.
     */
    private val ASSUMED_ALERT_RADIUS_KM = 150.0

    /**
     * Most recent air quality read from the band's own ENS160, set by the
     * sensor pipeline. Null when no band is streaming, which keeps the
     * regional station value from silently masquerading as a personal reading.
     */
    @Volatile
    private var bandSensorAqi: Int? = null

    fun updateBandSensorAqi(aqi: Int?) { bandSensorAqi = aqi }

    /**
     * Guards against duplicate concurrent fetches.
     *
     * Several view models observe this repository and each runs a fetch on
     * construction; observed live as four identical syncs inside 100 ms on a
     * single app open. That is four times the network cost and, more
     * importantly, four times the quota against a rate-limited data.gov.in key.
     */
    private val fetchMutex = kotlinx.coroutines.sync.Mutex()

    @Volatile
    private var lastFetchAtMs = 0L

    /** Below this, a fetch returns the cached snapshot instead of hitting the network. */
    private val MIN_FETCH_INTERVAL_MS = 60_000L

    suspend fun fetchAndCache(force: Boolean = false): EnvironmentalSnapshot = fetchMutex.withLock {
        val sinceLast = System.currentTimeMillis() - lastFetchAtMs
        if (!force && lastFetchAtMs > 0L && sinceLast < MIN_FETCH_INTERVAL_MS) {
            getCachedSnapshot()?.let {
                Log.d(TAG, "Skipping fetch - last one was ${sinceLast}ms ago")
                return@withLock it
            }
        }
        lastFetchAtMs = System.currentTimeMillis()
        return@withLock doFetch()
    }

    private suspend fun doFetch(): EnvironmentalSnapshot {
        // Without location permission nothing is fetched at all: every source
        // here is location-scoped, and the national fallback that used to run
        // instead (all-India alerts, an arbitrary CPCB station) is exactly what
        // must never be shown. The last position is forgotten too.
        if (!locationHelper.hasAnyPermission()) {
            Log.i(TAG, "Location not granted - no environmental fetch")
            prefsRepo.clearCachedLocation()
            return getCachedSnapshot() ?: EnvironmentalSnapshot.EMPTY
        }

        Log.d(TAG, "Fetching environmental snapshot")

        // Location first - every downstream source is location-scoped. A fresh
        // fix is preferred; failing that, a cached one no older than a day
        // (e.g. a background run, which cannot request a new fix).
        val now0 = System.currentTimeMillis()
        val cachedLoc = prefsRepo.cachedLocation.first()
            ?.takeIf { it.atMs != null && now0 - it.atMs <= MAX_CACHED_LOCATION_AGE_MS }
        val live = locationHelper.getCurrentFix()
        val lat = live?.lat ?: cachedLoc?.lat
        val lon = live?.lon ?: cachedLoc?.lon
        if (lat == null || lon == null) {
            // Permission is granted but there is no usable position. Fetching
            // anyway would mean nationwide data presented as local, so the
            // previous (location-scoped) snapshot stays, with its real age.
            Log.w(TAG, "No usable location - keeping the previous snapshot")
            return getCachedSnapshot() ?: EnvironmentalSnapshot.EMPTY
        }

        var district = cachedLoc?.district
        var state    = cachedLoc?.state
        val resolved = locationHelper.resolveDistrictAndState(lat, lon)
        district = resolved.first ?: district
        state    = resolved.second ?: state
        if (live != null) prefsRepo.cacheLocation(lat, lon, district, state, live.timeMs)

        // Alerts: Sachet JSON is primary (it carries per-alert identifier,
        // severity, centroid and expiry). IMD RSS is the fallback - it has none
        // of those, so it is only used when Sachet returns nothing at all.
        // A null from a fetcher means the feed could not be read, which is
        // tracked separately from "read, and nothing active".
        val previous = getCachedSnapshot()
        val sachetAlerts = sachetFetcher.fetchAll()
        var sourcesChecked = if (sachetAlerts != null) listOf(SOURCE_SACHET) else emptyList()
        val rawAlerts: List<ImdAlert>? = if (!sachetAlerts.isNullOrEmpty()) sachetAlerts else {
            Log.w(TAG, "Sachet returned nothing - falling back to IMD RSS")
            val imdAlerts = imdParser.fetchImdAlerts()
            if (imdAlerts != null) sourcesChecked = sourcesChecked + SOURCE_IMD
            imdAlerts ?: sachetAlerts
        }
        val alertCheckFailed = rawAlerts == null
        val alerts = if (rawAlerts != null) {
            processAlerts(rawAlerts, district, state, lat, lon)
        } else {
            // Every feed failed: keep what the last successful check found
            // (still dropping anything that has since expired) instead of
            // overwriting a live warning with an empty list.
            Log.w(TAG, "No alert feed reachable - keeping alerts from the last good check")
            previous?.activeAlerts?.filterNot { it.isExpiredAt(System.currentTimeMillis()) }.orEmpty()
        }
        val alertsCheckedAtMs = if (alertCheckFailed) previous?.alertsCheckedAtMs ?: 0L else System.currentTimeMillis()
        if (alertCheckFailed) sourcesChecked = previous?.alertSourcesChecked.orEmpty()
        val severity = alerts.maxByOrNull { it.severity.rank }?.severity ?: AlertSeverity.NONE

        // Air quality. Regional station and band sensor are kept separate:
        // the band reads the air the wearer is actually breathing, the station
        // reads a site kilometres away, and their divergence is what powers the
        // indoor/outdoor inference.
        val cpcb = cpcbFetcher.fetchAqi(state, lat, lon)
        val sensor = bandSensorAqi
        val blended = BlendedReading.of(sensor?.toFloat(), cpcb?.aqiValue?.toFloat())
        val aqiValue = blended.displayValue?.toInt()

        // Weather: Open-Meteo is the only source (no key needed).
        val meteo = if (lat != null && lon != null) openMeteo.fetchCurrent(lat, lon) else null

        val advisories = advisoryFetcher.fetchAdvisories()

        val now = System.currentTimeMillis()
        val snapshot = EnvironmentalSnapshot(
            ambientTempC      = meteo?.tempC,
            humidityPercent   = meteo?.humidity,
            uvIndex           = meteo?.uvIndex,
            pressureHpa       = meteo?.pressureHpa,
            windSpeedKph      = meteo?.windSpeedKph,
            aqiValue          = aqiValue,
            aqiRiskLevel      = aqiValue?.let { AqiRiskLevel.fromAqi(it) },
            aqiSource         = blended.source,
            regionalAqiValue  = cpcb?.aqiValue,
            dominantPollutant = cpcb?.dominantPollutant,
            pollutantSubIndices = cpcb?.pollutantSubIndices.orEmpty(),
            aqiStationName    = cpcb?.stationName,
            aqiStationDistanceKm = cpcb?.distanceKm,
            activeAlerts      = alerts,
            advisories        = advisories,
            alertSeverity     = severity,
            alertsCheckedAtMs = alertsCheckedAtMs,
            alertSourcesChecked = sourcesChecked,
            alertCheckFailed  = alertCheckFailed,
            districtName      = district,
            stateName         = state,
            latitude          = lat,
            longitude         = lon,
            fetchedAtMs       = now,
            isStale           = false,
            isCachedData      = false,
            dataAgeMs         = 0L
        )

        prefsRepo.cacheEnvironmentalSnapshot(
            UserPreferencesRepository.CachedEnvData(
                tempC            = snapshot.ambientTempC,
                humidity         = snapshot.humidityPercent,
                uvIndex          = snapshot.uvIndex,
                pressureHpa      = snapshot.pressureHpa,
                windSpeedKph     = snapshot.windSpeedKph,
                aqiValue         = snapshot.aqiValue,
                aqiSource        = snapshot.aqiSource.name,
                regionalAqiValue = snapshot.regionalAqiValue,
                aqiPollutant     = snapshot.dominantPollutant,
                pollutantSubIndices = snapshot.pollutantSubIndices,
                aqiStationName   = snapshot.aqiStationName,
                aqiStationDistanceKm = snapshot.aqiStationDistanceKm,
                alertSeverity    = severity.name,
                alerts           = alerts,
                alertsCheckedAtMs = alertsCheckedAtMs,
                alertSourcesChecked = sourcesChecked,
                alertCheckFailed = alertCheckFailed,
                advisories       = advisories,
                districtName     = district,
                stateName        = state,
                latitude         = lat,
                longitude        = lon,
                fetchedAtMs      = now
            )
        )
        return snapshot
    }

    /**
     * Drop expired, de-duplicate, keep only what is geographically relevant,
     * then order by severity and proximity.
     *
     * Ordering matters as much as filtering: a Red warning for the user's own
     * district must not sit below a stale Yellow watch two states away.
     */
    internal fun processAlerts(
        raw:      List<ImdAlert>,
        district: String?,
        state:    String?,
        lat:      Double?,
        lon:      Double?
    ): List<ImdAlert> {
        val now = System.currentTimeMillis()

        val deduped = raw
            .filterNot { it.isExpiredAt(now) }
            .distinctBy { it.identifier.ifBlank { it.title + "|" + it.areaLabel } }

        val scored = deduped.mapNotNull { alert ->
            val relevance = relevanceOf(alert, district, state, lat, lon)
            relevance?.let { alert to it }
        }

        // If nothing is local, show the most severe national items rather than
        // an empty screen - but only after the local ones have had priority.
        val ordered = scored.sortedWith(
            compareByDescending<Pair<ImdAlert, Relevance>> { it.first.severity.rank }
                .thenBy { it.second.ordinal }
                .thenByDescending { it.first.pubDateMs }
        )
        return ordered.map { it.first }
    }

    private fun relevanceOf(
        alert:    ImdAlert,
        district: String?,
        state:    String?,
        lat:      Double?,
        lon:      Double?
    ): Relevance? {
        // Coordinate match is preferred: it works offline and does not depend
        // on the feed spelling a district the same way the geocoder does.
        if (lat != null && lon != null && alert.centroidLat != null && alert.centroidLon != null) {
            val distance = haversineKm(lat, lon, alert.centroidLat, alert.centroidLon)
            val radius = alert.effectiveRadiusKm ?: ASSUMED_ALERT_RADIUS_KM
            return when {
                distance <= minOf(radius, 60.0) -> Relevance.DIRECT
                distance <= radius              -> Relevance.NEARBY
                distance <= MAX_RELEVANT_KM     -> Relevance.REGIONAL
                else                            -> null
            }
        }

        // Name fallback.
        if (!district.isNullOrBlank() && alert.areas.any { it.contains(district, ignoreCase = true) })
            return Relevance.DIRECT
        if (!state.isNullOrBlank() && alert.areas.any { it.contains(state, ignoreCase = true) })
            return Relevance.NEARBY

        // An alert that cannot be placed relative to the user is not shown.
        // (There used to be a "no location at all -> keep everything as
        // regional" branch; fetches now never run without a location.)
        return null
    }

    /** Reads the cached snapshot. Returns null when nothing has ever been fetched. */
    suspend fun getCachedSnapshot(): EnvironmentalSnapshot? {
        val cached = prefsRepo.cachedEnvironmentalData.first() ?: return null
        val ageMs = System.currentTimeMillis() - cached.fetchedAtMs
        return EnvironmentalSnapshot(
            ambientTempC      = cached.tempC,
            humidityPercent   = cached.humidity,
            uvIndex           = cached.uvIndex,
            pressureHpa       = cached.pressureHpa,
            windSpeedKph      = cached.windSpeedKph,
            aqiValue          = cached.aqiValue,
            aqiRiskLevel      = cached.aqiValue?.let { AqiRiskLevel.fromAqi(it) },
            aqiSource         = runCatching { AqiSource.valueOf(cached.aqiSource) }
                                    .getOrDefault(AqiSource.NONE),
            regionalAqiValue  = cached.regionalAqiValue,
            dominantPollutant = cached.aqiPollutant,
            pollutantSubIndices = cached.pollutantSubIndices,
            aqiStationName    = cached.aqiStationName,
            aqiStationDistanceKm = cached.aqiStationDistanceKm,
            // Alerts survive offline now, with their age stamped on screen.
            activeAlerts      = cached.alerts,
            advisories        = cached.advisories,
            alertSeverity     = runCatching { AlertSeverity.valueOf(cached.alertSeverity) }
                                    .getOrDefault(AlertSeverity.NONE),
            alertsCheckedAtMs = cached.alertsCheckedAtMs,
            alertSourcesChecked = cached.alertSourcesChecked,
            alertCheckFailed  = cached.alertCheckFailed,
            districtName      = cached.districtName,
            stateName         = cached.stateName,
            latitude          = cached.latitude,
            longitude         = cached.longitude,
            fetchedAtMs       = cached.fetchedAtMs,
            isStale           = ageMs > EnvironmentalSnapshot.STALE_THRESHOLD_MS,
            isCachedData      = true,
            dataAgeMs         = ageMs
        )
    }
}
