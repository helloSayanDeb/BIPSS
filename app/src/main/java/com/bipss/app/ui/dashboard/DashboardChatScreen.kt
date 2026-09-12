package com.bipss.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.bipss.app.ai.CoachCard
import com.bipss.app.ai.CoachCardKind
import com.bipss.app.domain.ChatMessage
import com.bipss.app.domain.Sender
import com.bipss.app.ui.components.BipssCard
import com.bipss.app.ui.components.IconBadge
import com.bipss.app.navigation.LocalScaffoldPadding
import com.bipss.app.ui.components.HairlineDivider
import com.bipss.app.ui.components.StatusPill
import com.bipss.app.ui.theme.BipssTheme
import com.bipss.app.ui.theme.Space
import org.koin.androidx.compose.koinViewModel

@Composable
fun DashboardChatScreen(
    navController: NavController,
    vm: ChatDetailsViewModel = koinViewModel()
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val padding = LocalScaffoldPadding.current
    val colors = BipssTheme.colors

    // The briefing reflects stored state, which changes while the user is on
    // other tabs, so rebuild it whenever this one comes forward.
    LaunchedEffect(Unit) { vm.refresh() }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size - 1)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.l),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Coach", style = MaterialTheme.typography.headlineMedium, color = colors.textPrimary)
                Text(
                    "Grounded in your live readings",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary
                )
            }
            // Honest about which engine is answering — the plan's
            // "deterministic first, AI second" contract made visible.
            if (state.aiModelReady) {
                StatusPill("On-device AI", MaterialTheme.colorScheme.primary, icon = Icons.Rounded.AutoAwesome)
            } else {
                StatusPill("Rule-based", colors.textSecondary, icon = Icons.Rounded.Rule)
            }
        }

        HairlineDivider()

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = Space.gutter, vertical = Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.m)
        ) {
            // The briefing sits above the conversation: it is what the user
            // wants on opening the tab, and it reads without asking anything.
            if (state.morningDose.isNotEmpty()) {
                item {
                    Text(
                        "YOUR BRIEFING",
                        style = com.bipss.app.ui.theme.SectionLabelStyle,
                        color = colors.textTertiary
                    )
                }
                items(state.morningDose, key = { it.title }) { card -> CoachCardView(card) }
                item { HairlineDivider() }
            }

            items(state.messages, key = { it.id }) { msg -> ChatBubble(msg) }
            if (state.isAiTyping) {
                item {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(colors.card)
                            .border(1.dp, colors.hairline, RoundedCornerShape(18.dp))
                            .padding(horizontal = Space.l, vertical = Space.m)
                    ) {
                        Text(
                            "Thinking…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary
                        )
                    }
                }
            }
        }

        HairlineDivider()

        Row(
            Modifier.fillMaxWidth().padding(Space.l),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.m)
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(colors.field)
                    .border(1.dp, colors.borderStrong.copy(alpha = 0.55f), RoundedCornerShape(26.dp))
                    .padding(horizontal = Space.xl, vertical = Space.m),
                contentAlignment = Alignment.CenterStart
            ) {
                if (state.inputText.isEmpty()) {
                    Text(
                        "Ask about a score…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.textTertiary
                    )
                }
                BasicTextField(
                    value = state.inputText,
                    onValueChange = vm::onInputChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.textPrimary),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            val sendActive = state.inputText.isNotBlank() && !state.isAiTyping
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(if (sendActive) MaterialTheme.colorScheme.primary else colors.raised)
                    .clickable(enabled = sendActive) { vm.sendMessage() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.Send,
                    "Send",
                    tint = if (sendActive) MaterialTheme.colorScheme.onPrimary else colors.textTertiary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    val colors = BipssTheme.colors
    val isUser = message.sender == Sender.USER

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            Modifier
                .widthIn(max = 300.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 20.dp, topEnd = 20.dp,
                        bottomStart = if (isUser) 20.dp else 6.dp,
                        bottomEnd = if (isUser) 6.dp else 20.dp
                    )
                )
                .background(if (isUser) MaterialTheme.colorScheme.primary else colors.card)
                .then(
                    if (isUser) Modifier
                    else Modifier.border(
                        1.dp, colors.hairline,
                        RoundedCornerShape(
                            topStart = 20.dp, topEnd = 20.dp,
                            bottomStart = 6.dp, bottomEnd = 20.dp
                        )
                    )
                )
                .padding(horizontal = Space.l, vertical = Space.m)
        ) {
            Text(
                message.content,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isUser) MaterialTheme.colorScheme.onPrimary else colors.textPrimary
            )
        }
    }
}

/**
 * One morning-briefing card.
 *
 * Assembled by the rule engine rather than generated, so a card only exists
 * when there was something real to say - a night with no sleep data produces
 * no sleep card rather than a card explaining its own absence.
 */
@Composable
private fun CoachCardView(card: CoachCard) {
    val colors = BipssTheme.colors
    val (icon, tint) = when (card.kind) {
        CoachCardKind.SLEEP      -> Icons.Rounded.Bedtime to colors.sleep
        CoachCardKind.CONDITIONS -> Icons.Rounded.WbSunny to colors.temp
        CoachCardKind.WATCH_OUT  -> Icons.Rounded.WarningAmber to colors.bandMonitor
        CoachCardKind.ADVISORY   -> Icons.Rounded.WarningAmber to colors.bandRisk
        CoachCardKind.ACTION     -> Icons.Rounded.CheckCircle to colors.bandNormal
    }

    BipssCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, tint, size = 32.dp)
            Spacer(Modifier.width(Space.m))
            Text(
                card.title,
                style = MaterialTheme.typography.titleSmall,
                color = colors.textPrimary
            )
        }
        Spacer(Modifier.height(Space.s))
        Text(
            card.body,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary
        )
    }
}
