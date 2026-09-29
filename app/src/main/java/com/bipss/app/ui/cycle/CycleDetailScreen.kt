package com.bipss.app.ui.cycle

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.bipss.app.domain.cycle.CyclePhase
import com.bipss.app.domain.cycle.DayKind
import com.bipss.app.ui.components.*
import com.bipss.app.ui.theme.BipssTheme
import com.bipss.app.ui.theme.Space
import org.koin.androidx.compose.koinViewModel
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Cycle detail (spec 7): where today falls, a month calendar to log period
 * starts and ends, and a free-text note per day. Same page pattern as Sleep.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CycleDetailScreen(navController: NavController, vm: CycleViewModel = koinViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val note by vm.note.collectAsStateWithLifecycle()
    val noteSaved by vm.noteSaved.collectAsStateWithLifecycle()
    val colors = BipssTheme.colors

    // A note typed in the last half second is not lost on leaving.
    DisposableEffect(Unit) { onDispose { vm.flushNote() } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Cycle") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.gutter)
        ) {
            Spacer(Modifier.height(Space.m))
            SummaryCard(ui)
            Spacer(Modifier.height(Space.l))
            BipssCard {
                MonthHeader(ui, onPrev = vm::previousMonth, onNext = vm::nextMonth)
                Spacer(Modifier.height(Space.s))
                MonthGrid(ui, onSelect = vm::select)
                Spacer(Modifier.height(Space.m))
                Legend()
            }
            ui.selectedDay?.let { day ->
                Spacer(Modifier.height(Space.l))
                BipssCard {
                    Text(
                        LocalDate.ofEpochDay(day).format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())),
                        style = MaterialTheme.typography.titleMedium, color = colors.textPrimary
                    )
                    Spacer(Modifier.height(Space.m))
                    val inPeriod = ui.selectedPeriod
                    if (day <= ui.todayDay) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                            if (inPeriod == null) {
                                SecondaryButton("Period started", { vm.logStart(day) }, Modifier.weight(1f))
                            }
                            SecondaryButton("Period ended", { vm.logEnd(day) }, Modifier.weight(1f))
                        }
                        if (inPeriod != null) {
                            Spacer(Modifier.height(Space.s))
                            GhostButton("Remove this period", { vm.removePeriod(inPeriod.id) }, color = colors.bandRisk)
                        }
                    } else {
                        Text("This day hasn't happened yet.", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
                    }
                    message?.let {
                        Spacer(Modifier.height(Space.s))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = colors.bandMonitor)
                    }
                    if (day <= ui.todayDay) {
                        Spacer(Modifier.height(Space.l))
                        OutlinedTextField(
                            value = note,
                            onValueChange = vm::onNoteChange,
                            label = { Text("Note for this day") },
                            placeholder = { Text("Mood, symptoms, energy - anything you want to remember") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3
                        )
                        Text(
                            if (noteSaved) "Saved" else "Saving…",
                            style = MaterialTheme.typography.labelSmall, color = colors.textTertiary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(Space.l))
            Text(
                "Kept in the app's own storage on this phone, and in your Drive backup if you turn that on. " +
                    "Never part of the optional data sharing. BIPSS doesn't use your calendar.",
                style = MaterialTheme.typography.bodySmall, color = colors.textTertiary
            )
            Spacer(Modifier.height(Space.huge))
        }
    }
}

@Composable
private fun SummaryCard(ui: CycleUi) {
    val colors = BipssTheme.colors
    val t = ui.today
    val date = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    BipssCard {
        if (t == null) {
            Text("No period logged yet", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
            Spacer(Modifier.height(Space.xs))
            Text("Tap the day your last period started, then \"Period started\".",
                 style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
            return@BipssCard
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Day ${t.cycleDay} of your cycle", style = MaterialTheme.typography.titleLarge, color = colors.textPrimary)
                Text(t.phase.label, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
            }
        }
        Spacer(Modifier.height(Space.m))
        val next = LocalDate.ofEpochDay(t.nextStartDay).format(date)
        Text(
            if (t.isLate) "Your next period was predicted for $next (${-t.daysUntilNext} day(s) ago)."
            else "Next period: in about ${t.daysUntilNext} day(s), around $next.",
            style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary
        )
        Spacer(Modifier.height(Space.xs))
        Text(
            if (t.stats.usedDefault) "Using a typical 28-day cycle until you've logged two periods."
            else "Your cycle averages ${t.stats.cycleLengthDays} days over your last ${t.stats.cyclesUsed} cycle(s)" +
                (if (t.stats.excludedGaps.isNotEmpty())
                    " (left out: ${t.stats.excludedGaps.joinToString { "$it days" }} - possibly a missed log)." else "."),
            style = MaterialTheme.typography.bodySmall, color = colors.textSecondary
        )
        Spacer(Modifier.height(Space.m))
        InfoBanner(
            text = "Predicted fertile window: ${LocalDate.ofEpochDay(t.fertileStartDay).format(date)} - " +
                "${LocalDate.ofEpochDay(t.fertileEndDay).format(date)}. Not a method of contraception.",
            icon = Icons.Rounded.Info,
            tint = colors.sleep
        )
    }
}

@Composable
private fun MonthHeader(ui: CycleUi, onPrev: () -> Unit, onNext: () -> Unit) {
    val colors = BipssTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrev) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Previous month") }
        Text(
            ui.month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())),
            style = MaterialTheme.typography.titleMedium, color = colors.textPrimary,
            textAlign = TextAlign.Center, modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onNext) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Next month") }
    }
}

