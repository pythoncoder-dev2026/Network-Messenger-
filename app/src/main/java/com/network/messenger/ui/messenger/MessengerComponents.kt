package com.network.messenger.ui.messenger

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.network.messenger.R
import com.network.messenger.crypto.CryptoManager
import com.network.messenger.domain.model.DeliveryState
import com.network.messenger.domain.model.KeyChangeAlert
import com.network.messenger.domain.model.LogEntry
import com.network.messenger.domain.model.LogSeverity
import com.network.messenger.domain.model.TransportStatus
import com.network.messenger.domain.model.TransportType
import com.network.messenger.ui.console.categoryColor
import com.network.messenger.ui.theme.AvatarPalette
import com.network.messenger.ui.theme.LocalMessengerColors
import com.network.messenger.ui.theme.SignalAmber
import com.network.messenger.ui.theme.SignalCyan
import com.network.messenger.ui.theme.SignalEmerald
import com.network.messenger.ui.theme.SignalRose
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@Composable
fun MonogramAvatar(
    seed: String,
    label: String,
    size: Dp = 44.dp,
    showOnlineDot: Boolean = false,
    isVerified: Boolean = false,
    isMuted: Boolean = false,
    modifier: Modifier = Modifier
) {
    val ext = LocalMessengerColors.current
    val bgColor = remember(seed) {
        AvatarPalette[abs(seed.hashCode()) % AvatarPalette.size]
    }
    val initials = remember(label) {
        val parts = label.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        when {
            parts.size >= 2 -> "${parts[0].first()}${parts[1].first()}".uppercase()
            parts.size == 1 -> parts[0].take(2).uppercase()
            else -> "NM"
        }
    }

    Box(modifier = modifier.size(size)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(bgColor)
        ) {
            Text(
                text = initials,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = (size.value * 0.36f).sp,
                    fontWeight = FontWeight.Bold
                ),
                color = Color.White,
                maxLines = 1
            )
        }

        if (showOnlineDot) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(ext.onlineDot)
            )
        } else if (isVerified) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(1.5.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.VerifiedUser,
                    contentDescription = "Verified contact",
                    tint = SignalEmerald,
                    modifier = Modifier.size(13.dp)
                )
            }
        } else if (isMuted) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(1.5.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.VolumeOff,
                    contentDescription = "Muted",
                    tint = SignalAmber,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

@Composable
fun DeliveryTickIndicator(
    state: DeliveryState,
    onOutgoingBubble: Boolean = false,
    modifier: Modifier = Modifier
) {
    val ext = LocalMessengerColors.current
    val baseMuted = if (onOutgoingBubble) {
        ext.onOutgoingBubble.copy(alpha = 0.72f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    val (icon, tint, desc) = when (state) {
        DeliveryState.QUEUED -> Triple(Icons.Default.Schedule, if (onOutgoingBubble) SignalAmber else ext.queuedTick, "Queued offline")
        DeliveryState.SENT -> Triple(Icons.Default.Done, baseMuted, "Sent")
        DeliveryState.RELAYED -> Triple(Icons.Default.DoneAll, if (onOutgoingBubble) Color(0xFFBAE6FD) else SignalCyan, "Relayed")
        DeliveryState.DELIVERED -> Triple(Icons.Default.DoneAll, if (onOutgoingBubble) ext.onOutgoingBubble.copy(alpha = 0.9f) else ext.deliveredTick, "Delivered")
        DeliveryState.READ -> Triple(Icons.Default.DoneAll, if (onOutgoingBubble) Color(0xFF6EE7B7) else ext.readTick, "Read")
        DeliveryState.FAILED -> Triple(Icons.Default.ErrorOutline, SignalRose, "Delivery failed")
    }

    Icon(
        imageVector = icon,
        contentDescription = desc,
        tint = tint,
        modifier = modifier
            .size(15.dp)
            .testTag("delivery_tick_${state.name.lowercase()}")
    )
}

@Composable
fun TopBarLogToggleButton(
    showLogsPanel: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onToggle,
        modifier = modifier.testTag("top_bar_toggle_logs")
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (showLogsPanel) MaterialTheme.colorScheme.primaryContainer
                    else Color.Transparent
                )
        ) {
            Icon(
                imageVector = if (showLogsPanel) Icons.Filled.Terminal else Icons.Outlined.Terminal,
                contentDescription = stringResource(R.string.cd_toggle_logs),
                tint = if (showLogsPanel) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun TransportQuickChip(
    type: TransportType,
    status: TransportStatus,
    peerCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val icon: ImageVector = when (type) {
        TransportType.NEARBY -> Icons.Default.Bluetooth
        TransportType.WIFI_LAN -> Icons.Default.Wifi
        TransportType.INTERNET_WS -> Icons.Default.Public
    }
    val active = status == TransportStatus.ACTIVE || status == TransportStatus.STARTING
    val accent = when (status) {
        TransportStatus.ACTIVE -> MaterialTheme.colorScheme.secondary
        TransportStatus.STARTING -> MaterialTheme.colorScheme.primary
        TransportStatus.DEGRADED -> SignalAmber
        TransportStatus.ERROR -> MaterialTheme.colorScheme.error
        TransportStatus.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = modifier
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (active) accent.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
            )
            .border(
                width = 1.dp,
                color = if (active) accent.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(18.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("transport_toggle_${type.name.lowercase()}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(accent)
        )
        Icon(
            imageVector = icon,
            contentDescription = type.label,
            tint = accent,
            modifier = Modifier.size(15.dp)
        )
        Text(
            text = if (peerCount > 0) "${type.label} ($peerCount)" else type.label,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@Composable
fun KeyChangeAlertBanner(
    alert: KeyChangeAlert,
    onVerify: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ext = LocalMessengerColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(ext.warningBannerBg)
            .border(1.dp, ext.warningBannerAccent.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("key_change_warning_banner"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.GppMaybe,
            contentDescription = "Key changed security warning",
            tint = ext.warningBannerAccent,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Key changed: ${CryptoManager.shortId(alert.userId)}",
                style = MaterialTheme.typography.labelMedium,
                color = ext.warningBannerAccent,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Re-verify safety number or QR before trusting.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        TextButton(onClick = onVerify) {
            Text("Verify", style = MaterialTheme.typography.labelMedium)
        }
        TextButton(onClick = onDismiss) {
            Text("Hide", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Compact, collapsible, auto-scrolling live log drawer (~25% height when expanded)
 * showing the latest 5-8 log lines. Tapping the log stream opens the full LogConsoleScreen.
 */
@Composable
fun CollapsibleLogPanel(
    recentLogs: List<LogEntry>,
    totalLogCount: Int,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenFullLogs: () -> Unit,
    bodyHeight: Dp = 152.dp,
    modifier: Modifier = Modifier
) {
    val ext = LocalMessengerColors.current
    val listState = rememberLazyListState()
    val visibleLogs = remember(recentLogs) { recentLogs.takeLast(8) }

    LaunchedEffect(visibleLogs.lastOrNull()?.id, expanded) {
        if (expanded && visibleLogs.isNotEmpty()) {
            listState.animateScrollToItem(visibleLogs.lastIndex)
        }
    }

    Surface(
        color = ext.logPanelSurface,
        tonalElevation = 3.dp,
        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = ext.logPanelBorder,
                shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
            )
            .testTag("compact_log_panel")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header strip: collapse toggle + live summary + open full log console button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ext.logPanelHeader)
                    .clickable(onClick = onToggleExpanded)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val lastCategoryColor = visibleLogs.lastOrNull()?.let { categoryColor(it.category) } ?: SignalCyan
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(lastCategoryColor)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "LIVE LOGS",
                    style = MaterialTheme.typography.labelSmall,
                    color = SignalCyan,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "($totalLogCount)",
                    style = MaterialTheme.typography.labelSmall,
                    color = ext.logTextMuted
                )
                Spacer(Modifier.weight(1f))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onOpenFullLogs)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("open_full_logs_button")
                ) {
                    Text(
                        text = "Full log",
                        style = MaterialTheme.typography.labelSmall,
                        color = SignalCyan
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.OpenInFull,
                        contentDescription = stringResource(R.string.cd_expand_logs),
                        tint = SignalCyan,
                        modifier = Modifier.size(13.dp)
                    )
                }
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                    contentDescription = stringResource(R.string.cd_collapse_logs),
                    tint = ext.logTextMuted,
                    modifier = Modifier.size(18.dp)
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(color = ext.logPanelBorder, thickness = 1.dp)
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(bodyHeight)
                            .clickable(onClick = onOpenFullLogs)
                            .semantics { contentDescription = "Tap to open full log console" }
                            .testTag("compact_log_list"),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(items = visibleLogs, key = { it.id }) { entry ->
                            CompactLogRow(entry = entry)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactLogRow(entry: LogEntry) {
    val ext = LocalMessengerColors.current
    val timeStr = remember(entry.timestampMs) {
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(entry.timestampMs))
    }
    val tagColor = categoryColor(entry.category)
    val msgColor = when (entry.severity) {
        LogSeverity.ERROR -> SignalRose
        LogSeverity.WARN -> SignalAmber
        LogSeverity.SUCCESS -> SignalEmerald
        LogSeverity.INFO -> ext.logTextPrimary
    }

    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = ext.logTextMuted)) {
                append(timeStr)
                append(' ')
            }
            withStyle(SpanStyle(color = tagColor, fontWeight = FontWeight.Bold)) {
                append("[${entry.category.tag}] ")
            }
            withStyle(SpanStyle(color = msgColor)) {
                append(entry.message)
            }
        },
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp, lineHeight = 14.sp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

fun formatShortTime(timestampMs: Long): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestampMs))
}

fun formatByteSize(bytes: Int): String = when {
    bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024f)
    else -> "${bytes} B"
}
