package com.bipss.app.data.environmental

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CpcbUrlBuilderTest {

    @Test
    fun `state filter carries a real space as %20, never a plus`() {
        val url = CpcbUrlBuilder.build("k", "West Bengal", 500)
        assertTrue(url, url.endsWith("&filters[state]=West%20Bengal"))
        assertFalse(url.contains("West+Bengal"))
    }

    @Test
    fun `no state means no filter`() {
        assertFalse(CpcbUrlBuilder.build("k", null, 500).contains("filters"))
        assertFalse(CpcbUrlBuilder.build("k", "  ", 500).contains("filters"))
    }

    @Test
    fun `state is trimmed and other characters are percent-encoded`() {
        assertTrue(CpcbUrlBuilder.build("k", " Jammu & Kashmir ", 10).endsWith("&filters[state]=Jammu%20%26%20Kashmir"))
    }

    @Test
    fun `limit and format are set`() {
        val url = CpcbUrlBuilder.build("k", null, 250)
        assertTrue(url.contains("&format=json&limit=250"))
        assertEquals("https://api.data.gov.in/resource/${CpcbUrlBuilder.RESOURCE}?api-key=k&format=json&limit=250", url)
    }
}
