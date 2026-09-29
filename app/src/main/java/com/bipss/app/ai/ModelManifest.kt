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
/**
 * URL to a JSON file declaring the current model version, its SHA-256 hash, and its download URL.
 * The app fetches this to decide whether an on-device update is needed.
 */
/**
 * From local.properties (MODEL_MANIFEST_URL) via BuildConfig. Empty in a build
 * without a published model - the previous hard-coded address pointed at a
 * repository that does not exist.
 */
val MODEL_MANIFEST_URL: String get() = com.bipss.app.BuildConfig.MODEL_MANIFEST_URL

/** Whether this build offers an on-device model at all. */
val isModelDownloadConfigured: Boolean get() = MODEL_MANIFEST_URL.isNotBlank()

@Serializable
data class ModelManifest(
    val version:    String,
    @SerialName("url")    val downloadUrl: String,
    @SerialName("sha256") val sha256:      String,
    // No default here on purpose: a manifest that omits size_bytes fails to
    // parse (fetchManifest() catches it and reports "unavailable") rather
    // than silently substituting a guessed number.
    @SerialName("size_bytes") val sizeBytes: Long
)
