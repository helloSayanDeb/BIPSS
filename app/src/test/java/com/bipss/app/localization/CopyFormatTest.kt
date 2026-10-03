package com.bipss.app.localization

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class CopyFormatTest {
    @Test fun translatedWordOrderPreservesNamesAndNumbers() {
        assertEquals("250 mL · Sayan", CopyFormat.render("%2\$s mL · %1\$s", listOf("Sayan", 250), Locale.US))
    }
    @Test fun userTextIsNotInterpretedAsAnotherTemplate() {
        assertEquals("Hello %2\$s $ 100%", CopyFormat.render("Hello %1\$s 100%", listOf("%2\$s $"), Locale.US))
    }
    @Test fun unknownSlotDoesNotCrash() {
        assertEquals("Score %2\$s", CopyFormat.render("Score %2\$s", listOf(12), Locale.US))
    }
}
