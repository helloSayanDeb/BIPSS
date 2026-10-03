package com.bipss.app.localization

import android.content.Context
import org.json.JSONArray

/** Render app-generated saved English copy. Never use this for names, notes or remote advisories. */
internal class SavedCopy(context: Context) {
    private data class Template(val source: String, val pattern: Regex, val positions: List<Int>, val weight: Int, val segment: Regex?)
    private val entries = context.assets.open("localization/templates.json").bufferedReader().use {
        val data = JSONArray(it.readText())
        (0 until data.length()).map { i -> data.getString(i) }
    }
    private val token = Regex("%(\\d+)\\\$s")
    private val templates = entries.mapNotNull { source ->
        val matches = token.findAll(source).toList()
        val weight = source.replace(token, "").length
        if (matches.isEmpty() || weight < 8) return@mapNotNull null
        var cursor = 0
        val pattern = buildString {
            append("^")
            matches.forEach { match ->
                append(Regex.escape(source.substring(cursor, match.range.first)))
                append("(.*?)")
                cursor = match.range.last + 1
            }
            append(Regex.escape(source.substring(cursor))); append("$")
        }
        val bounded = matches.first().range.first >= 8 && matches.last().range.last < source.lastIndex && weight >= 16
        Template(source, Regex(pattern, RegexOption.DOT_MATCHES_ALL), matches.map { it.groupValues[1].toInt() - 1 }, weight,
            if (bounded) Regex(pattern.removePrefix("^").removeSuffix("$"), RegexOption.DOT_MATCHES_ALL) else null)
    }.sortedByDescending { it.weight }
    private val staticParts = entries.filter { !token.containsMatchIn(it) && it.length >= 24 }.sortedByDescending { it.length }

    // Greeting prefixes are app-owned; the appended name remains literal.
    private fun localizedGreeting(value: String): String {
        for (prefix in listOf("Good morning", "Good afternoon", "Good evening", "Good night")) {
            if (value == prefix || value.startsWith("$prefix, ")) return AppText.label(prefix) + value.removePrefix(prefix)
        }
        return value
    }

    fun render(source: String): String {
        val exact = AppText.label(source)
        if (exact != source) return exact
        for (template in templates) {
            val match = template.pattern.matchEntire(source) ?: continue
            val arguments = MutableList(template.positions.max() + 1) { "" }
            template.positions.forEachIndexed { i, position -> arguments[position] = localizedGreeting(match.groupValues[i + 1]) }
            val translated = AppText.label(template.source)
            if (translated != template.source) return CopyFormat.render(translated, arguments, java.util.Locale.getDefault())
        }
        // Methods can be composed from several fixed sentences in the original source.
        var result = source
        // A briefing can combine several complete templates with their literal values.
        // Only templates with fixed text on both sides of the arguments can match a segment.
        templates.forEach { template ->
            val pattern = template.segment ?: return@forEach
            val translated = AppText.label(template.source)
            if (translated != template.source) result = pattern.replace(result) { match ->
                val arguments = MutableList(template.positions.max() + 1) { "" }
                template.positions.forEachIndexed { i, position -> arguments[position] = localizedGreeting(match.groupValues[i + 1]) }
                CopyFormat.render(translated, arguments, java.util.Locale.getDefault())
            }
        }
        staticParts.forEach { part -> if (part in result) result = result.replace(part, AppText.label(part)) }
        return result
    }
}
