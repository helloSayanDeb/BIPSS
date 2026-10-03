package com.bipss.app.ui.activity

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.bipss.app.R
import com.bipss.app.data.MetricTypes
import com.bipss.app.data.db.AppDatabase
import com.bipss.app.data.db.entity.RawReadingEntity
import com.bipss.app.localization.AppText
import com.bipss.app.ui.components.BipssCard
import com.bipss.app.ui.theme.BipssTheme
import com.bipss.app.ui.theme.Space
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * While a walk or run is being recorded and this screen is open, the phone's
 * GPS speed is stored alongside the band's heart rate: with it the VO2 max
 * estimate can use a measured speed (and so can use runs, which step cadence
 * cannot give). Precise location only, only on this screen, nothing leaves
 * the phone.
 */
@SuppressLint("MissingPermission")
@Composable
fun WorkoutGps(type: String, db: AppDatabase = koinInject()) {
    if (type != "Walk" && type != "Run") return
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    DisposableEffect(granted) {
        if (!granted) return@DisposableEffect onDispose { }
        val client = LocationServices.getFusedLocationProviderClient(context)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L).setMinUpdateIntervalMillis(3_000L).build()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.filter { it.hasSpeed() && it.accuracy <= 30f }.forEach { l ->
                    scope.launch {
                        db.rawReadingDao().insert(RawReadingEntity(metricType = MetricTypes.GPS_SPEED, value = l.speed.toDouble(), timestampMs = l.time))
                    }
                }
            }
        }
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        onDispose { client.removeLocationUpdates(callback); scope.cancel() }
    }
    BipssCard {
        Text(AppText.text(R.string.copy_2981b2aa998a, "GPS speed"), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Space.xs))
        if (granted) {
            Text(AppText.text(R.string.copy_f3e212fd58b4, "Recording your speed while this screen is open, for a better VO₂ max estimate."),
                style = MaterialTheme.typography.bodySmall, color = BipssTheme.colors.textSecondary)
        } else {
            Text(AppText.text(R.string.copy_076886bfb3ed, "Add your phone's GPS speed for a better VO₂ max estimate."),
                style = MaterialTheme.typography.bodySmall, color = BipssTheme.colors.textSecondary)
            TextButton(onClick = { launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) {
                Text(AppText.text(R.string.copy_30f69a08e7e5, "Allow precise location"))
            }
        }
    }
}