@Composable
private fun MonthGrid(ui: CycleUi, onSelect: (Long) -> Unit) {
    val colors = BipssTheme.colors
    val firstDow = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    val weekdays = (0 until 7).map { firstDow.plus(it.toLong()) }
    Row(Modifier.fillMaxWidth()) {
        weekdays.forEach { d ->
            Text(d.getDisplayName(TextStyle.NARROW, Locale.getDefault()), style = MaterialTheme.typography.labelSmall,
                 color = colors.textTertiary, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        }
    }
    Spacer(Modifier.height(Space.xs))
    val lead = (ui.month.atDay(1).dayOfWeek.value - firstDow.value + 7) % 7
    val cells: List<CycleDayUi?> = List(lead) { null } + ui.days
    cells.chunked(7).forEach { week ->
        Row(Modifier.fillMaxWidth()) {
            week.forEach { day ->
                Box(Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                    if (day != null) DayCell(day, selected = day.epochDay == ui.selectedDay, onClick = { onSelect(day.epochDay) })
                }
            }
            repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun DayCell(day: CycleDayUi, selected: Boolean, onClick: () -> Unit) {
    val colors = BipssTheme.colors
    val period = colors.heart
    val bg = when (day.kind) {
        DayKind.LOGGED_PERIOD -> period
        DayKind.FERTILE       -> colors.sleep.copy(alpha = 0.22f)
        else                  -> Color.Transparent
    }
    val fg = when {
        day.kind == DayKind.LOGGED_PERIOD -> Color.White
        day.isFuture -> colors.textTertiary
        else -> colors.textPrimary
    }
    val what = when (day.kind) {
        DayKind.LOGGED_PERIOD    -> "period logged"
        DayKind.PREDICTED_PERIOD -> "period predicted"
        DayKind.FERTILE          -> "fertile window, estimated"
        DayKind.NONE             -> ""
    }
    Box(
        Modifier
            .fillMaxSize(0.86f)
            .clip(CircleShape)
            .background(bg)
            .then(if (day.kind == DayKind.PREDICTED_PERIOD) Modifier.border(1.5.dp, period, CircleShape) else Modifier)
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
            .clickable(onClick = onClick)
            .semantics { contentDescription = listOf("${day.dayOfMonth}", what, if (day.hasNote) "has a note" else "")
                .filter { it.isNotEmpty() }.joinToString(", ") },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${day.dayOfMonth}", style = MaterialTheme.typography.bodyMedium, color = fg,
                 fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Normal)
            if (day.hasNote) Box(Modifier.size(4.dp).clip(CircleShape).background(if (day.kind == DayKind.LOGGED_PERIOD) Color.White else colors.textSecondary))
        }
    }
}

@Composable
private fun Legend() {
    val colors = BipssTheme.colors
    @Composable
    fun item(label: String, dot: @Composable () -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            dot(); Spacer(Modifier.width(Space.xs))
            Text(label, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        item("Logged") { Box(Modifier.size(10.dp).clip(CircleShape).background(colors.heart)) }
        item("Predicted") { Box(Modifier.size(10.dp).border(1.5.dp, colors.heart, CircleShape)) }
        item("Fertile (predicted)") { Box(Modifier.size(10.dp).clip(CircleShape).background(colors.sleep.copy(alpha = 0.4f))) }
    }
}

/** Home card: today's cycle state and one-tap logging; the card opens the detail page. */
@Composable
fun CycleQuickCard(onOpen: () -> Unit, vm: CycleViewModel = koinViewModel()) {
    val female by vm.isFemale.collectAsStateWithLifecycle()
    if (!female) return
    val ui by vm.ui.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val colors = BipssTheme.colors
    val t = ui.today
    Spacer(Modifier.height(Space.l))
    QuickLogCard(
        icon = Icons.Rounded.Favorite,
        tint = colors.heart,
        title = "Cycle",
        subtitle = when {
            !ui.loaded -> "…"
            t == null -> "Log your period to see where you are in your cycle."
            t.isLate -> "Day ${t.cycleDay} · period expected ${-t.daysUntilNext} day(s) ago"
            t.phase == CyclePhase.FERTILE -> "Day ${t.cycleDay} of your cycle · predicted fertile window"
            else -> "Day ${t.cycleDay} of your cycle · ${t.phase.label}"
        },
        onOpen = onOpen,
        footnote = message
    ) {
        val ongoing = ui.ongoing
        androidx.compose.material3.FilledTonalButton(
            onClick = { if (ongoing != null) vm.logEnd(ui.todayDay) else vm.logStart(ui.todayDay) },
            modifier = Modifier.weight(1f)
        ) {
            Text(if (ongoing != null) "Period ended today" else "Period started today")
        }
    }
}
