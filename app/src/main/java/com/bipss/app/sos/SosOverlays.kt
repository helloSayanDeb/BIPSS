package com.bipss.app.sos

import com.bipss.app.localization.AppText
import com.bipss.app.R

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bipss.app.ui.theme.LocalDangerColor
import com.google.accompanist.permissions.isGranted
import com.bipss.app.ui.theme.SOSBackgroundLight
import kotlinx.coroutines.delay

/**
 * Emergency SOS countdown — visual design ported from BIPSS 1's dedicated
 * SOSScreen (pulsing red rings around a circular progress indicator, fixed
 * light-pink background regardless of app theme, for maximum legibility in
 * an emergency), wired to BIPSS 3's real state machine: [state] is a live
 * countdown that actually dispatches location + SMS to emergency contacts
 * when it reaches zero (see [com.bipss.app.sos.SosViewModel]), not a mock.
 */
@OptIn(com.google.accompanist.permissions.ExperimentalPermissionsApi::class)
@Composable
fun SosCountdownOverlay(
    state:    SosUiState.Countdown,
    onCancel: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var remainingMs by remember(state.deadlineElapsedMs) {
        mutableLongStateOf((state.deadlineElapsedMs - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L))
    }
    LaunchedEffect(state.deadlineElapsedMs) {
        while (true) {
            withFrameNanos { remainingMs = (state.deadlineElapsedMs - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L) }
            if (remainingMs == 0L) break
        }
    }
    val smsPermission = com.google.accompanist.permissions.rememberPermissionState(android.Manifest.permission.SEND_SMS)
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress      = true,
            dismissOnClickOutside   = false
        )
    ) {
        Box(
            modifier         = Modifier.fillMaxSize().background(SOSBackgroundLight),
            contentAlignment = Alignment.Center
        ) {
            // Everything but the two buttons scrolls, so blocker rows on a short
            // screen can never push Cancel (or Call 112) out of reach.
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
              Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
              ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = AppText.text(R.string.copy_5b180b0c4425, "EMERGENCY SOS"),
                        color = Color.Red,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        // Never "Sending" when something already guarantees nothing will be sent.
                        text = if (state.blockers.isEmpty()) AppText.text(R.string.copy_5c91d9af8924, "Sending Alert") else AppText.text(R.string.copy_ea56f842a92f, "Alert can't be sent yet"),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.Black,
                        textAlign = TextAlign.Center
                    )
                }

                Box(modifier = Modifier.size(280.dp), contentAlignment = Alignment.Center) {
                    val pulseAlpha by rememberInfiniteTransition(label = "pulse")
                        .animateFloat(
                            initialValue = 0.05f, targetValue = 0.12f,
                            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
                            label = "sos_pulse"
                        )
                    repeat(2) { i ->
                        Box(
                            modifier = Modifier
                                .size(240.dp + (i * 30).dp)
                                .background(Color.Red.copy(alpha = pulseAlpha), CircleShape)
                        )
                    }

                    CircularProgressIndicator(
                        progress    = { CountdownClock.progress(remainingMs, state.totalSeconds * 1000L) },
                        modifier    = Modifier.size(220.dp),
                        color       = Color.Red,
                        strokeWidth = 8.dp,
                        trackColor  = Color.Transparent
                    )

                    Surface(
                        modifier = Modifier.size(180.dp),
                        shape = CircleShape,
                        color = Color.Red,
                        shadowElevation = 8.dp
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.LocalHospital,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "${CountdownClock.secondsLeft(remainingMs)}s",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 28.sp
                            )
                        }
                    }
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (state.blockers.isEmpty()) {
                        Text(
                            text = if (state.locationAllowed)
                                AppText.text(R.string.copy_5fe850e6aa24, "Your %1\$s emergency contact(s) will get an SMS with your location unless you cancel. ", state.contactCount) +
                                    AppText.text(R.string.copy_7e82b5fe5649, "Nearby hospitals that list a mobile number will be texted too.")
                            else
                                AppText.text(R.string.copy_b40d10b3e191, "Your %1\$s emergency contact(s) will get an SMS unless you cancel. ", state.contactCount) +
                                    AppText.text(R.string.copy_13711b85c220, "Location is off for BIPSS, so it won't say where you are."),
                            textAlign = TextAlign.Center,
                            color = Color.DarkGray,
                            fontSize = 16.sp,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    } else {
                        // Shown live: fixing a blocker mid-countdown clears it on the next tick.
                        state.blockers.forEach { blocker ->
                            SosBlockerRow(
                                blocker = blocker,
                                canRequestSms = !smsPermission.status.isGranted,
                                onAllowSms = { smsPermission.launchPermissionRequest() },
                                onOpenSettings = { openAppSettings(context) }
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
              }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick  = { dialEmergency(context) },
                        modifier = Modifier.height(44.dp),
                        border   = BorderStroke(1.dp, Color.DarkGray),
                        colors   = ButtonDefaults.outlinedButtonColors(contentColor = Color.DarkGray),
                        shape    = RoundedCornerShape(22.dp)
                    ) {
                        Text(AppText.text(R.string.copy_908a026e7b30, "Call 112 now"), fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(
                        onClick  = onCancel,
                        modifier = Modifier.width(180.dp).height(52.dp),
                        border   = BorderStroke(2.dp, Color.Red),
                        colors   = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red),
                        shape    = RoundedCornerShape(26.dp)
                    ) {
                        Text(AppText.text(R.string.copy_ad8d698573fc, "CANCEL"), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun SosBlockerRow(
    blocker: SosBlocker,
    canRequestSms: Boolean,
    onAllowSms: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val text = when (blocker) {
        SosBlocker.NO_SMS_PERMISSION -> AppText.text(R.string.copy_8bba5191698f, "SMS permission is off, so SOS can't text anyone.")
        SosBlocker.NO_CONTACTS       -> AppText.text(R.string.copy_cb0387c67a4f, "No emergency contacts are saved. Add them in Settings.")
        SosBlocker.NO_TELEPHONY      -> AppText.text(R.string.copy_0bd985eca829, "This device can't send SMS.")
    }
    Surface(
        color = Color(0xFFFFE0E2),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = Color(0xFF8A1C23), fontSize = 14.sp, modifier = Modifier.weight(1f))
            if (blocker == SosBlocker.NO_SMS_PERMISSION) {
                // Android stops showing the dialog after repeated denials, so
                // Settings is always offered alongside the in-place request.
                if (canRequestSms) {
                    TextButton(onClick = onAllowSms) { Text(AppText.text(R.string.copy_e213c161d5ce, "Allow"), color = Color.Red, fontWeight = FontWeight.Bold) }
                }
                TextButton(onClick = onOpenSettings) { Text(AppText.text(R.string.copy_74a883a037bc, "Settings"), color = Color.Red) }
            }
        }
    }
}

/** Opens the dialer with 112 filled in. ACTION_DIAL needs no permission and never places the call by itself. */
fun dialEmergency(context: android.content.Context) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:112"))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

fun openAppSettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.fromParts("package", context.packageName, null))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@Composable
fun SosSendingSpinner() {
    val dangerColor = LocalDangerColor.current
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false)
    ) {
        Box(
            modifier         = Modifier.fillMaxSize().background(dangerColor.copy(alpha = 0.92f)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                   verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 4.dp)
                Text(AppText.text(R.string.copy_c50a9462460b, "Sending alert…"), style = MaterialTheme.typography.titleMedium, color = Color.White)
            }
        }
    }
}

