package com.bipss.app.localization

import java.text.Normalizer
import java.util.Locale

/** Bundled phrase recognition for the deterministic Coach. No messages leave the phone. */
object CoachLanguage {
    @Volatile private var cachedLocale = ""
    @Volatile private var cachedPairs: List<Pair<String, String>> = emptyList()
    private val aliases = mapOf(
        "hi" to mapOf("सीने में दर्द नहीं" to "no chest pain", "छाती में दर्द नहीं" to "no chest pain", "मेरी" to "my", "मेरे" to "my", "मेरा" to "my", "नींद" to "sleep", "पानी" to "water", "कदम" to "steps", "कदमों" to "steps", "आज" to "today", "कल रात" to "last night", "हृदय गति" to "heart rate", "धड़कन" to "heart rate", "तुलना" to "compare", "औसत" to "average", "लक्ष्य" to "goal", "चक्कर" to "dizzy", "लेकिन" to "but", "नहीं" to "not", "मत" to "do not", "कितना पानी" to "how much water", "मिलीलीटर" to "millilitres"),
        "bn" to mapOf("বুকে ব্যথা নেই" to "no chest pain", "আমার" to "my", "ঘুম" to "sleep", "জল" to "water", "পানি" to "water", "পদক্ষেপ" to "steps", "পদক্ষেপের" to "steps", "আজ" to "today", "গতকাল" to "yesterday", "গত রাত" to "last night", "হৃদস্পন্দন" to "heart rate", "তুলনা" to "compare", "গড়" to "average", "লক্ষ্য" to "goal", "মাথা ঘুরছে" to "dizzy", "কিন্তু" to "but", "না" to "not", "নেই" to "not", "কতটা পানি" to "how much water", "কতটা জল" to "how much water"),
        "ta" to mapOf("என்" to "my", "தூக்கம்" to "sleep", "தண்ணீர்" to "water", "அடிகள்" to "steps", "இன்று" to "today", "இதயத் துடிப்பு" to "heart rate"),
        "te" to mapOf("నా" to "my", "నిద్ర" to "sleep", "నీరు" to "water", "అడుగులు" to "steps", "ఈరోజు" to "today", "గుండె వేగం" to "heart rate"),
        "kn" to mapOf("ನನ್ನ" to "my", "ನಿದ್ರೆ" to "sleep", "ನೀರು" to "water", "ಹೆಜ್ಜೆಗಳು" to "steps", "ಇಂದು" to "today", "ಹೃದಯ ಬಡಿತ" to "heart rate"),
        "gu" to mapOf("મારી" to "my", "મારા" to "my", "ઊંઘ" to "sleep", "પાણી" to "water", "પગલાં" to "steps", "આજે" to "today", "હૃદયના ધબકારા" to "heart rate")
    )

    fun canonicalize(text: String): String {
        val locale = Locale.getDefault()
        if (locale.toLanguageTag() != cachedLocale) synchronized(this) {
            if (locale.toLanguageTag() != cachedLocale) {
                val translated = AppText.coachPhrases().map { AppText.label(it) to it }
                    .filter { (local, english) -> local != english && local.isNotBlank() }
                // Ambiguous translations (for example yesterday/tomorrow) must not choose a meaning.
                val unique = translated.groupBy { it.first }.filterValues { it.map { pair -> pair.second }.distinct().size == 1 }
                    .values.map { it.first() }
                cachedPairs = unique + aliases[locale.language].orEmpty().map { it.key to it.value }
                cachedLocale = locale.toLanguageTag()
            }
        }
        return rewrite(text, cachedPairs)
    }

    internal fun rewrite(text: String, pairs: List<Pair<String, String>>): String {
        var result = Normalizer.normalize(text, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).replace('’', '\'')
        // Numeric input uses ASCII internally regardless of keyboard digit script.
        result = result.map { c -> Character.digit(c, 10).takeIf { it >= 0 }?.let { ('0'.code + it).toChar() } ?: c }.joinToString("")
        val replacements = pairs.distinctBy { it.first }.sortedByDescending { it.first.length }
        replacements.forEach { (local, english) ->
            result = Regex("(?<![\\p{L}\\p{N}\\p{M}])" + Regex.escape(local.lowercase(Locale.ROOT)) + "(?![\\p{L}\\p{N}\\p{M}])")
                .replace(result) { english }
        }
        return result.trim()
    }
}
