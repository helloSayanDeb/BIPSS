package com.bipss.app.sos

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
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
@Composable
fun SosCountdownOverlay(
    state:    SosUiState.Countdown,
    onCancel: () -> Unit
) {
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
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(48.dp))
                    Text(
                        text = "EMERGENCY SOS",
                        color = Color.Red,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Sending Alert",
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
                    Text(
                        text = "Your emergency contacts will get your location by SMS unless you cancel.",
                        textAlign = TextAlign.Center,
                        color = Color.DarkGray,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                    Spacer(Modifier.height(32.dp))
                    OutlinedButton(
                        onClick  = onCancel,
                        modifier = Modifier.width(180.dp).height(52.dp),
                        border   = BorderStroke(2.dp, Color.Red),
                        colors   = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red),
                        shape    = RoundedCornerShape(26.dp)
                    ) {
                        Text("CANCEL", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
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
    LaunchedEffect(Unit) { delay(4_000L); onDismiss() }
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
                Icon(Icons.Outlined.CheckCircle, null,
                     tint     = MaterialTheme.colorScheme.primary, modifier = Modifier.size(80.dp))
                Text("Alert Sent", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    buildString {
                        append("SOS sent to ${state.recipientCount} contact(s).")
                        if (!state.locationIncluded) append("\nLocation could not be included.")
                    },
                    style     = MaterialTheme.typography.bodyMedium,
                    color     = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.size(8.dp))
                TextButton(onClick = onDismiss) { Text("Dismiss") }
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

@Composable
fun SosErrorSnackbar(message: String, onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { delay(4_000L); onDismiss() }
    Snackbar(
        modifier      = Modifier.padding(16.dp),
        action        = { TextButton(onClick = onDismiss) { Text("Dismiss") } },
        containerColor = MaterialTheme.colorScheme.errorContainer
    ) {
        Text(message)
    }
}
