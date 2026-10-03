package com.bipss.app.ui.settings

import com.bipss.app.localization.AppText
import com.bipss.app.R

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bipss.app.sos.HospitalLocator
import com.bipss.app.sos.HospitalLookup
import com.bipss.app.sos.LocationHelper
import com.bipss.app.ui.components.BipssCard
import com.bipss.app.ui.theme.Space
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

data class NearbyHospitalState(val busy: Boolean = false, val message: String? = null,
    val result: HospitalLookup? = null)
class NearbyHospitalsViewModel(private val location: LocationHelper, private val hospitals: HospitalLocator) : ViewModel() {
    val state = MutableStateFlow(NearbyHospitalState())
    fun hasPermission() = location.hasAnyPermission()
    fun denied() { state.value = NearbyHospitalState(message = AppText.text(R.string.copy_d67be69b101b, "Allow location to find hospitals near you.")) }
    fun find() {
        if (state.value.busy) return
        viewModelScope.launch {
            state.value = NearbyHospitalState(busy = true)
            try {
                val fix = location.getCurrentFix(15_000)
                if (fix == null || System.currentTimeMillis() - fix.timeMs !in 0..120_000) {
                    state.value = NearbyHospitalState(message = AppText.text(R.string.copy_47f2bd8c40ce, "Couldn't get a current location. Turn on Location and try again."))
                    return@launch
                }
                val result = hospitals.nearest(fix.lat, fix.lon)
                val message = when (result.status) {
                    HospitalLookup.Status.FOUND -> (if (fix.isApproximate) AppText.text(R.string.copy_8e36121cff1a, "Using your approximate location. ") else "") +
                        AppText.text(R.string.copy_58bde11b745c, "Nearest listed contacts within 15 km. Distances are straight-line estimates.")
                    HospitalLookup.Status.NONE_WITH_NUMBER -> AppText.text(R.string.copy_dc48323beda6, "%1\$s hospitals are mapped nearby, but none lists a usable phone number.", result.found)
                    HospitalLookup.Status.NONE_NEARBY -> AppText.text(R.string.copy_ded2953d967c, "No hospitals are mapped within 15 km.")
                    HospitalLookup.Status.FAILED -> AppText.text(R.string.copy_def266e12736, "The hospital map service couldn't be reached. Try again when connected.")
                }
                state.value = NearbyHospitalState(message = message, result = result)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { state.value = NearbyHospitalState(message = AppText.text(R.string.copy_9cabbf77fcd9, "Couldn't look up nearby hospitals. Please try again.")) }
        }
    }
}

@Composable
fun NearbyHospitalsCard(vm: NearbyHospitalsViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (vm.hasPermission()) vm.find() else vm.denied()
    }
    BipssCard {
        Text(AppText.text(R.string.copy_59452dc9627b, "Nearby hospitals"), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Space.s))
        Text(AppText.text(R.string.copy_f5a371dab09f, "Preview the hospitals SOS would text. Nothing is sent."), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(Space.m))
        OutlinedButton(onClick = { if (vm.hasPermission()) vm.find() else launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }, enabled = !state.busy) {
            Text(if (state.busy) AppText.text(R.string.copy_74b458e8d162, "Finding hospitals…") else AppText.text(R.string.copy_633d8edc3a0d, "Find nearby hospitals"))
        }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = Space.s)) }
        state.result?.hospitals?.forEach { h ->
            Spacer(Modifier.height(Space.m))
            Text(h.name, style = MaterialTheme.typography.titleSmall)
            Text(AppText.text(R.string.copy_69ba18ba472c, "%.1f km · %s").format(h.distanceKm, h.callNumber ?: AppText.text(R.string.copy_2d463a039c6e, "No number")), style = MaterialTheme.typography.bodyMedium)
            Text(if (h.mobile != null) AppText.text(R.string.copy_5fc6e6b3d39b, "Mobile listed; SMS receipt isn't guaranteed.") else AppText.text(R.string.copy_606d70c0a556, "Listed for your contacts to call; no mobile for SMS."), style = MaterialTheme.typography.bodySmall)
        }
        if (state.result?.hospitals?.isNotEmpty() == true) Text(AppText.text(R.string.copy_f2a867bfcd8a, "A map listing does not confirm emergency availability or dispatch help."), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = Space.m))
    }
}
