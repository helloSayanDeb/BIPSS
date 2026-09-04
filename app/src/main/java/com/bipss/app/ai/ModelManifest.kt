package com.bipss.app.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Shape of manifest.json hosted at [MODEL_MANIFEST_URL].
 *
 * TODO: Replace with your real GitHub release/raw URL once the fine-tuned
 *       model is published, e.g.:
 *       "https://raw.githubusercontent.com/<org>/<repo>/main/manifest.json"
 *       or a GitHub Releases asset URL. The manifest itself must be JSON
 *       shaped like [ModelManifest] below (version/url/sha256/size_bytes) —
 *       ModelDownloadManager already does the fetch + streamed download +
 *       SHA-256 verification against whatever this constant points to.
 */
const val MODEL_MANIFEST_URL = "https://raw.githubusercontent.com/REPLACE_ME/REPLACE_ME/main/manifest.json" // TODO: real GitHub URL

@Serializable
data class ModelManifest(
    val version:    String,
    @SerialName("url")    val downloadUrl: String,
    @SerialName("sha256") val sha256:      String,
    @SerialName("size_bytes") val sizeBytes: Long = 262_144_000L
)

const val MODEL_EXPECTED_SIZE_MB = 250
