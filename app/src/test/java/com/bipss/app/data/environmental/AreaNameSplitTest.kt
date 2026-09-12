package com.bipss.app.data.environmental

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the repair applied to Sachet's malformed area_description field.
 *
 * The cases below are taken from the live feed on 8 Sep 2026. The risk being
 * tested for is over-eagerness: a heuristic that splits ordinary place names
 * would be worse than the corruption it fixes.
 */
class AreaNameSplitTest {

    @Test
    fun `run-together district names are separated`() {
        assertEquals(
            listOf("Kolkata", "South 24 Parganas", "North 24 Parganas"),
            splitRunTogetherAreaNames("KolkataSouth 24 ParganasNorth 24 Parganas")
        )
    }

    @Test
    fun `an ordinary phrase is left alone`() {
        assertEquals(
            listOf("Kolkata district of West Bengal"),
            splitRunTogetherAreaNames("Kolkata district of West Bengal")
        )
    }

    @Test
    fun `a single district is left alone`() {
        assertEquals(listOf("South 24 Parganas"), splitRunTogetherAreaNames("South 24 Parganas"))
    }

    @Test
    fun `a properly separated name survives unchanged`() {
        assertEquals(listOf("West Bengal"), splitRunTogetherAreaNames("West Bengal"))
    }

    @Test
    fun `an all-caps name is not shredded`() {
        assertEquals(listOf("NCT OF DELHI"), splitRunTogetherAreaNames("NCT OF DELHI"))
    }

    @Test
    fun `blank input yields nothing`() {
        assertEquals(emptyList<String>(), splitRunTogetherAreaNames("   "))
    }
}
