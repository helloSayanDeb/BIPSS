package com.bipss.app.sos

import android.util.Log
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAG = "HospitalLocator"

/** A hospital near the user, with the numbers OpenStreetMap lists for it. */
data class Hospital(
    val name:       String,
    val distanceKm: Double,
    /** An Indian mobile (+91 6-9...), the only kind of number that can receive the SOS text. */
    val mobile:     String?,
    /** A full landline number (area code included), for the contacts to call. */
    val landline:   String?
) {
    /** The number a person should call: the landline switchboard first, else the mobile. */
    val callNumber: String? get() = landline ?: mobile
}

data class HospitalLookup(
    val status:    Status,
    /** The nearest hospitals with a usable number, nearest first (at most [HospitalLocator.MAX_HOSPITALS]). */
    val hospitals: List<Hospital> = emptyList(),
    /** How many hospitals were mapped within the search radius, with or without numbers. */
    val found:     Int = 0
) {
    enum class Status {
        FOUND,
        /** Hospitals are mapped nearby, but none lists a number that can be called or texted. */
        NONE_WITH_NUMBER,
        NONE_NEARBY,
        /** Every endpoint failed or answered with something unreadable. */
        FAILED
    }
}

/**
 * Finds the nearest hospitals for an SOS, from OpenStreetMap via Overpass.
 * Keyless, so it works in any build, but only as good as the map: in central
 * Kolkata (checked 29 Sep 2026) 26% of the mapped hospitals list a usable
 * number and 4% a mobile - see the P7 notes on a keyed source.
 *
 * The main server and a mirror are asked at once and the first readable
 * answer wins; the other request is cancelled. The same query took 2 s to
 * 11 s on the main server within minutes, so the two are raced rather than
 * tried in turn. The lookup starts with the SOS countdown, which leaves room
 * for [CALL_TIMEOUT_S] per call.
 */
class HospitalLocator(okHttpClient: OkHttpClient) {

    private val client = okHttpClient.newBuilder()
        .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    suspend fun nearest(lat: Double, lon: Double): HospitalLookup = coroutineScope {
        val query = HospitalParser.query(lat, lon, RADIUS_M)
        // Parsing a large answer stays off the main thread.
        val attempts = ENDPOINTS.map { endpoint -> async(Dispatchers.Default) { fetch(endpoint, query)?.let { HospitalParser.parse(it, lat, lon) } } }
        val pending = attempts.toMutableList()
        while (pending.isNotEmpty()) {
            val (done, result) = select<Pair<Deferred<HospitalLookup?>, HospitalLookup?>> {
                pending.forEach { d -> d.onAwait { d to it } }
            }
            pending.remove(done)
            if (result != null) {
                pending.forEach { it.cancel() }
                return@coroutineScope result
            }
        }
        HospitalLookup(HospitalLookup.Status.FAILED)
    }

    /** The body, or null for any failure. Cancelling the coroutine cancels the HTTP call. */
    private suspend fun fetch(endpoint: String, query: String): String? {
        val call = client.newCall(
            Request.Builder().url(endpoint)
                .header("User-Agent", USER_AGENT)
                .post(FormBody.Builder().add("data", query).build())
                .build()
        )
        return try {
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                    override fun onResponse(call: Call, response: Response) {
                        val body = runCatching {
                            response.use { r ->
                                if (r.isSuccessful) r.body?.string() else null.also { Log.w(TAG, "$endpoint: HTTP ${r.code}") }
                            }
                        }.getOrNull()
                        if (cont.isActive) cont.resume(body)
                    }
                })
            }
        } catch (e: IOException) {
            Log.w(TAG, "$endpoint: ${e.message}")
            null
        }
    }

    companion object {
        const val RADIUS_M = 15_000
        const val MAX_HOSPITALS = 3
        const val CALL_TIMEOUT_S = 12L
        private const val USER_AGENT = "BIPSS/1.0 (Android; SOS nearest-hospital lookup)"
        private val ENDPOINTS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter"
        )
    }
}

/** Overpass query and response handling. Pure, so it is tested without a network. */
object HospitalParser {

