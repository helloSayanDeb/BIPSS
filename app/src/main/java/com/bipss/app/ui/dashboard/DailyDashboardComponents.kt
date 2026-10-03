package com.bipss.app.ui.dashboard

import com.bipss.app.localization.AppText
import com.bipss.app.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bipss.app.data.DayHealth
import com.bipss.app.domain.DailyWindow
import com.bipss.app.domain.time.LocalDay
import com.bipss.app.ui.components.*
import com.bipss.app.ui.theme.*
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun DashboardDateSelector(day: Long, select: (Long) -> Unit) {
    var calendar by remember { mutableStateOf(false) }
    val today = LocalDay.epochDay(System.currentTimeMillis())
    val colors = BipssTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            val last = minOf(day + 3, today)
            for (date in (last - 6)..last) {
                val chosen = date == day
                Column(Modifier.width(42.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (chosen) colors.raised else Color.Transparent)
                    .clickable(enabled = date >= today - 89) { select(date) }.padding(vertical = Space.m),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(LocalDate.ofEpochDay(date).format(DateTimeFormatter.ofPattern("EEEEE")), style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
                    Spacer(Modifier.height(Space.s))
                    Text(LocalDate.ofEpochDay(date).dayOfMonth.toString(), style = MaterialTheme.typography.titleSmall, color = if (chosen) colors.textPrimary else colors.textSecondary)
                }
            }
        }
        IconButton(onClick = { calendar = true }) { Icon(Icons.Rounded.CalendarMonth, AppText.text(R.string.copy_343b5a9c7fd6, "Choose date")) }
    }
    if (calendar) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = day * 86_400_000L,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis / 86_400_000L in (today - 89)..today
            })
        DatePickerDialog(onDismissRequest = { calendar = false }, confirmButton = {
            TextButton(onClick = { picker.selectedDateMillis?.let { select(it / 86_400_000L) }; calendar = false }, enabled = picker.selectedDateMillis != null) { Text(AppText.text(R.string.copy_170e74862adf, "Show day")) }
        }, dismissButton = { TextButton(onClick = { calendar = false }) { Text(AppText.text(R.string.copy_19766ed6ccb2, "Cancel")) } }) { DatePicker(picker) }
    }
}

