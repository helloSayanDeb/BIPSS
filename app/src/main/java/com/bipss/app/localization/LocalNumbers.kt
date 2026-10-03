package com.bipss.app.localization

import java.text.DecimalFormatSymbols
import java.util.Locale

/** Keyboard input and saved numeric display strings can contain Indic or Arabic digits. */
object LocalNumbers {
    fun canonical(text: String, locale: Locale = Locale.getDefault()): String {
        val symbols = DecimalFormatSymbols.getInstance(locale)
        val hasLocaleDecimal = text.indexOf(symbols.decimalSeparator) >= 0
        val ascii = text.trim().map { c -> Character.digit(c, 10).takeIf { it >= 0 }?.let { ('0'.code + it).toChar() } ?: c }.joinToString("")
        val separator = Regex.escape(symbols.groupingSeparator.toString())
        val groupingValid = Regex("^[+-]?[0-9]{1,3}(?:" + separator + "[0-9]{2,3})*" + separator + "[0-9]{3}(?:" + Regex.escape(symbols.decimalSeparator.toString()) + "[0-9]+)?$").matches(ascii)
        return text.trim().mapNotNull { c ->
            val digit = Character.digit(c, 10)
            when {
                digit >= 0 -> ('0'.code + digit).toChar()
                c == symbols.decimalSeparator || c == '\u066b' -> '.'
                c == '.' && !hasLocaleDecimal -> '.' // Canonical persisted decimals stay readable.
                c == symbols.groupingSeparator && groupingValid || c == '\u066c' || c == '\u202f' || c == '\u00a0' -> null
                else -> c
            }
        }.joinToString("")
    }
    fun intOrNull(text: String) = canonical(text).toIntOrNull()
    fun floatOrNull(text: String) = canonical(text).toFloatOrNull()
    fun doubleOrNull(text: String) = canonical(text).toDoubleOrNull()
}