    /**
     * Every hospital within the radius is counted, but only the ones listing
     * a number are returned - the rest cannot be called or texted, and
     * leaving them out made the answer 2.4 times smaller (Kolkata).
     */
    fun query(lat: Double, lon: Double, radiusM: Int): String {
        val around = "around:$radiusM,${"%.6f".format(Locale.ROOT, lat)},${"%.6f".format(Locale.ROOT, lon)}"
        return "[out:json][timeout:${HospitalLocator.CALL_TIMEOUT_S}];" +
            "(nwr[\"amenity\"=\"hospital\"]($around);nwr[\"healthcare\"=\"hospital\"]($around);)->.h;" +
            ".h out count;" +
            "(nwr.h[\"phone\"];nwr.h[\"contact:phone\"];nwr.h[\"contact:mobile\"];nwr.h[\"mobile\"];);" +
            "out center tags;"
    }

    private data class Candidate(val name: String, val distanceKm: Double, val numbers: List<String>)

    /** Null when [json] is not an Overpass answer (a failed read, not "no hospitals"). */
    fun parse(json: String, lat: Double, lon: Double, limit: Int = HospitalLocator.MAX_HOSPITALS): HospitalLookup? {
        val elements = runCatching {
            Json.parseToJsonElement(json).jsonObject["elements"]?.jsonArray
        }.getOrNull() ?: return null

        // "out count" puts the total first; an answer without it counts what it lists.
        val counted = elements.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "count" }
            ?.jsonObject?.get("tags")?.jsonObject?.str("total")?.toIntOrNull()

        val candidates = elements.mapNotNull { el ->
            val o = el.jsonObject
            if (o["type"]?.jsonPrimitive?.contentOrNull == "count") return@mapNotNull null
            val tags = o["tags"]?.jsonObject ?: return@mapNotNull null
            val centre = o["center"]?.jsonObject
            val eLat = (o["lat"] ?: centre?.get("lat"))?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val eLon = (o["lon"] ?: centre?.get("lon"))?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            // English name where mapped: it keeps the SMS in the 160-character GSM alphabet.
            val name = (tags.str("name:en") ?: tags.str("name"))?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if (!canTakeEmergencies(name, tags)) return@mapNotNull null
            val raw = listOf("contact:mobile", "mobile", "phone", "contact:phone").mapNotNull { tags.str(it) }
            Candidate(name, haversineKm(lat, lon, eLat, eLon), raw)
        }
        val found = counted ?: candidates.size
        if (candidates.isEmpty()) {
            return if (found > 0) HospitalLookup(HospitalLookup.Status.NONE_WITH_NUMBER, found = found)
            else HospitalLookup(HospitalLookup.Status.NONE_NEARBY)
        }

        val areaCodes = IndianPhone.AreaCodes(candidates.flatMap { c -> c.numbers.flatMap { IndianPhone.parseTag(it, null) } })
        val seenNumbers = HashSet<String>()
        val usable = candidates.sortedBy { it.distanceKm }.mapNotNull { c ->
            val numbers = c.numbers.flatMap { IndianPhone.parseTag(it, areaCodes) }
            // The same number on two map entries is one hospital (a campus mapped twice).
            if (numbers.isEmpty() || numbers.any { it.e164 in seenNumbers }) return@mapNotNull null
            numbers.forEach { seenNumbers += it.e164 }
            Hospital(
                name       = c.name,
                distanceKm = c.distanceKm,
                mobile     = numbers.firstOrNull { it.isMobile }?.e164,
                landline   = numbers.firstOrNull { !it.isMobile }?.e164
            )
        }
        return if (usable.isEmpty()) HospitalLookup(HospitalLookup.Status.NONE_WITH_NUMBER, found = found)
        else HospitalLookup(HospitalLookup.Status.FOUND, usable.take(limit), found = found)
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    /**
     * Leaves out places that are mapped as hospitals but cannot help in an
     * emergency: tagged emergency=no, a speciality made up only of eye, skin
     * or dental care, or a name that says so. The first test run from a
     * Kolkata street listed an eye hospital as the third-nearest; many eye
     * hospitals there carry no speciality tag, only the name. A heuristic -
     * it only removes, never ranks, so an unrecognised specialist is kept.
     */
    internal fun canTakeEmergencies(name: String, tags: JsonObject): Boolean {
        if (tags.str("emergency") == "no") return false
        val specialities = tags.str("healthcare:speciality")?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }
        if (!specialities.isNullOrEmpty() && specialities.all { it in NON_ACUTE_SPECIALITIES }) return false
        return !NON_ACUTE_NAME.containsMatchIn(name)
    }

    private val NON_ACUTE_SPECIALITIES = setOf(
        "ophthalmology", "optometry", "dermatology", "dentistry", "orthodontics",
        "dental_oral_maxillo_facial_surgery", "cosmetic_surgery"
    )
    // "Netra" and "Nayan" are "eye" in Hindi and Bengali names.
    private val NON_ACUTE_NAME = Regex(
        "\\b(eye|eyes|netra|nayan|dental|dentist|skin|ivf|fertility|diagnostics?|pathology|imaging)\\b",
        RegexOption.IGNORE_CASE
    )

    internal fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
        return 2 * 6371.0 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}

