package com.bipss.app.domain.environmental

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Outbreak reports from several public-health agencies, turned into a short
 * list a person can take in at a glance: one card per outbreak, recent only,
 * summarised on the phone. Pure Kotlin, so all of it is unit-tested.
 *
 * The agencies publish the same outbreak many times (WHO posted five updates
 * on one Ebola outbreak in six weeks), and each report opens with background
 * and boilerplate. Showing each report as it came read as the same card
 * copied over and over.
 */

/** Where an outbreak report came from. */
enum class AdvisorySource(val label: String) { WHO("WHO"), CDC("CDC"), ECDC("ECDC") }

/** One report as its agency published it, before grouping and summarising. */
data class AdvisoryItem(
    val source:      AdvisorySource,
    val id:          String,
    val title:       String,
    /** Plain text, markup already removed. */
    val text:        String,
    val publishedMs: Long,
    val url:         String? = null,
    /** WHO's risk-assessment section, plain text. */
    val assessment:  String = "",
    /** CDC travel-notice level, 1 to 4. */
    val travelLevel: Int? = null
)

/** What a report is about. Reports with the same [key] describe the same outbreak. */
data class OutbreakTopic(val disease: String?, val place: String?, val scope: AdvisoryScope) {
    val key: String get() = "${disease ?: "?"}|${place ?: "?"}"
}

object OutbreakTopics {

    /** Most specific first: "avian influenza" must win over "influenza". */
    private val DISEASES: List<Pair<Regex, String>> = listOf(
        "ebola|bundibugyo|sudan virus disease" to "Ebola",
        "marburg" to "Marburg",
        "nipah" to "Nipah",
        "avian influenza|bird flu|\\bh5n\\d\\b|\\bh5\\b|\\bh7n9\\b|\\bh9n2\\b|\\bh10n3\\b" to "Avian influenza",
        "swine influenza|\\bh1n2v?\\b|\\bh3n2v\\b|\\bh1n1v\\b" to "Swine influenza",
        "\\bmers\\b|mers-cov|middle east respiratory" to "MERS",
        "covid|sars-cov-2" to "COVID-19",
        "mpox|monkeypox" to "Mpox",
        "chikungunya" to "Chikungunya",
        "dengue" to "Dengue",
        "zika" to "Zika",
        "yellow fever" to "Yellow fever",
        "oropouche" to "Oropouche",
        "west nile" to "West Nile virus",
        "japanese encephalitis" to "Japanese encephalitis",
        "crimean-congo|\\bcchf\\b" to "Crimean-Congo haemorrhagic fever",
        "lassa" to "Lassa fever",
        "rift valley" to "Rift Valley fever",
        "hantavirus|andes virus" to "Hantavirus",
        "malaria" to "Malaria",
        "cholera" to "Cholera",
        "paratyphoid" to "Paratyphoid fever",
        "typhoid" to "Typhoid",
        "hepatitis a\\b" to "Hepatitis A",
        "hepatitis e\\b" to "Hepatitis E",
        "polio" to "Polio",
        "measles" to "Measles",
        "diphtheria" to "Diphtheria",
        "pertussis|whooping cough" to "Pertussis",
        "meningococcal|meningitis" to "Meningococcal disease",
        "plague" to "Plague",
        "anthrax" to "Anthrax",
        "rabies" to "Rabies",
        "leptospirosis" to "Leptospirosis",
        "scrub typhus" to "Scrub typhus",
        "shigell" to "Shigellosis",
        "salmonell" to "Salmonellosis",
        "botulism" to "Botulism",
        "legionell|legionnaires" to "Legionnaires' disease",
        "kyasanur" to "Kyasanur forest disease",
        "influenza" to "Influenza"
    ).map { (pattern, name) -> Regex(pattern, RegexOption.IGNORE_CASE) to name }

    private val GLOBAL_MARKER = Regex(
        "\\bglobal\\b|worldwide|multi-country|multi-locations|multiple countries", RegexOption.IGNORE_CASE
    )
    private val INDIA = Regex("\\bindia\\b", RegexOption.IGNORE_CASE)

