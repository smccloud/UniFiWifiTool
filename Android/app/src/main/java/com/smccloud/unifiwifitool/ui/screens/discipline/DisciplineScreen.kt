package com.smccloud.unifiwifitool.ui.screens.discipline

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smccloud.unifiwifitool.ui.components.DisciplineBadge
import com.smccloud.unifiwifitool.ui.components.MonoTag
import com.smccloud.unifiwifitool.ui.model.DeviceUiModel
import com.smccloud.unifiwifitool.ui.model.SampleUiData
import com.smccloud.unifiwifitool.ui.model.TunnelState
import com.smccloud.unifiwifitool.ui.theme.LocalStatusColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DisciplineScreen(
    devices: List<DeviceUiModel>,
    tunnelState: TunnelState,
    onStartDiscipline: (Int, Int, Int, Int) -> Unit,
    onEndDiscipline: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val statusColors = LocalStatusColors.current
    val throttledDevices = devices.filter { it.activeDiscipline != null }
    val eligibleDevices = devices.filter { it.activeDiscipline == null }

    var selectedDeviceId by remember(eligibleDevices) {
        mutableIntStateOf(eligibleDevices.firstOrNull()?.id ?: devices.first().id)
    }
    var selectedPreset by remember { mutableStateOf(SampleUiData.speedPresets[2]) }
    var selectedDuration by remember { mutableIntStateOf(60) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Active Temporary Speed Limits (${throttledDevices.size})",
                style = MaterialTheme.typography.titleMedium
            )
        }

        if (throttledDevices.isEmpty()) {
            item {
                OutlinedCard(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "No devices are currently throttled.",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "Start a temporary speed limit below to assign a UniFi bandwidth profile that automatically restores when the timer expires.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        } else {
            items(throttledDevices, key = { it.id }) { device ->
                val limit = device.activeDiscipline ?: return@items
                OutlinedCard(
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
                                MonoTag(text = device.mac)
                            }
                            FilledTonalButton(
                                onClick = { onEndDiscipline(device.id) },
                                enabled = tunnelState.mutationsAllowed
                            ) {
                                Text("Restore Speed")
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        DisciplineBadge(
                            text = "${limit.downloadKbps} ↓ / ${limit.uploadKbps} ↑ Kbps · ${limit.remainingLabel}"
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { limit.progressFraction },
                            modifier = Modifier.fillMaxWidth(),
                            color = statusColors.discipline.indicator,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Started ${limit.startedAtUtc} · Expires ${limit.expiresAtUtc}\nAttributed to ${limit.actor}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Quick-Start Discipline Card
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
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Speed,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Text(
                            text = "Start a Temporary Speed Limit",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "1. Target device:",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        devices.forEach { device ->
                            FilterChip(
                                selected = selectedDeviceId == device.id,
                                onClick = { selectedDeviceId = device.id },
                                label = { Text(device.label) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "2. Speed preset (${selectedPreset.subtitle}):",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SampleUiData.speedPresets.forEach { preset ->
                            FilterChip(
                                selected = selectedPreset.id == preset.id,
                                onClick = { selectedPreset = preset },
                                label = { Text(preset.title) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "3. Duration:",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SampleUiData.durationPresets.forEach { duration ->
                            FilterChip(
                                selected = selectedDuration == duration.minutes,
                                onClick = { selectedDuration = duration.minutes },
                                label = { Text(duration.label) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            onStartDiscipline(
                                selectedDeviceId,
                                selectedPreset.downloadKbps,
                                selectedPreset.uploadKbps,
                                selectedDuration
                            )
                        },
                        enabled = tunnelState.mutationsAllowed,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Apply ${selectedPreset.downloadKbps}↓/${selectedPreset.uploadKbps}↑ Kbps for ${selectedDuration}m"
                        )
                    }
                }
            }
        }
    }
}
