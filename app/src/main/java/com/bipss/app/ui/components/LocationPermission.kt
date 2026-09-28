package com.bipss.app.ui.components

import android.Manifest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.shouldShowRationale

/** Location permission as the app uses it: precise *or* approximate counts as granted. */
class LocationPermissionHandle internal constructor(
    val isGranted: Boolean,
    val isPreciseGranted: Boolean,
    /**
     * True only after this screen has asked and Android will no longer show
     * the dialog - the one case where the answer is "open Settings". Before any
     * request, "not granted and no rationale" is the normal first-run state,
     * not a permanent denial.
     */
    val isPermanentlyDenied: Boolean,
    val request: () -> Unit
)

/**
 * Requests FINE and COARSE together. Asking for FINE alone is ignored by
 * Android 12+ for apps targeting API 31+, and a user who picks "approximate"
 * holds COARSE only - both previously left location looking denied.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun rememberLocationPermission(onResult: (granted: Boolean) -> Unit = {}): LocationPermissionHandle {
    var requested by rememberSaveable { mutableStateOf(false) }
    val state = rememberMultiplePermissionsState(
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    ) { results -> onResult(results.values.any { it }) }

    val granted = state.permissions.any { it.status.isGranted }
    val precise = state.permissions.any { it.permission == Manifest.permission.ACCESS_FINE_LOCATION && it.status.isGranted }
    val anyRationale = state.permissions.any { it.status.shouldShowRationale }
    return LocationPermissionHandle(
        isGranted           = granted,
        isPreciseGranted    = precise,
        isPermanentlyDenied = requested && !granted && !anyRationale,
        request             = { requested = true; state.launchMultiplePermissionRequest() }
    )
}