/**
 * Indian numbers as OpenStreetMap has them: any of "+91 98...", "098...",
 * "033 2432 0043", "22651808" (no area code), separated by ";", "," or "/".
 */
object IndianPhone {

    data class Number(val e164: String, val isMobile: Boolean)

    /** Every usable number in one tag value. */
    fun parseTag(value: String, areaCodes: AreaCodes?): List<Number> {
        val parts = value.split(';', ',', '/').map { it.trim() }.filter { it.isNotEmpty() }
        // A full landline in the same tag gives its area code to the bare local
        // numbers beside it ("03324628677;24628678").
        val sibling = parts.mapNotNull { national(it) }.firstOrNull { LANDLINE.matches(it) }
        return parts.mapNotNull { part ->
            val nat = national(part) ?: return@mapNotNull null
            when {
                MOBILE.matches(nat)   -> Number("+91$nat", isMobile = true)
                LANDLINE.matches(nat) -> Number("+91$nat", isMobile = false)
                // "/39" style alternates and extension fragments are not numbers.
                nat.length < 6 || nat.length > 8 -> null
                else -> (sibling?.let { withAreaCode(it.take(10 - nat.length), nat) }
                    ?: areaCodes?.forLocal(nat)?.let { withAreaCode(it, nat) })
                    ?.let { Number("+91$it", isMobile = false) }
            }
        }
    }

    /**
     * The area code nearby full landlines agree on, for local numbers mapped
     * without one. An area code and its local number make 10 digits, so an
     * 8-digit local number needs a 2-digit code (Kolkata 33), a 7-digit one
     * 3 digits, and so on. Only a clear majority counts - at least 3 of the
     * nearby numbers and 60% of them - because a wrong code is a wrong number.
     */
    class AreaCodes(numbers: List<Number>) {
        private val landlines = numbers.filter { !it.isMobile }.map { it.e164.removePrefix("+91") }
        private val byLength = HashMap<Int, String?>()

        fun forLocal(local: String): String? = byLength.getOrPut(10 - local.length) {
            val len = 10 - local.length
            if (len !in 2..4 || landlines.size < 3) return@getOrPut null
            val (code, count) = landlines.groupingBy { it.take(len) }.eachCount().maxByOrNull { it.value }
                ?: return@getOrPut null
            code.takeIf { count >= 3 && count >= landlines.size * 0.6 }
        }
    }

    /** Digits after the country code and trunk prefix, or null for anything else. */
    internal fun national(raw: String): String? {
        val s = raw.filter { it.isDigit() || it == '+' }
        val digits = s.filter { it.isDigit() }
        val nat = when {
            s.startsWith("+91")                          -> digits.drop(2)
            s.startsWith("0091")                         -> digits.drop(4)
            s.startsWith("+")                            -> return null      // another country
            digits.startsWith("91") && digits.length == 12 -> digits.drop(2)
            digits.startsWith("0")                       -> digits.drop(1)
            else                                         -> digits
        }
        return nat.takeIf { it.isNotEmpty() }
    }

    private fun withAreaCode(code: String, local: String): String? =
        (code + local).takeIf { it.length == 10 && LANDLINE.matches(it) }

    private val MOBILE = Regex("[6-9]\\d{9}")
    private val LANDLINE = Regex("[2-5]\\d{9}")
}
