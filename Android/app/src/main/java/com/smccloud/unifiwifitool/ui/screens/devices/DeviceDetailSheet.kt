package com.smccloud.unifiwifitool.ui.screens.devices

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EditCalendar
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smccloud.unifiwifitool.ui.components.AccessStatusBadge
import com.smccloud.unifiwifitool.ui.components.DestructiveActionConfirmDialog
import com.smccloud.unifiwifitool.ui.components.DisciplineBadge
import com.smccloud.unifiwifitool.ui.components.MonoTag
import com.smccloud.unifiwifitool.ui.model.AccessMode
import com.smccloud.unifiwifitool.ui.model.DeviceUiModel
import com.smccloud.unifiwifitool.ui.model.SampleUiData
import com.smccloud.unifiwifitool.ui.model.TunnelState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DeviceDetailSheet(
    device: DeviceUiModel,
    tunnelState: TunnelState,
    onDismiss: () -> Unit,
    onChangeAccessMode: (Int, AccessMode) -> Unit,
    onOpenScheduleEditor: (DeviceUiModel) -> Unit,
    onStartDiscipline: (Int, Int, Int, Int) -> Unit,
    onEndDiscipline: (Int) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pendingBlockConfirm by remember { mutableStateOf(false) }
    var selectedPreset by remember { mutableStateOf(SampleUiData.speedPresets[2]) } // 56/33.6 Kbps
    var selectedDurationMinutes by remember { mutableIntStateOf(60) }

    if (pendingBlockConfirm) {
        DestructiveActionConfirmDialog(
            title = "Block ${device.label}?",
            message = "This immediately sends a block-sta command to your UniFi controller over the encrypted relay and records the action in the audit log.",
            confirmLabel = "Block Device",
            onConfirm = {
                pendingBlockConfirm = false
                onChangeAccessMode(device.id, AccessMode.BLOCKED)
            },
            onDismiss = { pendingBlockConfirm = false }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Device Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.label,
                        style = MaterialTheme.typography.titleLarge
                    )
                    if (device.notes.isNotBlank()) {
                        Text(
                            text = device.notes,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
                AccessStatusBadge(mode = device.accessMode)
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MonoTag(text = "MAC ${device.mac}")
                device.ip?.let { MonoTag(text = "IP $it") }
                device.ssid?.let { MonoTag(text = "SSID $it") }
                MonoTag(text = if (device.observedBlocked) "Controller: Blocked" else "Controller: Allowed")
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // 4-Way Access Mode Segmented Button
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Access Policy",
                    style = MaterialTheme.typography.titleSmall
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val modes = AccessMode.entries
                    modes.forEachIndexed { index, mode ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                            selected = device.accessMode == mode,
                            enabled = tunnelState.mutationsAllowed,
                            onClick = {
                                if (mode == AccessMode.BLOCKED && device.accessMode != AccessMode.BLOCKED) {
                                    pendingBlockConfirm = true
                                } else {
                                    onChangeAccessMode(device.id, mode)
                                }
                            }
                        ) {
                            Text(mode.label)
                        }
                    }
                }
            }

            // Schedule Summary & Editor Launcher
            OutlinedCard(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Weekly Schedule",
                            style = MaterialTheme.typography.titleMedium
                        )
                        FilledTonalButton(
                            onClick = { onOpenScheduleEditor(device) },
                            enabled = tunnelState.mutationsAllowed
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.EditCalendar,
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (device.schedule == null) "Create" else "Edit")
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = device.schedule?.plainEnglishSummary
                            ?: "No weekly schedule configured for this device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    device.schedule?.nextTransitionLabel?.let { nextLabel ->
                        Text(
                            text = "Next transition: $nextLabel (${device.schedule.timezone})",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }

            // Discipline Mode (Temporary Speed Limit) Card
            OutlinedCard(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
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
                            text = "Discipline Mode (Speed Limit)",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    val activeLimit = device.activeDiscipline
                    if (activeLimit != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        DisciplineBadge(
                            text = "Active: ${activeLimit.downloadKbps} ↓ / ${activeLimit.uploadKbps} ↑ Kbps · ${activeLimit.remainingLabel}"
                        )
                        Text(
                            text = "Expires ${activeLimit.expiresAtUtc} · ${activeLimit.actor}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { onEndDiscipline(device.id) },
                            enabled = tunnelState.mutationsAllowed,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("End Speed Limit & Restore Normal Speed")
                        }
                    } else {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Speed preset (whole Kbps applied via UniFi bandwidth profile):",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Duration:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            SampleUiData.durationPresets.forEach { duration ->
                                FilterChip(
                                    selected = selectedDurationMinutes == duration.minutes,
                                    onClick = { selectedDurationMinutes = duration.minutes },
                                    label = { Text(duration.label) }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        FilledTonalButton(
                            onClick = {
                                onStartDiscipline(
                                    device.id,
                                    selectedPreset.downloadKbps,
                                    selectedPreset.uploadKbps,
                                    selectedDurationMinutes
                                )
                            },
                            enabled = tunnelState.mutationsAllowed,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "Start Speed Limit (${selectedPreset.downloadKbps}↓/${selectedPreset.uploadKbps}↑ Kbps for ${selectedDurationMinutes}m)"
                            )
                        }
                    }
                }
            }
        }
    }
}
