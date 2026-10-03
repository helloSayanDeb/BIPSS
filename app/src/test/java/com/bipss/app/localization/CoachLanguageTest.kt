package com.bipss.app.localization

import org.junit.Assert.assertEquals
import org.junit.Test

class CoachLanguageTest {
    @Test fun longestPhrasePreservesNegationBeforeShorterKeywords() {
        val pairs = listOf("सीने में दर्द नहीं" to "no chest pain", "सीने में दर्द" to "chest pain", "लेकिन" to "but", "चक्कर" to "dizzy")
        assertEquals("no chest pain but dizzy", CoachLanguage.rewrite("सीने में दर्द नहीं लेकिन चक्कर", pairs))
    }
    @Test fun keywordsDoNotRewritePartsOfOtherWords() {
        assertEquals("পানীয় water", CoachLanguage.rewrite("পানীয় পানি", listOf("পানি" to "water")))
    }
    @Test fun keyboardDigitsAreAcceptedWithoutChangingUnknownWords() {
        assertEquals("log 250 ml water", CoachLanguage.rewrite("log ২৫০ ml water", emptyList()))
    }
}
