package com.bipss.app.data.environmental

import android.util.Log
import com.bipss.app.data.UserPreferencesRepository
import com.bipss.app.domain.environmental.*
import com.bipss.app.sos.LocationHelper
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

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
    private val capEnricher:     ImdCapEnricher,
    private val advisoryFetcher: HealthAdvisoryFetcher,
    private val cpcbFetcher:     CpcbAqiFetcher,
    private val openMeteo:       OpenMeteoFetcher,
    private val airQualityModel: OpenMeteoAirQualityFetcher,
    private val locationHelper:  LocationHelper,
    private val prefsRepo:       UserPreferencesRepository
) {
    /** Alerts centred further away than this are not shown, absent a name match. */
    private val MAX_RELEVANT_KM = 250.0

    /** Outside a published polygon but this close to it: "nearby". */
    private val POLYGON_NEARBY_KM = 50.0

    /** A remembered position older than this is not "where the user is". */
    private val MAX_CACHED_LOCATION_AGE_MS = 24 * 3_600_000L

    /**
     * How long the sources of one sync have, together. Location setup allows
     * the whole fetch 20 s, and the position fix and place lookup come first.
     */
    private val SOURCE_DEADLINE_MS = 12_000L

    /** Outbreak reports are re-read this often, unless a refresh is forced. */
    private val ADVISORY_REFRESH_MS = 3 * 3_600_000L

    /** A station reading is shown, with its time, for this long after CPCB stops answering. */
    private val CPCB_CARRY_MS = 3 * 3_600_000L

    private val DAY_MS = 24 * 3_600_000L

    /**
     * Assumed coverage radius for an alert that publishes no area.
     *
     * This is a filtering policy, not a measurement: it decides how far from an
     * alert's centre we still treat it as relevant, and it is never displayed
     * or fed into a score. Roughly a large district.
     */
    private val ASSUMED_ALERT_RADIUS_KM = 150.0

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
        return@withLock doFetch(force)
    }

    private suspend fun doFetch(force: Boolean): EnvironmentalSnapshot {
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

        val previous = getCachedSnapshot()

        // The sources are independent, so they are fetched side by side. In
        // sequence, one unreachable feed added its whole timeout to every sync
        // (four DNS failures took about 48 s) and held up the sources that
        // were answering. They also share a deadline: a source that has not
        // answered by then is cancelled and counts as unreachable, and the
        // ones that did answer keep their results. Without it, stalled servers
        // held the whole sync, and the setup screen waiting on it, for about
        // two minutes (data.gov.in alone took 61 s to time out, 29 Sep 2026).
        val deadline = System.currentTimeMillis() + SOURCE_DEADLINE_MS
        // Outbreak reports change a few times a week, so they are read every few
        // hours (or on a manual refresh), not on every 15-minute sync.
        val advisoriesDue = force || previous == null || previous.advisorySources.isEmpty() ||
            System.currentTimeMillis() - previous.advisoriesFetchedAtMs >= ADVISORY_REFRESH_MS
        val fetched = coroutineScope {
            val alertsJob   = async { fetchAlertFeeds(deadline) }
            val cpcbJob     = async { beforeDeadline("CPCB", deadline) { cpcbFetcher.fetchAqi(state, lat, lon) } }
            val modelJob    = async { beforeDeadline("Open-Meteo air quality", deadline) { airQualityModel.fetch(lat, lon) } }
            val meteoJob    = async { beforeDeadline("Open-Meteo", deadline) { openMeteo.fetchCurrent(lat, lon) } }
            val advisoryJob = async { if (advisoriesDue) beforeDeadline("Advisories", deadline) { advisoryFetcher.fetch() } else null }
            Fetched(alertsJob.await(), cpcbJob.await(), modelJob.await(), meteoJob.await(), advisoryJob.await())
        }
        val (feeds, cpcb, modelAqi, meteo) = fetched

        val rawAlerts = feeds.alerts
        var sourcesChecked = feeds.sourcesChecked
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

        val now = System.currentTimeMillis()

        // Air quality, in order of preference: a CPCB station reading now; the
        // last station reading while it is under three hours old (stations
        // report hourly, and data.gov.in is often down for hours); the CAMS
        // model, labelled as modelled. The band's ENS160 reports UBA 1-5, not
        // a 0-500 AQI, so it is never shown in place of either.
        val air = chooseAir(cpcb, modelAqi, previous, now)

        // Outbreak reports: this check's, or - when not due or when no agency
        // answered - the last ones, still limited to the last 30 days.
        val advisoryResult = fetched.advisories?.takeIf { it.sourcesAnswered.isNotEmpty() }
        val advisories = advisoryResult?.advisories
            ?: previous?.advisories.orEmpty().filter { now - it.publishedMs <= AdvisoryDigest.MAX_AGE_DAYS * DAY_MS }
        val advisorySources = advisoryResult?.sourcesAnswered ?: previous?.advisorySources.orEmpty()
        val advisoriesFetchedAtMs = if (advisoryResult != null) now else previous?.advisoriesFetchedAtMs ?: 0L

        val snapshot = EnvironmentalSnapshot(
            ambientTempC      = meteo?.tempC,
            humidityPercent   = meteo?.humidity,
            uvIndex           = meteo?.uvIndex,
            pressureHpa       = meteo?.pressureHpa,
            windSpeedKph      = meteo?.windSpeedKph,
            aqiValue          = air.aqi,
            aqiRiskLevel      = air.aqi?.let { AqiRiskLevel.fromAqi(it) },
            aqiSource         = air.source,
            // The band's air readings are only ever learned against a station.
            regionalAqiValue  = air.aqi.takeIf { air.source == AqiSource.CPCB },
            dominantPollutant = air.dominant,
            pollutantSubIndices = air.subIndices,
            aqiStationName    = air.stationName,
            aqiStationDistanceKm = air.stationKm,
            aqiObservedAtMs   = air.observedAtMs,
            aqiNote           = air.note,
            activeAlerts      = alerts,
            advisories        = advisories,
            advisorySources   = advisorySources,
            advisoriesFetchedAtMs = advisoriesFetchedAtMs,
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
                aqiObservedAtMs  = snapshot.aqiObservedAtMs,
                aqiNote          = snapshot.aqiNote,
                alertSeverity    = severity.name,
                alerts           = alerts,
                alertsCheckedAtMs = alertsCheckedAtMs,
                alertSourcesChecked = sourcesChecked,
                alertCheckFailed = alertCheckFailed,
                advisories       = advisories,
                advisorySources  = advisorySources,
                advisoriesFetchedAtMs = advisoriesFetchedAtMs,
                districtName     = district,
                stateName        = state,
                latitude         = lat,
                longitude        = lon,
                fetchedAtMs      = now
            )
        )
        return snapshot
    }

    /** A finished result, so "finished with null" and "ran out of time" stay distinct. */
    private class Finished<T>(val value: T)

    /**
     * [block]'s result, or null when it has not finished by [deadlineMs]. It is
     * then cancelled, and with it the HTTP call it is waiting on.
     */
    private suspend fun <T> beforeDeadline(source: String, deadlineMs: Long, block: suspend () -> T): T? {
        val finished = withTimeoutOrNull((deadlineMs - System.currentTimeMillis()).coerceAtLeast(0L)) { Finished(block()) }
        if (finished == null) Log.w(TAG, "$source: no answer in time - cancelled")
        return finished?.value
    }

    /** What one sync fetched; see [doFetch]. [advisories] is null when they were not due. */
    private data class Fetched(
        val feeds:      AlertFeeds,
        val cpcb:       AqiSnapshot?,
        val modelAqi:   ModelledAqi.Result?,
        val meteo:      OpenMeteoFetcher.OpenMeteoSnapshot?,
        val advisories: HealthAdvisoryFetcher.Result?
    )

    /** The air-quality figure a sync settled on, with where it came from. */
    private data class Air(
        val aqi:          Int?,
        val source:       AqiSource,
        val dominant:     String? = null,
        val subIndices:   Map<String, Int> = emptyMap(),
        val stationName:  String? = null,
        val stationKm:    Double? = null,
        val observedAtMs: Long? = null,
        val note:         String? = null
    )

    private fun chooseAir(cpcb: AqiSnapshot?, model: ModelledAqi.Result?, previous: EnvironmentalSnapshot?, now: Long): Air {
        if (cpcb?.aqiValue != null) {
            return Air(cpcb.aqiValue, AqiSource.CPCB, cpcb.dominantPollutant, cpcb.pollutantSubIndices,
                       cpcb.stationName, cpcb.distanceKm, cpcb.observedAtMs ?: now)
        }
        val lastStation = previous?.takeIf { it.aqiSource == AqiSource.CPCB && it.aqiValue != null }
        val lastObserved = lastStation?.let { it.aqiObservedAtMs ?: it.fetchedAtMs }
        if (lastStation != null && lastObserved != null && now - lastObserved <= CPCB_CARRY_MS) {
            return Air(lastStation.aqiValue, AqiSource.CPCB, lastStation.dominantPollutant, lastStation.pollutantSubIndices,
                       lastStation.aqiStationName, lastStation.aqiStationDistanceKm, lastObserved,
                       note = "CPCB isn't answering right now, so this is the station's last reading.")
        }
        val why = when (cpcbFetcher.lastOutcome) {
            CpcbAqiFetcher.Outcome.NO_STATION_NEARBY -> "No CPCB station within 50 km."
            CpcbAqiFetcher.Outcome.NOT_CONFIGURED    -> "CPCB isn't set up in this build."
            CpcbAqiFetcher.Outcome.OK                -> "The nearest CPCB station isn't reporting a full index."
            else                                     -> "CPCB stations aren't answering right now."
        }
        if (model != null) {
            return Air(model.computation.aqi, AqiSource.MODEL, model.computation.dominantPollutant,
                       model.computation.subIndices, observedAtMs = model.observedAtMs,
                       note = "$why This is a modelled estimate for the area (CAMS, via Open-Meteo), not a station reading.")
        }
        return Air(null, AqiSource.NONE, note = why)
    }

    /** Null [alerts] means no feed could be read - distinct from "read, and nothing active". */
    private data class AlertFeeds(val alerts: List<ImdAlert>?, val sourcesChecked: List<String>)

    /**
     * Alerts: IMD first, each item completed from its CAP document (severity,
     * expiry, instruction, polygon). NDMA Sachet only when IMD cannot be read
     * or has nothing current.
     */
    private suspend fun fetchAlertFeeds(deadlineMs: Long): AlertFeeds {
        val now = System.currentTimeMillis()
        val imd = beforeDeadline("IMD", deadlineMs) { imdParser.fetchImdAlerts() }
            ?.let { capEnricher.enrich(it, now, deadlineMs) }
        if (imd != null && imd.any { !it.isExpiredAt(now) }) return AlertFeeds(imd, listOf(SOURCE_IMD))

        Log.i(TAG, if (imd == null) "IMD unreachable - trying Sachet" else "Nothing current from IMD - trying Sachet")
        val sachet = beforeDeadline("Sachet", deadlineMs) { sachetFetcher.fetchAll() }
        val checked = listOfNotNull(SOURCE_IMD.takeIf { imd != null }, SOURCE_SACHET.takeIf { sachet != null })
        // Null only when neither feed could be read.
        return AlertFeeds(if (sachet == null && imd == null) null else imd.orEmpty() + sachet.orEmpty(), checked)
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
        // A published polygon is the most exact answer: inside is direct;
        // outside, the distance to its edge (approximated by the nearest
        // vertex) decides.
        if (lat != null && lon != null && alert.polygons.isNotEmpty()) {
            if (alert.polygons.any { Geo.contains(it, lat, lon) }) return Relevance.DIRECT
            val edgeKm = alert.polygons.minOf { Geo.nearestVertexKm(it, lat, lon) }
            return when {
                edgeKm <= POLYGON_NEARBY_KM -> Relevance.NEARBY
                edgeKm <= MAX_RELEVANT_KM   -> Relevance.REGIONAL
                else                        -> null
            }
        }

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
    /**
     * The cached snapshot as it changes, EMPTY when there is none (never
     * fetched, or cleared when location permission went).
     */
    val cachedSnapshots: kotlinx.coroutines.flow.Flow<EnvironmentalSnapshot> =
        prefsRepo.cachedEnvironmentalData.distinctUntilChanged().map { it?.toSnapshot() ?: EnvironmentalSnapshot.EMPTY }

    /** Forgets everything location-derived: the last position and the environment cached from it. */
    suspend fun clearLocationDerivedData() {
        prefsRepo.clearCachedLocation()
        prefsRepo.clearEnvironmentalSnapshot()
    }

    suspend fun getCachedSnapshot(): EnvironmentalSnapshot? =
        prefsRepo.cachedEnvironmentalData.first()?.toSnapshot()

    private fun UserPreferencesRepository.CachedEnvData.toSnapshot(): EnvironmentalSnapshot {
        val cached = this
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
            aqiObservedAtMs   = cached.aqiObservedAtMs,
            aqiNote           = cached.aqiNote,
            // Alerts survive offline now, with their age stamped on screen.
            activeAlerts      = cached.alerts,
            advisories        = cached.advisories,
            advisorySources   = cached.advisorySources,
            advisoriesFetchedAtMs = cached.advisoriesFetchedAtMs,
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
