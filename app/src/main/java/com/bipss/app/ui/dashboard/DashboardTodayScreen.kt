package com.bipss.app.ui.dashboard

import com.bipss.app.localization.AppText
import com.bipss.app.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.bipss.app.data.MetricTypes
import com.bipss.app.domain.*
import com.bipss.app.navigation.LocalScaffoldPadding
import com.bipss.app.navigation.Routes
import com.bipss.app.sos.*
import com.bipss.app.ui.activity.ActivityViewModel
import com.bipss.app.ui.components.*
import com.bipss.app.ui.theme.*
import org.koin.androidx.compose.koinViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun DashboardTodayScreen(navController: NavController, vm: DashboardViewModel = koinViewModel(),
    sosVm: SosViewModel = koinViewModel(), activityVm: ActivityViewModel = koinViewModel(), dayVm: DayOverviewViewModel = koinViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val daily by activityVm.ui.collectAsStateWithLifecycle()
    val sosState by sosVm.state.collectAsStateWithLifecycle()
    val readiness by sosVm.readiness.collectAsStateWithLifecycle()
    val day by dayVm.ui.collectAsStateWithLifecycle()
    val colors = BipssTheme.colors
    val location = rememberLocationPermission()
    LaunchedEffect(location.isGranted) { if (location.isGranted && state.env.latitude == null) vm.refreshEnvironment() }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        sosVm.refreshReadiness()
        onPauseOrDispose { }
    }
    when (val s = sosState) {
        is SosUiState.Countdown -> SosCountdownOverlay(s) { sosVm.cancelSos() }
        is SosUiState.Sending -> SosSendingSpinner()
        is SosUiState.Sent -> SosSentOverlay(s) { sosVm.dismissResult() }
        is SosUiState.NotSent -> SosNotSentOverlay(s) { sosVm.dismissResult() }
        is SosUiState.FalseAlarm -> SosFalseAlarmOverlay { sosVm.dismissResult() }
        SosUiState.Idle -> Unit
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(LocalScaffoldPadding.current)
            .padding(horizontal = Space.gutter), verticalArrangement = Arrangement.spacedBy(Space.l)) {
            Spacer(Modifier.height(Space.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(LocalDate.ofEpochDay(day.day).format(DateTimeFormatter.ofPattern("EEEE, d MMMM")),
                        style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                    Spacer(Modifier.height(Space.s))
                    Text(com.bipss.app.ai.GreetingHelper.getGreeting(daily.profile?.name),
                        style = MaterialTheme.typography.headlineSmall, color = colors.textPrimary)
                }
                IconButton(onClick = { navController.navigate(Routes.ONBOARDING_CONNECT_BAND_REPAIR) }) {
                    Icon(Icons.Rounded.Watch, if (state.hasBand) AppText.text(R.string.copy_8051c4b4da21, "Band connected") else AppText.text(R.string.copy_ebc121880aa4, "Connect your band"),
                        tint = if (state.hasBand) colors.bandNormal else colors.textSecondary)
                }
                state.batteryPercent?.let { Text("$it%", style = MaterialTheme.typography.labelSmall) }
                Spacer(Modifier.width(Space.s))
                FilledTonalButton(onClick = { sosVm.triggerSos(SosTriggerSource.MANUAL) },
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = SOSRed, contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = Space.m), modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("SOS", style = MaterialTheme.typography.labelLarge)
                }
            }
            DashboardDateSelector(day.day, dayVm::select)
            val health = if (day.isToday) com.bipss.app.data.DayHealth(
                state.riskScores.firstOrNull { it.type == RiskScoreType.SLEEP && it.hasNumber }?.score ?: day.health.sleep,
                state.riskScores.firstOrNull { it.type == RiskScoreType.RECOVERY && it.hasNumber }?.score ?: day.health.recovery,
                state.riskScores.firstOrNull { it.type == RiskScoreType.STRAIN && it.hasNumber }?.valueLabel?.let(com.bipss.app.localization.LocalNumbers::floatOrNull) ?: day.health.strain
            ) else day.health
            DailyScoreHero(health) { navController.navigate(Routes.snapshot(day.day)) }
            if (day.isToday) {
            val dayTitle = when {
                !state.hasBand -> AppText.text(R.string.copy_ddb2c3ed7320, "Make today yours")
                (state.baselineDays ?: 0) < 14 -> AppText.text(R.string.copy_de1bf2b52776, "Getting to know your rhythm")
                else -> AppText.text(R.string.copy_c0bc6a46ec43, "Your day, at a glance")
            }
            BipssCard(onClick = { navController.navigate(Routes.DASHBOARD_CHAT) }) {
                Text(dayTitle, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                Spacer(Modifier.height(Space.s))
                Text(if (!state.hasBand) AppText.text(R.string.copy_bc46a2769d20, "Connect your band for sleep and recovery. You can log water and activities now.")
                    else if ((state.baselineDays ?: 0) < 14) AppText.text(R.string.copy_70f8063aa316, "Keep wearing your band each day and overnight. Your personal insights build as you go.")
                    else AppText.text(R.string.copy_11789eebd1ae, "Explore your latest readings and choose a small next step with Coach."),
                    style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                Spacer(Modifier.height(Space.m))
                Text(AppText.text(R.string.copy_abb8ead8d707, "Ask Coach  →"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            }
            SectionHeader(if (day.isToday) AppText.text(R.string.copy_1b2fffea95f7, "My day") else AppText.text(R.string.copy_b5b35eb8c85a, "Your day"), trailing = {
                TextButton(onClick = { navController.navigate(Routes.snapshot(day.day)) }) { Text(AppText.text(R.string.copy_f9dbcaa8f1bf, "Snapshot →")) }
            })
            DayEvents(day) { navController.navigate(Routes.workout(it)) }
            if (day.isToday) {

            BipssCard(onClick = { navController.navigate(Routes.ACTIVITY_DETAIL) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Rounded.DirectionsRun, colors.activity)
                    Spacer(Modifier.width(Space.m))
                    Column(Modifier.weight(1f)) {
                        Text(AppText.text(R.string.copy_1de3df70ddf4, "Steps"), style = MaterialTheme.typography.titleSmall, color = colors.textSecondary)
                        Spacer(Modifier.height(Space.s))
                        Text(daily.todaySteps?.let { "%,d".format(it) } ?: AppText.text(R.string.copy_521adb53898c, "Start moving"), style = MaterialTheme.typography.headlineSmall, color = colors.textPrimary)
                    }
                    Text(if (daily.todaySteps == null) AppText.text(R.string.copy_cc3dc81f41c3, "Set up →") else AppText.text(R.string.copy_25c8122ba463, "of %1\$s", daily.data.goal), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                }
                if (daily.todaySteps != null) {
                    Spacer(Modifier.height(Space.m))
                    LinearProgressIndicator(progress = { (daily.todaySteps!!.toFloat() / daily.data.goal).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(), color = colors.activity)
                }
            }
            com.bipss.app.ui.hydration.HydrationQuickCard(onOpen = { navController.navigate(Routes.HYDRATION_DETAIL) })
            com.bipss.app.ui.cycle.CycleQuickCard(onOpen = { navController.navigate(Routes.CYCLE_DETAIL) })
            if (daily.data.nutritionEnabled) BipssCard(onClick = { navController.navigate(Routes.NUTRITION) }) {
                Text(AppText.text(R.string.copy_be80ef360690, "Food journal"), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(Space.s))
                val meals = daily.data.foods.count { com.bipss.app.domain.time.LocalDay.epochDay(it.atMs) == daily.today }
                Text(if (meals == 0) AppText.text(R.string.copy_7932cd18c2ca, "Log your first meal  →") else AppText.text(R.string.copy_8780fb18d2ca, "%1\$s entries today  →", meals), color = colors.textSecondary)
            }
            } else {
                BipssCard {
                    InfoRow(AppText.text(R.string.copy_1de3df70ddf4, "Steps"), day.steps?.toString() ?: AppText.text(R.string.copy_b37c7879f6c3, "Not recorded"))
                    InfoRow(AppText.text(R.string.copy_fdfd56ca6a17, "Water logged"), AppText.text(R.string.copy_e52749128c70, "%1\$s mL", day.waterMl))
                }
            }
            if (day.isToday) {
                SectionHeader(AppText.text(R.string.copy_be38d62f0f07, "Daily rhythm"), trailing = {
                    TextButton(onClick = { navController.navigate(Routes.ROUTINE) }) { Text(AppText.text(R.string.copy_7758080221e6, "Sleep times")) }
                })
                val now = java.time.LocalTime.now()
                DailyWindowCards(com.bipss.app.domain.DailyWindows.build(now.hour * 60 + now.minute,
                    day.activity.bedtimeMinutes, day.activity.wakeMinutes,
                    state.env.uvIndex.takeUnless { state.env.isStale }, state.env.aqiValue.takeUnless { state.env.isStale },
                    health.recovery, day.waterMl))
            }
            SectionHeader(AppText.text(R.string.copy_80ef841eaeea, "Health snapshot"), trailing = { TextButton(onClick = { navController.navigate(Routes.snapshot(day.day)) }) { Text(AppText.text(R.string.copy_d7a8c446069a, "See all")) } })
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                MetricTile(AppText.text(R.string.copy_1447897ca9f5, "Heart rate"), (if (day.isToday) state.heartRate else day.vitals[MetricTypes.HR]?.avgValue?.toInt())?.toString() ?: "—", "bpm", Icons.Rounded.Favorite,
                    colors.heart, if (day.isToday) state.heartRateHistory else emptyList(), Modifier.weight(1f).fillMaxHeight(),
                    caption = if (!day.isToday) AppText.text(R.string.copy_73a5ff412204, "Daily average") else if (state.heartRate == null) AppText.text(R.string.copy_68fc858133b9, "Wear your band") else AppText.text(R.string.copy_d82e338b1734, "Latest reading"), onClick = { navController.navigate(if (day.isToday) Routes.metricDetail(MetricTypes.HR) else Routes.snapshot(day.day)) })
                MetricTile(AppText.text(R.string.copy_c8b3907ee6a2, "Blood oxygen"), (if (day.isToday) state.spO2 else day.vitals[MetricTypes.SPO2]?.avgValue?.toInt())?.toString() ?: "—", "%", Icons.Rounded.Bloodtype,
                    colors.spo2, if (day.isToday) state.spO2History else emptyList(), Modifier.weight(1f).fillMaxHeight(),
                    caption = if (!day.isToday) AppText.text(R.string.copy_73a5ff412204, "Daily average") else if (state.spO2 == null) AppText.text(R.string.copy_68fc858133b9, "Wear your band") else AppText.text(R.string.copy_d82e338b1734, "Latest reading"), onClick = { navController.navigate(if (day.isToday) Routes.metricDetail(MetricTypes.SPO2) else Routes.snapshot(day.day)) })
            }
            BipssCard(onClick = { navController.navigate(Routes.BREATHING) }) {
                Text(AppText.text(R.string.copy_99651e04f1e1, "A moment for you"), style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                Spacer(Modifier.height(Space.s))
                Text(AppText.text(R.string.copy_3679e72930e1, "Take a one-minute breathing break  →"), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
            }
            if (day.isToday) {
            SectionHeader(AppText.text(R.string.copy_c9669413061d, "Around you"))
            BipssCard(onClick = { navController.navigate(Routes.DASHBOARD_INSIGHTS) }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    HomeCondition(AppText.text(R.string.copy_b958ce8b871a, "Temperature"), state.env.ambientTempC?.let { "${it.toInt()}°" } ?: "—")
                    HomeCondition(AppText.text(R.string.copy_46819ccb5e6d, "Humidity"), state.env.humidityPercent?.let { "${it.toInt()}%" } ?: "—")
                    HomeCondition(if (state.env.aqiSource == com.bipss.app.domain.environmental.AqiSource.MODEL) AppText.text(R.string.copy_df8cbadf5610, "AQI · estimated") else AppText.text(R.string.copy_0e9cbafcdd80, "Air quality"),
                        state.env.aqiValue?.toString() ?: "—")
                }
                val high = state.hazardDomains.firstOrNull { it.hasNumber && it.level == RiskLevel.HIGH }
                if (high != null) {
                    Spacer(Modifier.height(Space.m))
                    Text(AppText.text(R.string.copy_11a5cb268077, "%1\$s: take extra care. View local conditions →", high.type.displayName),
                        style = MaterialTheme.typography.bodySmall, color = colors.bandRisk)
                }
            }
            }
            val fixable = if (SosBlocker.NO_TELEPHONY in readiness) emptySet() else readiness
            if (fixable.isNotEmpty()) InfoBanner(AppText.text(R.string.copy_f787bdbf8290, "Finish SOS setup so your contacts can receive an alert."),
                Icons.Rounded.LocalHospital, colors.bandMonitor, action = {
                    GhostButton(AppText.text(R.string.copy_4da10f1fbb17, "Set up"), { navController.navigate(Routes.SETTINGS_EMERGENCY) }, color = colors.bandMonitor)
                })
            Spacer(Modifier.height(Space.l))
        }
    }
}

@Composable private fun HomeCondition(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = BipssTheme.colors.textSecondary)
        Spacer(Modifier.height(Space.s))
        Text(value, style = MaterialTheme.typography.titleLarge, color = BipssTheme.colors.textPrimary)
    }
}
