package com.bipss.app.ai

import org.junit.Assert.*
import org.junit.Test

class ModelManifestTest {
    @Test fun `manifest requires bounded size safe version HTTPS and checksum`() {
        val good = ModelManifest("1.0", "https://example.com/model.bin", "a".repeat(64), 1000)
        assertTrue(good.isValid())
        assertFalse(good.copy(version = "../../other").isValid())
        assertFalse(good.copy(sizeBytes = 0).isValid())
        assertFalse(good.copy(sizeBytes = Long.MAX_VALUE).isValid())
        assertFalse(good.copy(downloadUrl = "http://example.com/model.bin").isValid())
        assertFalse(good.copy(sha256 = "bad").isValid())
    }
}