@Composable fun DailyScoreHero(health: DayHealth, onDetails: () -> Unit) {
    val colors = BipssTheme.colors
    val titles = listOf(AppText.text(R.string.copy_d466bcf52eb6, "Sleep"), AppText.text(R.string.copy_48f6a8d5688b, "Recovery"), AppText.text(R.string.copy_fcde25fd44fb, "Strain"))
    val tints = listOf(colors.sleep, colors.bandNormal, colors.activity)
    val values = listOf(health.sleep?.toFloat(), health.recovery?.toFloat(), health.strain)
    val scales = listOf(100f, 100f, 21f)
    val pager = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()
    BipssCard(contentPadding = PaddingValues(Space.m)) {
        HorizontalPager(pager, modifier = Modifier.fillMaxWidth()) { index ->
            val value = values[index]
            Column(Modifier.fillMaxWidth().clickable(onClick = onDetails).padding(vertical = Space.m), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(226.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(progress = { ((value ?: 0f) / scales[index]).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxSize(), color = tints[index], strokeWidth = 12.dp, trackColor = colors.raised)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(value?.let { if (index == 2) "%.1f".format(it) else it.toInt().toString() } ?: "—",
                            style = MaterialTheme.typography.displayLarge, color = colors.textPrimary)
                        Text(titles[index], style = MaterialTheme.typography.titleMedium, color = colors.textSecondary)
                        Spacer(Modifier.height(Space.s))
                        Text(if (value == null) AppText.text(R.string.copy_60e66c67a643, "No score for this day") else if (index == 2) AppText.text(R.string.copy_5466d1a93ad0, "out of 21") else AppText.text(R.string.copy_8a6be05198a5, "out of 100"),
                            style = MaterialTheme.typography.labelSmall, color = colors.textTertiary)
                    }
                }
                Spacer(Modifier.height(Space.l))
                Text(if (value == null) when(index) { 0 -> AppText.text(R.string.copy_9c5d19bdd22f, "Wear your band overnight"); 1 -> AppText.text(R.string.copy_a7802c745d22, "Your baseline builds with regular wear"); else -> AppText.text(R.string.copy_6c6cb4bf0bea, "Wear your band during the day") }
                    else AppText.text(R.string.copy_355a92ecfe9a, "Tap to explore your day"), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            titles.forEachIndexed { index, title ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                    .background(if (pager.currentPage == index) colors.raised else Color.Transparent)
                    .clickable { scope.launch { pager.animateScrollToPage(index) } }.padding(vertical = Space.m), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(values[index]?.let { if (index == 2) "%.1f".format(it) else "${it.toInt()}" } ?: "—",
                        style = MaterialTheme.typography.titleLarge, color = tints[index])
                    Spacer(Modifier.height(Space.xs))
                    Text(title, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                }
            }
        }
    }
}

@Composable fun DayEvents(ui: DayOverview, openWorkout: (String) -> Unit) {
    data class Event(val at: Long, val title: String, val detail: String, val icon: ImageVector, val id: String? = null)
    val events = buildList {
        ui.sleep?.let { add(Event(it.endMs, AppText.text(R.string.copy_d466bcf52eb6, "Sleep"), AppText.text(R.string.copy_462002dd9830, "%1\$sh %2\$sm · %3\$s interruptions", it.totalSleepMs / 3_600_000, (it.totalSleepMs / 60_000) % 60, it.awakenings), Icons.Rounded.Bedtime)) }
        ui.activities.forEach { add(Event(it.atMs, it.name, AppText.text(R.string.copy_b59b2f00fceb, "%1\$s min · logged by you", it.minutes), Icons.Rounded.DirectionsRun, it.id)) }
        ui.water.forEach { add(Event(it.timestampMs, AppText.text(R.string.copy_20fcba12a24f, "Drink logged"), AppText.text(R.string.copy_e52749128c70, "%1\$s mL", it.amountMl), Icons.Rounded.WaterDrop)) }
    }.sortedByDescending { it.at }
    var expanded by remember(ui.day) { mutableStateOf(false) }
    if (events.isEmpty()) BipssCard { Text(if (ui.isToday) AppText.text(R.string.copy_36802292a490, "Your activities and logged drinks will appear here.") else AppText.text(R.string.copy_a84dc87b7240, "No events recorded for this day."),
        style = MaterialTheme.typography.bodyMedium, color = BipssTheme.colors.textSecondary) }
    else BipssCard {
        (if (expanded) events else events.take(4)).forEachIndexed { index, event ->
            if (index > 0) HairlineDivider()
            Row(Modifier.fillMaxWidth().then(if (event.id != null) Modifier.clickable { openWorkout(event.id) } else Modifier)
                .padding(vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(event.icon, BipssTheme.colors.sleep, size = 34.dp)
                Spacer(Modifier.width(Space.m))
                Column(Modifier.weight(1f)) {
                    Text(event.title, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(Space.xs))
                    Text(event.detail, style = MaterialTheme.typography.bodySmall, color = BipssTheme.colors.textSecondary)
                }
                Text(Instant.ofEpochMilli(event.at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("h:mm a")),
                    style = MaterialTheme.typography.labelSmall, color = BipssTheme.colors.textSecondary)
            }
        }
        if (events.size > 4) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) AppText.text(R.string.copy_94ea9b1d33a0, "Show less") else AppText.text(R.string.copy_3c1f4ac9c973, "Show all %1\$s", events.size)) }
    }
}

@Composable fun DailyWindowCards(windows: List<DailyWindow>) {
    var selected by remember { mutableStateOf<DailyWindow?>(null) }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        windows.forEach { window ->
            val icon = when (window.id) {
                "caffeine" -> Icons.Rounded.Coffee; "move" -> Icons.Rounded.DirectionsRun
                "screens" -> Icons.Rounded.PhoneAndroid; "sun" -> Icons.Rounded.WbSunny
                "water" -> Icons.Rounded.WaterDrop; "sleep" -> Icons.Rounded.Bedtime
                else -> Icons.Rounded.Favorite
            }
            BipssCard(modifier = Modifier.width(150.dp), onClick = { selected = window }) {
                Icon(icon, null, tint = if (window.tone < 0) BipssTheme.colors.bandMonitor else BipssTheme.colors.sleep)
                Spacer(Modifier.height(Space.m))
                Text(window.title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(Space.s))
                Text(window.state, style = MaterialTheme.typography.bodySmall, color = BipssTheme.colors.textSecondary)
            }
        }
    }
    selected?.let { window -> AlertDialog(onDismissRequest = { selected = null }, title = { Text(window.title) },
        text = { Text(window.detail) }, confirmButton = { TextButton(onClick = { selected = null }) { Text(AppText.text(R.string.copy_5ad3dbd1242a, "Got it")) } }) }
}