@Composable
fun SosSentOverlay(state: SosUiState.Sent, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier         = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                   verticalArrangement = Arrangement.spacedBy(16.dp),
                   modifier = Modifier.padding(32.dp)) {
                val allSent = state.sentCount == state.totalCount
                Icon(if (state.sentCount > 0) Icons.Outlined.CheckCircle else Icons.Outlined.Schedule, null,
                     tint     = MaterialTheme.colorScheme.primary, modifier = Modifier.size(80.dp))
                Text(
                    when {
                        allSent              -> AppText.text(R.string.copy_4de6356a6c25, "Alert sent")
                        state.sentCount == 0 -> AppText.text(R.string.copy_4736ca7e29ea, "Alert not confirmed yet")
                        else                 -> AppText.text(R.string.copy_aa5ecd14aebf, "Alert partly sent")
                    },
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold
                )
                Text(
                    buildString {
                        append(AppText.text(R.string.copy_167aca10d29a, "The mobile network accepted your SOS for %1\$s of %2\$s contact(s).", state.sentCount, state.totalCount))
                        if (state.unconfirmedNames.isNotEmpty()) append(
                            AppText.text(R.string.copy_2370b12e5205, "\nNo confirmation yet for: %1\$s. ", state.unconfirmedNames.joinToString()) +
                                AppText.text(R.string.copy_194b58b42787, "It may still go out, or may have failed.")
                        )
                        if (state.failedNames.isNotEmpty()) append(AppText.text(R.string.copy_e9b138b31e02, "\nNot sent to: %1\$s.", state.failedNames.joinToString()))
                        if (state.hospitalLines.isNotEmpty()) {
                            append(AppText.text(R.string.copy_8790eef30685, "\n\nNearby hospitals:"))
                            state.hospitalLines.forEach { append("\n").append(it) }
                            append("\n")
                        }
                        when {
                            !state.locationIncluded -> append(AppText.text(R.string.copy_d449756243f8, "\nYour location could not be included."))
                            state.locationLastKnown -> append(
                                AppText.text(R.string.copy_f888191191bf, "\nIt included your last known location") +
                                    (state.locationAgeMinutes?.let { AppText.text(R.string.copy_ee03ff7fc13c, " (about %1\$s old)", SosMessageBuilder.ageLabel(it)) } ?: "") +
                                    AppText.text(R.string.copy_a9c8c29c1c06, ", not a fresh fix.")
                            )
                        }
                    },
                    style     = MaterialTheme.typography.bodyMedium,
                    color     = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.size(8.dp))
                OutlinedButton(onClick = { dialEmergency(context) }) { Text(AppText.text(R.string.copy_087479cd31cd, "Call 112")) }
                TextButton(onClick = onDismiss) { Text(AppText.text(R.string.copy_48845bff334a, "Dismiss")) }
            }
        }
    }
}

