package com.bipss.app.sos

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
                        text = "EMERGENCY SOS",
                        color = Color.Red,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        // Never "Sending" when something already guarantees nothing will be sent.
                        text = if (state.blockers.isEmpty()) "Sending Alert" else "Alert can't be sent yet",
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

                    val animatedFraction by animateFloatAsState(
                        targetValue   = state.fraction,
                        animationSpec = tween(900, easing = LinearEasing),
                        label         = "arc"
                    )
                    CircularProgressIndicator(
                        progress    = { 1f - animatedFraction },
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
                                text = "${state.secondsLeft}s",
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
                                "Your ${state.contactCount} emergency contact(s) will get an SMS with your location unless you cancel."
                            else
                                "Your ${state.contactCount} emergency contact(s) will get an SMS unless you cancel. " +
                                    "Location is off for BIPSS, so it won't say where you are.",
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
                        Text("Call 112 now", fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(
                        onClick  = onCancel,
                        modifier = Modifier.width(180.dp).height(52.dp),
                        border   = BorderStroke(2.dp, Color.Red),
                        colors   = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red),
                        shape    = RoundedCornerShape(26.dp)
                    ) {
                        Text("CANCEL", fontWeight = FontWeight.Bold, fontSize = 16.sp)
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
        SosBlocker.NO_SMS_PERMISSION -> "SMS permission is off, so SOS can't text anyone."
        SosBlocker.NO_CONTACTS       -> "No emergency contacts are saved. Add them in Settings."
        SosBlocker.NO_TELEPHONY      -> "This device can't send SMS."
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
                    TextButton(onClick = onAllowSms) { Text("Allow", color = Color.Red, fontWeight = FontWeight.Bold) }
                }
                TextButton(onClick = onOpenSettings) { Text("Settings", color = Color.Red) }
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
                Text("Sending alert…", style = MaterialTheme.typography.titleMedium, color = Color.White)
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
                        allSent              -> "Alert sent"
                        state.sentCount == 0 -> "Alert not confirmed yet"
                        else                 -> "Alert partly sent"
                    },
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold
                )
                Text(
                    buildString {
                        append("The mobile network accepted your SOS for ${state.sentCount} of ${state.totalCount} contact(s).")
                        if (state.unconfirmedNames.isNotEmpty()) append(
                            "\nNo confirmation yet for: ${state.unconfirmedNames.joinToString()}. " +
                                "It may still go out, or may have failed."
                        )
                        if (state.failedNames.isNotEmpty()) append("\nNot sent to: ${state.failedNames.joinToString()}.")
                        when {
                            !state.locationIncluded -> append("\nYour location could not be included.")
                            state.locationLastKnown -> append(
                                "\nIt included your last known location" +
                                    (state.locationAgeMinutes?.let { " (about ${SosMessageBuilder.ageLabel(it)} old)" } ?: "") +
                                    ", not a fresh fix."
                            )
                        }
                    },
                    style     = MaterialTheme.typography.bodyMedium,
                    color     = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.size(8.dp))
                OutlinedButton(onClick = { dialEmergency(context) }) { Text("Call 112") }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
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
                Text("SOS was not sent", style = MaterialTheme.typography.headlineMedium,
                     fontWeight = FontWeight.Bold, color = Color.Black)
                Text(
                    when (state.reason) {
                        SosNotSentReason.NO_SMS_PERMISSION -> "SMS permission is off, so no message could be sent. Allow SMS for BIPSS in Settings."
                        SosNotSentReason.NO_CONTACTS       -> "No emergency contacts are saved, so there was no one to text. Add contacts in Settings."
                        SosNotSentReason.NO_TELEPHONY      -> "This device can't send SMS."
                        SosNotSentReason.ALL_SENDS_FAILED  -> "Your phone could not send any of the messages" +
                            (state.detail?.let { " ($it)" } ?: "") + ". Check your signal and SIM."
                    },
                    color = Color.DarkGray, textAlign = TextAlign.Center
                )
                Button(
                    onClick = { dialEmergency(context) },
                    colors  = ButtonDefaults.buttonColors(containerColor = Color.Red, contentColor = Color.White)
                ) { Text("Call 112", fontWeight = FontWeight.Bold) }
                if (state.reason == SosNotSentReason.NO_SMS_PERMISSION) {
                    OutlinedButton(onClick = { openAppSettings(context) }) { Text("Open Settings") }
                }
                TextButton(onClick = onDismiss) { Text("Dismiss", color = Color.DarkGray) }
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
                Text("Alert Cancelled", style = MaterialTheme.typography.titleLarge)
                Text("Logged locally as a false alarm. No data sent.",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
    }
}
