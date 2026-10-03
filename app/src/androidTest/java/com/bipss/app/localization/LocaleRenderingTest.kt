package com.bipss.app.localization

import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bipss.app.R
import com.bipss.app.ai.GreetingHelper
import com.bipss.app.domain.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class LocaleRenderingTest {
    private val original = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    @Test fun resourcesAndCachedLabelsFollowEveryRequestedLanguage() {
        val previous = Locale.getDefault()
        try {
            val names = linkedMapOf("hi" to "नींद", "bn" to "ঘুম", "ta" to "தூக்கம்", "te" to "నిద్ర", "kn" to "ನಿದ್ರೆ", "gu" to "ઊંઘ")
            names.forEach { (tag, expected) ->
                val locale = Locale.forLanguageTag(tag)
                Locale.setDefault(locale)
                val config = Configuration(original.resources.configuration).apply { setLocales(LocaleList(locale)) }
                val ctx = original.createConfigurationContext(config)
                AppText.initialize(ctx)
                assertEquals(tag, expected, AppText.label("Sleep"))
                assertEquals(tag, expected, RiskScoreType.SLEEP.displayName)
                assertTrue(GreetingHelper.getGreeting("Sayan Example").endsWith("Sayan"))
                assertFalse(AppText.label("Home") == "Home")
                assertEquals("READINESS", RiskScoreType.READINESS.name)
            }
        } finally { Locale.setDefault(previous); AppText.initialize(original) }
    }

    @Test fun savedGeneratedCopyRetainsItsValuesAndUserName() {
        val previous = Locale.getDefault()
        try {
            val locale = Locale.forLanguageTag("hi-IN")
            Locale.setDefault(locale)
            AppText.initialize(original.createConfigurationContext(Configuration(original.resources.configuration).apply { setLocales(LocaleList(locale)) }))
            val source = "Your current heart rate is 72 bpm."
            val translated = AppText.display(source)
            assertNotEquals(source, translated)
            assertTrue(translated.contains("72"))
            val composed = AppText.display("Your current heart rate is 72 bpm. " + "Your blood oxygen saturation is 97%.")
            assertFalse(composed.contains("Your current heart rate"))
            assertFalse(composed.contains("Your blood oxygen saturation"))
            assertTrue(composed.contains("97"))
            assertEquals("Sayan", CopyFormat.render("%1\$s", listOf("Sayan"), locale))
            assertEquals("Sleep", AppText.inEnglish { AppText.label("Sleep") })
        } finally { Locale.setDefault(previous); AppText.initialize(original) }
    }

    @Test fun commonCoachQueriesAndNegationKeepTheirMeaning() {
        val previous = Locale.getDefault()
        try {
            for ((tag, query) in listOf("hi" to "मेरे कदमों का औसत", "bn" to "আমার পদক্ষেপের গড়")) {
                val locale = Locale.forLanguageTag(tag)
                Locale.setDefault(locale)
                AppText.initialize(original.createConfigurationContext(Configuration(original.resources.configuration).apply { setLocales(LocaleList(locale)) }))
                val canonical = CoachLanguage.canonicalize(query)
                assertTrue(canonical.contains("steps"))
                assertTrue(canonical.contains("average"))
            }
            Locale.setDefault(Locale.forLanguageTag("hi"))
            assertTrue(CoachLanguage.canonicalize("सीने में दर्द नहीं लेकिन चक्कर").contains("no chest pain"))
        } finally { Locale.setDefault(previous); AppText.initialize(original) }
    }

    @Test fun urduUsesRtlAndUnsupportedLocaleFallsBackToEnglish() {
        val urdu = original.createConfigurationContext(Configuration(original.resources.configuration).apply { setLocales(LocaleList(Locale.forLanguageTag("ur-IN"))) })
        assertEquals(android.view.View.LAYOUT_DIRECTION_RTL, urdu.resources.configuration.layoutDirection)
        assertNotEquals("Home", urdu.getString(R.string.copy_3a78695388b3))
        val unsupported = original.createConfigurationContext(Configuration(original.resources.configuration).apply { setLocales(LocaleList(Locale.JAPANESE)) })
        assertEquals("Sleep", unsupported.getString(R.string.copy_d466bcf52eb6))
    }
}