    /** A title naming an Indian state is about India even without the word. */
    private val INDIAN_STATES = Regex(
        "\\b(andhra pradesh|arunachal pradesh|assam|bihar|chhattisgarh|goa|gujarat|haryana|himachal pradesh|" +
            "jharkhand|karnataka|kerala|madhya pradesh|maharashtra|manipur|meghalaya|mizoram|nagaland|odisha|" +
            "punjab|rajasthan|sikkim|tamil nadu|telangana|tripura|uttar pradesh|uttarakhand|west bengal|delhi|" +
            "jammu and kashmir|ladakh|puducherry)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Short display names for places the agencies spell out in full. Checked in order. */
    private val PLACE_ALIASES: List<Pair<Regex, String>> = listOf(
        "democratic republic of (the )?congo|\\bdr ?congo\\b|\\bdrc\\b" to "DR Congo",
        "republic of (the )?congo" to "Congo",
        "united states|\\busa\\b|\\bu\\.s\\.a?\\.?" to "USA",
        "united kingdom|\\buk\\b" to "UK",
        "eu/eea|european union|\\beurope\\b" to "Europe",
        "cabo verde|cape verde" to "Cabo Verde",
        "\\bindia\\b" to "India"
    ).map { (pattern, name) -> Regex(pattern, RegexOption.IGNORE_CASE) to name }

    fun of(item: AdvisoryItem): OutbreakTopic {
        val title = cleanTitle(item.title)
        val disease = DISEASES.firstOrNull { it.first.containsMatchIn(item.title) }?.second
            ?: DISEASES.firstOrNull { it.first.containsMatchIn(item.text.take(300)) }?.second
        val placeText = placeSegment(item)
        return when {
            INDIA.containsMatchIn(item.title) || INDIAN_STATES.containsMatchIn(item.title) ->
                OutbreakTopic(disease, "India", AdvisoryScope.NATIONAL)
            GLOBAL_MARKER.containsMatchIn(item.title) ->
                OutbreakTopic(disease, "Worldwide", AdvisoryScope.GLOBAL)
            placeText != null ->
                OutbreakTopic(disease, canonicalPlace(placeText), AdvisoryScope.ABROAD)
            INDIA.containsMatchIn(item.text.take(400)) ->
                OutbreakTopic(disease, "India", AdvisoryScope.NATIONAL)
            else -> OutbreakTopic(disease ?: title, null, AdvisoryScope.GLOBAL)
        }
    }

    /**
     * The place part of a title: WHO writes "Disease - Place" or "Disease,
     * Place & Place", CDC "Level 2 - Disease in Place", ECDC "... in the Place".
     */
    internal fun placeSegment(item: AdvisoryItem): String? {
        val title = cleanTitle(item.title)
        return when (item.source) {
            AdvisorySource.WHO -> {
                val dash = Regex("\\s[-–—]\\s").findAll(title).lastOrNull()
                when {
                    dash != null -> title.substring(dash.range.last + 1)
                    title.contains(", ") -> title.substringAfterLast(", ")
                    else -> null
                }
            }
            else -> Regex("\\bin (?:the )?(.+)$", RegexOption.IGNORE_CASE).find(title)?.groupValues?.get(1)
        }?.trim()?.takeIf { it.isNotEmpty() }
    }

    internal fun canonicalPlace(segment: String): String {
        PLACE_ALIASES.firstOrNull { it.first.containsMatchIn(segment) }?.let { return it.second }
        // "A and B" or "A & B": the first place named.
        val first = segment.split(Regex("\\s+(?:and|&)\\s+|,\\s*")).first()
        return first.removePrefix("the ").trim()
    }

    /** Drops CDC's "Level N - " and ECDC's "Epidemiological update – date: " prefixes. */
    internal fun cleanTitle(raw: String): String = raw.trim()
        .replace(Regex("^Level \\d\\s*[-–—]\\s*", RegexOption.IGNORE_CASE), "")
        .replace(Regex("^Epidemiological update[^:]*:\\s*", RegexOption.IGNORE_CASE), "")
        .trim()

    /** The title a card shows: the subject, without the place (the card names that separately). */
    fun displayTitle(item: AdvisoryItem): String {
        val title = cleanTitle(item.title)
        val subject = when (item.source) {
            AdvisorySource.WHO -> {
                val dash = Regex("\\s[-–—]\\s").findAll(title).lastOrNull()
                when {
                    dash != null -> title.substring(0, dash.range.first)
                    title.contains(", ") -> title.substringBeforeLast(", ")
                    else -> title
                }
            }
            else -> title
                .replace(Regex("\\s+in (?:the )?.+$", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s+worldwide overview$", RegexOption.IGNORE_CASE), "")
                .replace(Regex("^Global\\s+", RegexOption.IGNORE_CASE), "")
        }.trim()
        return subject.replaceFirstChar { it.uppercase() }.ifEmpty { title }
    }
}

/**
 * Extractive summaries: the most informative sentence or two of a report,
 * with the boilerplate taken out and long phrases shortened. Nothing is
 * generated, so nothing can be invented; every word shown is the agency's.
 */
object AdvisorySummarizer {

    const val MAX_CHARS = 230

    private val MONTH = "(?:January|February|March|April|May|June|July|August|September|October|November|December)"

    /** Openers that carry no information of their own. */
    private val LEAD_INS = listOf(
        Regex("^(?:Since|Following) (?:the )?(?:last|previous|most recent) (?:Disease Outbreak News|DON)" +
            "(?: \\(DON\\))?(?: report| posting)?(?: (?:was |were )?(?:published|posted))?(?: on [^,]+)?,\\s*",
            RegexOption.IGNORE_CASE),
        Regex("^Summary\\s+", RegexOption.IGNORE_CASE)
    )

    private val BOILERPLATE = Regex(
        "Disease Outbreak News (?:report|posting)|^WHO (?:advises|recommends)|\\bis an? (?:viral|zoonotic|bacterial|" +
            "severe|acute|mosquito-borne|vector-borne|highly contagious)|is transmitted|is caused by|spread by infected|" +
            "Member States|International Health Regulations|\\bIHR\\b|\\bshould\\b|Every month ECDC|" +
            "epidemiological overview|weekly threat report|This issue of|travell?ers? (?:should|are advised)|" +
            "You can (?:protect|prevent)|make sure you are up to date",
        RegexOption.IGNORE_CASE
    )

    private val INFORMATIVE = Regex(
        "\\bcases?\\b|\\bdeaths?\\b|\\bdied\\b|fatal|confirmed|outbreak|spread|expanded|increase|rising|new\\b|" +
            "additional|affected|reported|hospitali[sz]ed|contacts",
        RegexOption.IGNORE_CASE
    )

    private val NUMBER_WORD = Regex(
        "\\b(?:one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|dozens?|hundreds?|thousands?)\\b",
        RegexOption.IGNORE_CASE
    )
    /** Digits that are neither a year nor the day of a date ("23 Sep"). */
    private val COUNT = Regex(
        "\\b(?!(?:19|20)\\d{2}\\b)(?!\\d{1,2} (?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\b)\\d[\\d,.]*%?"
    )

    fun summarize(text: String, maxChars: Int = MAX_CHARS, currentYear: Int? = null): String {
        val sentences = sentences(text).map { clean(it, currentYear) }.filter { it.isNotBlank() }
        if (sentences.isEmpty()) return ""
        val scored = sentences.mapIndexed { i, s -> Triple(i, s, score(s, i)) }
        val best = scored.filter { it.third > 0 }.maxByOrNull { it.third } ?: return ""

        // A second sentence only when it adds a number the first lacks and both fit.
        val bestCounts = COUNT.findAll(best.second).map { it.value }.toSet()
        val second = scored
            .filter { it.first != best.first && it.third > 0 }
            .filter { s -> COUNT.findAll(s.second).any { it.value !in bestCounts } }
            .maxByOrNull { it.third }
            ?.takeIf { best.second.length + 1 + it.second.length <= maxChars }

        val chosen = listOfNotNull(best, second).sortedBy { it.first }.joinToString(" ") { it.second }
        return fit(chosen, maxChars)
    }

    internal fun sentences(text: String): List<String> {
        val normal = text.replace(Regex("\\s+"), " ").trim()
        if (normal.isEmpty()) return emptyList()
        val parts = normal.split(Regex("(?<=[.!?])\\s+(?=[A-Z0-9\"“(])"))
        // Re-join splits made after an abbreviation.
        val out = mutableListOf<String>()
        for (p in parts) {
            val prev = out.lastOrNull()
            if (prev != null && Regex("(?:\\b(?:e\\.g|i\\.e|etc|approx|vs|St|Dr|No|Mt)|\\b[A-Z])\\.$").containsMatchIn(prev)) {
                out[out.lastIndex] = "$prev $p"
            } else out += p
        }
        return out
    }

    internal fun clean(sentence: String, currentYear: Int? = null): String {
        var s = sentence.trim()
        var changed = true
        while (changed) {
            val before = s
            LEAD_INS.forEach { s = it.replace(s, "") }
            changed = s != before
        }
        s = s
            .replace(Regex(",?\\s*(?:resulting in|corresponding to) an? (?:crude )?case fatality (?:ratio|rate)(?: \\(CFR\\))? of ([\\d.]+ ?%)",
                RegexOption.IGNORE_CASE), " (fatality ratio $1)")
            .replace(Regex("\\ba (?:cumulative )?total of |\\ba cumulative (?:of )?|\\bcumulative total of ", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\blaboratory[- ]confirmed\\b", RegexOption.IGNORE_CASE), "confirmed")
            // "confirmed one laboratory confirmed case" would now read "confirmed one confirmed case".
            .replace(Regex("\\bconfirmed (\\w+) confirmed\\b", RegexOption.IGNORE_CASE), "confirmed $1")
            .replace(Regex("(?:the )?Democratic Republic of (?:the )?Congo(?: \\(DRC\\))?", RegexOption.IGNORE_CASE), "DR Congo")
            .replace(Regex("\\s+"), " ")
            .trim()
        // Dates: "23 September 2026" -> "23 Sep"; the year only when it is not the current one.
        s = Regex("\\b(\\d{1,2}) ($MONTH)(?: (\\d{4}))?\\b").replace(s) { m ->
            val month = m.groupValues[2].take(3)
            val year = m.groupValues[3]
            if (year.isNotEmpty() && year.toIntOrNull() != currentYear) "${m.groupValues[1]} $month $year"
            else "${m.groupValues[1]} $month"
        }
        return s.replaceFirstChar { it.uppercase() }
    }

    /** Whether [s] states a count: a number that is neither a year nor a date. */
    fun hasCount(s: String): Boolean = COUNT.containsMatchIn(s)

    internal fun score(s: String, index: Int): Int {
        if (BOILERPLATE.containsMatchIn(s)) return -5
        var score = 0
        if (COUNT.containsMatchIn(s)) score += 3
        else if (NUMBER_WORD.containsMatchIn(s) && INFORMATIVE.containsMatchIn(s)) score += 2
        score += INFORMATIVE.findAll(s).count().coerceAtMost(3)
        // Reports lead with the news ("confirmed one case in Kozhikode"); the
        // detail that follows (contacts traced) rarely beats it.
        if (index == 0) score += 3 else if (index == 1) score += 1
        if (s.length < 35) score -= 2
        if (s.length > 320) score -= 2
        return score
    }

    /** Cuts at a clause break, else a word break, never mid-word. */
    internal fun fit(s: String, maxChars: Int): String {
        if (s.length <= maxChars) return s
        val window = s.take(maxChars - 1)
        val clause = maxOf(window.lastIndexOf(", "), window.lastIndexOf("; "))
        val cut = if (clause > maxChars / 2) window.substring(0, clause) else window.substringBeforeLast(' ')
        return cut.trimEnd(',', ';', ':', ' ') + "…"
    }

    /**
     * WHO's own risk verdict, e.g. "very high in DR Congo, low globally", or
     * null when the assessment states none in a recognisable form.
     */
    fun riskNote(assessment: String, place: String?): String? {
        val text = assessment.replace(Regex("\\s+"), " ")
        val level = "(very high|high|moderate|low)"
        var local: String? = null
        var global: String? = null

        // "The risk in the Democratic Republic of the Congo was assessed as very high"
        Regex("\\brisks? (?:in|for|within) (?:the )?([A-Z][^.,;]{2,60}?) (?:was|is|remains|has been)(?: again| still)? (?:re)?assessed (?:as |to be )?$level",
            RegexOption.IGNORE_CASE).findAll(text).forEach { m ->
            val where = m.groupValues[1]
            if (local == null && where.split(' ').size <= 6 && !where.contains("countries", true) &&
                (place == null || OutbreakTopics.canonicalPlace(where) == place)) {
                local = "${m.groupValues[2].lowercase()} in ${OutbreakTopics.canonicalPlace(where)}"
            }
        }
        // "very high at the national level ..., low at the global level"
        Regex("$level at the (national|global) level", RegexOption.IGNORE_CASE).findAll(text).forEach { m ->
            val lv = m.groupValues[1].lowercase()
            if (m.groupValues[2].equals("national", true)) { if (local == null) local = "$lv nationally" }
            else if (global == null) global = "$lv globally"
        }
        // "At the regional and global levels, the risk remains low"
        Regex("at the (?:regional and )?global levels?,? the risk (?:remains|is|was)(?: assessed as)? $level", RegexOption.IGNORE_CASE)
            .find(text)?.let { if (global == null) global = "${it.groupValues[1].lowercase()} globally" }
        // "... at the global level was again assessed as low", "risk to the global population ... as low"
        Regex("global (?:level|population)[^.]{0,60}?\\b(?:as|to be) $level", RegexOption.IGNORE_CASE)
            .find(text)?.let { if (global == null) global = "${it.groupValues[1].lowercase()} globally" }

        val parts = listOfNotNull(local, global)
        return if (parts.isEmpty()) null else "WHO risk: " + parts.joinToString(", ") + "."
    }

    /** "3 Sep" style, for the card's date line. */
    fun shortDate(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH).format(Instant.ofEpochMilli(ms).atZone(zone))
}

/**
 * Turns every agency's recent reports into at most [MAX_CARDS] cards: one per
 * outbreak, India first, then worldwide notices, then the most serious
 * outbreaks elsewhere.
 */
object AdvisoryDigest {

    /** Reports older than this are not shown at all. */
    const val MAX_AGE_DAYS = 30
    const val MAX_CARDS = 6
    /** Outbreaks confined to another country: only the most serious few. */
    const val MAX_ABROAD = 3

    private const val DAY_MS = 24 * 60 * 60 * 1000L

    fun build(items: List<AdvisoryItem>, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<HealthAdvisory> {
        val currentYear = Instant.ofEpochMilli(nowMs).atZone(zone).year
        val fresh = items.filter { it.publishedMs in (nowMs - MAX_AGE_DAYS * DAY_MS)..(nowMs + DAY_MS) }
        val cards = fresh.groupBy { OutbreakTopics.of(it).key }.values.map { group ->
            val byDate = group.sortedByDescending { it.publishedMs }
            val latest = byDate.first()
            val topic = OutbreakTopics.of(latest)
            val who = byDate.firstOrNull { it.source == AdvisorySource.WHO }
            // The newest report that says something concrete: an agency's
            // later one-liner ("has been ongoing since May") should not hide
            // the WHO update with the case counts from three days before.
            val summary = byDate
                .filter { latest.publishedMs - it.publishedMs <= 10 * DAY_MS }
                .map { AdvisorySummarizer.summarize(it.text, currentYear = currentYear) }
                .let { s -> s.firstOrNull { AdvisorySummarizer.hasCount(it) } ?: s.firstOrNull { it.isNotEmpty() } }
                .orEmpty()
            HealthAdvisory(
                id              = latest.id,
                title           = OutbreakTopics.displayTitle(who ?: latest),
                summary         = summary,
                source          = group.map { it.source }.distinct().joinToString(", ") { it.label },
                scope           = topic.scope,
                publishedMs     = latest.publishedMs,
                url             = (who ?: latest).url,
                place           = topic.place,
                updateCount     = group.size,
                firstReportedMs = group.minOf { it.publishedMs },
                riskNote        = who?.let { AdvisorySummarizer.riskNote(it.assessment, topic.place) },
                travelLevel     = group.mapNotNull { it.travelLevel }.maxOrNull()
            )
        }
        val india  = cards.filter { it.scope == AdvisoryScope.NATIONAL }.sortedByDescending { it.publishedMs }
        val global = cards.filter { it.scope == AdvisoryScope.GLOBAL }.sortedByDescending { it.publishedMs }
        val abroad = cards.filter { it.scope == AdvisoryScope.ABROAD }
            .sortedWith(compareByDescending<HealthAdvisory> { severity(it) }.thenByDescending { it.publishedMs })
            .take(MAX_ABROAD)
        return (india + global + abroad).take(MAX_CARDS)
    }

    /** WHO's "very high" and CDC's travel levels rank outbreaks elsewhere. */
    private fun severity(a: HealthAdvisory): Int {
        val who = when {
            a.riskNote?.contains("very high") == true -> 4
            a.riskNote?.contains("high") == true -> 3
            a.riskNote?.contains("moderate") == true -> 2
            else -> 0
        }
        return maxOf(who, a.travelLevel ?: 0)
    }
}

/** Markup to plain text: tags removed, the common entities decoded, whitespace collapsed. */
fun htmlToPlainText(html: String): String = html
    .replace(Regex("<(?:br|/p|/li|/h\\d)\\b[^>]*>", RegexOption.IGNORE_CASE), " ")
    .replace(Regex("<[^>]*>"), " ")
    .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: " " }
    .replace(Regex("&#x([0-9a-fA-F]+);")) { m -> m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: " " }
    .replace("&nbsp;", " ")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&rsquo;", "’")
    .replace("&lsquo;", "‘")
    .replace("&ndash;", "–")
    .replace("&mdash;", "—")
    .replace("&amp;", "&")   // last, or an escaped entity would decode twice
    .replace(Regex("\\s+"), " ")
    .trim()