/** Nothing left the phone. Deliberately not styled like success. */
@Composable
fun SosNotSentOverlay(state: SosUiState.NotSent, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier         = Modifier.fillMaxSize().background(SOSBackgroundLight),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                   verticalArrangement = Arrangement.spacedBy(16.dp),
                   modifier = Modifier.padding(32.dp)) {
                Icon(Icons.Outlined.Cancel, null, tint = Color.Red, modifier = Modifier.size(72.dp))
                Text(AppText.text(R.string.copy_d3afc9f89a75, "SOS was not sent"), style = MaterialTheme.typography.headlineMedium,
                     fontWeight = FontWeight.Bold, color = Color.Black)
                Text(
                    when (state.reason) {
                        SosNotSentReason.NO_SMS_PERMISSION -> AppText.text(R.string.copy_2d54a8f04540, "SMS permission is off, so no message could be sent. Allow SMS for BIPSS in Settings.")
                        SosNotSentReason.NO_CONTACTS       -> AppText.text(R.string.copy_7faa485cdb7a, "No emergency contacts are saved, so there was no one to text. Add contacts in Settings.")
                        SosNotSentReason.NO_TELEPHONY      -> AppText.text(R.string.copy_0bd985eca829, "This device can't send SMS.")
                        SosNotSentReason.ALL_SENDS_FAILED  -> AppText.text(R.string.copy_ce6552405c16, "Your phone could not send any of the messages") +
                            (state.detail?.let { " ($it)" } ?: "") + AppText.text(R.string.copy_c237a9bb137c, ". Check your signal and SIM.")
                    },
                    color = Color.DarkGray, textAlign = TextAlign.Center
                )
                Button(
                    onClick = { dialEmergency(context) },
                    colors  = ButtonDefaults.buttonColors(containerColor = Color.Red, contentColor = Color.White)
                ) { Text(AppText.text(R.string.copy_087479cd31cd, "Call 112"), fontWeight = FontWeight.Bold) }
                if (state.reason == SosNotSentReason.NO_SMS_PERMISSION) {
                    OutlinedButton(onClick = { openAppSettings(context) }) { Text(AppText.text(R.string.copy_3f940108cb1e, "Open Settings")) }
                }
                TextButton(onClick = onDismiss) { Text(AppText.text(R.string.copy_48845bff334a, "Dismiss"), color = Color.DarkGray) }
            }
        }
    }
}

@Composable
fun SosFalseAlarmOverlay(onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { delay(2_500L); onDismiss() }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier         = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                   verticalArrangement = Arrangement.spacedBy(12.dp),
                   modifier = Modifier.padding(32.dp)) {
                Icon(Icons.Outlined.Cancel, null,
                     tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(64.dp))
                Text(AppText.text(R.string.copy_4aaf751ef2cb, "Alert Cancelled"), style = MaterialTheme.typography.titleLarge)
                Text(AppText.text(R.string.copy_3399405c81e0, "Logged locally as a false alarm. No data sent."),
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
    }
}
