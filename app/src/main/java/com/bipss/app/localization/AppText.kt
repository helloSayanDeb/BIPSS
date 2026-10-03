package com.bipss.app.localization

import android.content.Context
import androidx.annotation.StringRes
import java.security.MessageDigest
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext

/** Android owns the locale. No preference, network translation, or locale override. */
object AppText {
    @Volatile private var context: Context? = null
    private val labels = ConcurrentHashMap<String, Int>()
    private val canonical = ThreadLocal.withInitial { false }
    @Volatile private var savedCopy: SavedCopy? = null
    @Volatile private var phrases: List<String>? = null
    private val rendered = object : LinkedHashMap<String, String>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 256
    }

    fun initialize(context: Context) { this.context = context }

    internal fun coachPhrases(): List<String> {
        phrases?.let { return it }
        val ctx = context ?: return emptyList()
        return ctx.assets.open("localization/coach_phrases.json").bufferedReader().use {
            val data = org.json.JSONArray(it.readText())
            (0 until data.length()).map(data::getString)
        }.also { phrases = it }
    }

    /** English is also available to pure JVM domain tests, without an Android runtime. */
    fun text(@StringRes id: Int, english: String, vararg arguments: Any?): String {
        val resources = if (canonical.get()) null else context?.resources
        val template = resources?.getText(id)?.toString() ?: english
        val locale = if (canonical.get()) Locale.ROOT else resources?.configuration?.locales?.get(0) ?: Locale.getDefault()
        return CopyFormat.render(template, arguments.toList(), locale)
    }

    /** Only for explicitly identified, fixed display labels (never user-authored text). */
    fun label(english: String): String {
        if (canonical.get()) return english
        val ctx = context ?: return english
        val id = labels.getOrPut(english) {
            val hash = MessageDigest.getInstance("SHA-256").digest(english.toByteArray(Charsets.UTF_8))
                .take(6).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
            ctx.resources.getIdentifier("copy_$hash", "string", ctx.packageName)
        }
        return if (id == 0) english else ctx.getText(id).toString()
    }

    fun <T> inEnglish(block: () -> T): T {
        val before = canonical.get()
        canonical.set(true)
        return try { block() } finally { canonical.set(before) }
    }

    suspend fun <T> inEnglishSuspend(block: suspend () -> T): T =
        withContext(canonical.asContextElement(true)) { block() }

    /** For app-owned stored calculation text; call sites explicitly exclude user text. */
    fun display(english: String): String {
        val ctx = context ?: return english
        val locale = ctx.resources.configuration.locales[0]
        if (canonical.get() || locale.language == "en" || english.isBlank()) return english
        val key = locale.toLanguageTag() + ":" + english
        synchronized(rendered) {
            return rendered.getOrPut(key) {
                val renderer = savedCopy ?: SavedCopy(ctx).also { savedCopy = it }
                renderer.render(english)
            }
        }
    }
}

/** Substitution is literal: percent signs, dollar signs, names and notes are never re-parsed. */
object CopyFormat {
    private val slot = Regex("%(\\d+)\\\$s")
    fun render(template: String, arguments: List<Any?>, locale: Locale): String =
        slot.replace(template) { match ->
            val index = match.groupValues[1].toInt() - 1
            if (index !in arguments.indices) match.value else when (val value = arguments[index]) {
                is Number -> NumberFormat.getNumberInstance(locale).apply {
                    isGroupingUsed = false
                    maximumFractionDigits = 8
                }.format(value)
                null -> "null"
                else -> value.toString()
            }
        }
}
