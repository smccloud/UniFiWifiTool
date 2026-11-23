package com.smccloud.unifiwifitool.ui.screens.overview

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smccloud.unifiwifitool.ui.components.DisciplineBadge
import com.smccloud.unifiwifitool.ui.components.MonoTag
import com.smccloud.unifiwifitool.ui.model.AccessMode
import com.smccloud.unifiwifitool.ui.model.AuditEntryUiModel
import com.smccloud.unifiwifitool.ui.model.DeviceUiModel
import com.smccloud.unifiwifitool.ui.model.PairedInstanceUiModel
import com.smccloud.unifiwifitool.ui.model.TunnelState
import com.smccloud.unifiwifitool.ui.theme.LocalStatusColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OverviewScreen(
    instance: PairedInstanceUiModel,
    devices: List<DeviceUiModel>,
    auditEntries: List<AuditEntryUiModel>,
    tunnelState: TunnelState,
    onSelectDeviceFilter: (String) -> Unit,
    onOpenDevice: (DeviceUiModel) -> Unit,
    onEndDiscipline: (Int) -> Unit,
    onOpenAuditTab: () -> Unit,
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier
) {
    val statusColors = LocalStatusColors.current
    val allowedCount = devices.count { it.accessMode == AccessMode.ALLOWED }
    val blockedCount = devices.count { it.accessMode == AccessMode.BLOCKED }
    val scheduledCount = devices.count { it.accessMode == AccessMode.SCHEDULED }
    val throttledDevices = devices.filter { it.activeDiscipline != null }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 2x2 Status Bento Grid
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    BentoStatTile(
                        title = "Allowed",
                        count = allowedCount,
                        subtitle = "${devices.count { it.isOnline && it.effectiveAccess == AccessMode.ALLOWED }} online now",
                        icon = Icons.Outlined.CheckCircle,
                        accentColor = statusColors.allowed.content,
                        containerColor = statusColors.allowed.container,
                        borderColor = statusColors.allowed.border,
                        onClick = { onSelectDeviceFilter("Allowed") },
                        modifier = Modifier.weight(1f)
                    )
                    BentoStatTile(
                        title = "Blocked",
                        count = blockedCount,
                        subtitle = "Enforced at controller",
                        icon = Icons.Outlined.Block,
                        accentColor = statusColors.blocked.content,
                        containerColor = statusColors.blocked.container,
                        borderColor = statusColors.blocked.border,
                        onClick = { onSelectDeviceFilter("Blocked") },
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    BentoStatTile(
                        title = "Scheduled",
                        count = scheduledCount,
                        subtitle = "Timezone-aware windows",
                        icon = Icons.Outlined.Schedule,
                        accentColor = statusColors.scheduled.content,
                        containerColor = statusColors.scheduled.container,
                        borderColor = statusColors.scheduled.border,
                        onClick = { onSelectDeviceFilter("Scheduled") },
                        modifier = Modifier.weight(1f)
                    )
                    BentoStatTile(
                        title = "Discipline",
                        count = throttledDevices.size,
                        subtitle = if (throttledDevices.isEmpty()) "No active limits" else "Temporary speed limits",
                        icon = Icons.Outlined.Speed,
                        accentColor = statusColors.discipline.content,
                        containerColor = statusColors.discipline.container,
                        borderColor = statusColors.discipline.border,
                        onClick = { onSelectDeviceFilter("Throttled") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Active Discipline Timers
        if (throttledDevices.isNotEmpty()) {
            item {
                Text(
                    text = "Active Speed Limits",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            items(throttledDevices, key = { it.id }) { device ->
                val limit = device.activeDiscipline ?: return@items
                OutlinedCard(
                    onClick = { onOpenDevice(device) },
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    border = BorderStroke(1.dp, statusColors.discipline.border)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = device.label,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                DisciplineBadge(
                                    text = "${limit.downloadKbps} ↓ / ${limit.uploadKbps} ↑ Kbps · ${limit.remainingLabel}"
                                )
                            }
                            FilledTonalButton(
                                onClick = { onEndDiscipline(device.id) },
                                enabled = tunnelState.mutationsAllowed
                            ) {
                                Text("Restore")
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { limit.progressFraction },
                            modifier = Modifier.fillMaxWidth(),
                            color = statusColors.discipline.indicator,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Expires ${limit.expiresAtUtc} · ${limit.actor}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Controller & Outbound Relay Health Card
        item {
            OutlinedCard(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.VerifiedUser,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "${instance.instanceName} (v${instance.instanceVersion})",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "${instance.lastSyncLabel} · Zero inbound ports open",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        FilledTonalButton(
                            onClick = onSyncNow,
                            enabled = tunnelState != TunnelState.HOME_OFFLINE
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Sync,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Sync")
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        instance.capabilities.forEach { capability ->
                            AssistChip(
                                onClick = {},
                                label = {
                                    Text(
                                        text = capability,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }

        // Recent Activity Preview
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recent Audit Activity",
                    style = MaterialTheme.typography.titleMedium
                )
                TextButton(onClick = onOpenAuditTab) {
                    Text("View all")
                }
            }
        }

        items(auditEntries.take(3), key = { it.id }) { entry ->
            OutlinedCard(
                onClick = onOpenAuditTab,
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "${entry.action} · ${entry.targetLabel ?: "system"}",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "${entry.actor} · ${entry.relativeTime}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    MonoTag(text = entry.result.uppercase())
                }
            }
        }
    }
}

@Composable
private fun BentoStatTile(
    title: String,
    count: Int,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    containerColor: Color,
    borderColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedCard(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.outlinedCardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = accentColor
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = accentColor
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = accentColor.copy(alpha = 0.85f)
            )
        }
    }
}
