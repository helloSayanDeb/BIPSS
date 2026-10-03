package com.bipss.app.localization

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class LocalNumbersTest {
    @Test fun indicKeyboardDigitsBecomeNumbers() {
        assertEquals("175.5", LocalNumbers.canonical("१७५.५", Locale.forLanguageTag("hi-IN")))
        assertEquals("250", LocalNumbers.canonical("২৫০", Locale.forLanguageTag("bn-IN")))
    }
    @Test fun arabicDecimalAndGroupingAreHandled() {
        assertEquals("1234.5", LocalNumbers.canonical("١٬٢٣٤٫٥", Locale.forLanguageTag("ur-IN")))
    }
    @Test fun malformedGroupingIsNotSilentlyMultiplied() {
        assertEquals("25,0", LocalNumbers.canonical("25,0", Locale.US))
        assertEquals("1250", LocalNumbers.canonical("1,250", Locale.US))
    }
    @Test fun commaDecimalLocalesPreserveFractions() {
        assertEquals("175.5", LocalNumbers.canonical("175,5", Locale.GERMAN))
    }
}
