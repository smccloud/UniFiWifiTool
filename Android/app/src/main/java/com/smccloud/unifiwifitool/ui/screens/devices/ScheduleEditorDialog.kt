package com.smccloud.unifiwifitool.ui.screens.devices

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.smccloud.unifiwifitool.ui.model.AccessMode
import com.smccloud.unifiwifitool.ui.model.DeviceScheduleUiModel
import com.smccloud.unifiwifitool.ui.model.DeviceUiModel
import com.smccloud.unifiwifitool.ui.model.ScheduleWindowUiModel
import com.smccloud.unifiwifitool.ui.model.WindowAction

private val DayLabels = listOf("M", "T", "W", "T", "F", "S", "S")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScheduleEditorDialog(
    device: DeviceUiModel,
    onDismiss: () -> Unit,
    onSaveSchedule: (Int, DeviceScheduleUiModel) -> Unit
) {
    val existing = device.schedule
    var defaultAccess by remember {
        mutableStateOf(existing?.defaultAccess ?: AccessMode.BLOCKED)
    }
    var timezone by remember {
        mutableStateOf(existing?.timezone ?: "America/Chicago")
    }
    var windows by remember {
        mutableStateOf(
            existing?.windows ?: listOf(
                ScheduleWindowUiModel(
                    id = "w_default",
                    days = setOf(0, 1, 2, 3, 4),
                    startTime = "15:30",
                    endTime = "20:30",
                    action = WindowAction.ALLOW
                )
            )
        )
    }

    val previewSummary = remember(defaultAccess, windows) {
        val fallbackLabel = if (defaultAccess == AccessMode.ALLOWED) "allowed" else "blocked"
        "${windows.size} weekly window(s) configured; $fallbackLabel outside windows. Block windows take priority over allow windows."
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Schedule · ${device.label}") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Outlined.Close, contentDescription = "Close")
                        }
                    },
                    actions = {
                        Button(
                            onClick = {
                                onSaveSchedule(
                                    device.id,
                                    DeviceScheduleUiModel(
                                        defaultAccess = defaultAccess,
                                        timezone = timezone,
                                        windows = windows,
                                        plainEnglishSummary = previewSummary,
                                        nextTransitionLabel = "Evaluated every 30s"
                                    )
                                )
                            },
                            modifier = Modifier.padding(end = 12.dp)
                        ) {
                            Text("Save")
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Quick Presets
                Text(
                    text = "Quick Presets",
                    style = MaterialTheme.typography.titleSmall
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AssistChip(
                        onClick = {
                            defaultAccess = AccessMode.BLOCKED
                            windows = listOf(
                                ScheduleWindowUiModel(
                                    id = "p_weekdays",
                                    days = setOf(0, 1, 2, 3, 4),
                                    startTime = "15:30",
                                    endTime = "20:30",
                                    action = WindowAction.ALLOW
                                )
                            )
                        },
                        label = { Text("Allow after school") }
                    )
                    AssistChip(
                        onClick = {
                            defaultAccess = AccessMode.ALLOWED
                            windows = listOf(
                                ScheduleWindowUiModel(
                                    id = "p_overnight",
                                    days = setOf(0, 1, 2, 3, 4, 5, 6),
                                    startTime = "22:00",
                                    endTime = "07:00",
                                    action = WindowAction.BLOCK
                                )
                            )
                        },
                        label = { Text("Block overnight (22:00–07:00)") }
                    )
                    AssistChip(
                        onClick = {
                            defaultAccess = AccessMode.BLOCKED
                            windows = listOf(
                                ScheduleWindowUiModel(
                                    id = "p_weekend",
                                    days = setOf(5, 6),
                                    startTime = "08:00",
                                    endTime = "21:00",
                                    action = WindowAction.ALLOW
                                )
                            )
                        },
                        label = { Text("Weekends only") }
                    )
                }

                // Default Fallback Access outside windows
                Text(
                    text = "Fallback State (Outside Windows)",
                    style = MaterialTheme.typography.titleSmall
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                        selected = defaultAccess == AccessMode.BLOCKED,
                        onClick = { defaultAccess = AccessMode.BLOCKED }
                    ) {
                        Text("Blocked by default")
                    }
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        selected = defaultAccess == AccessMode.ALLOWED,
                        onClick = { defaultAccess = AccessMode.ALLOWED }
                    ) {
                        Text("Allowed by default")
                    }
                }

                // Weekly Windows List
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Weekly Windows ($timezone)",
                        style = MaterialTheme.typography.titleSmall
                    )
                    FilledTonalButton(
                        onClick = {
                            windows = windows + ScheduleWindowUiModel(
                                id = "w_${windows.size + 1}",
                                days = setOf(5, 6),
                                startTime = "09:00",
                                endTime = "21:00",
                                action = WindowAction.ALLOW
                            )
                        }
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Text("Add Window")
                    }
                }

                windows.forEachIndexed { index, window ->
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
                                Text(
                                    text = "Window ${index + 1} (${window.startTime} – ${window.endTime})",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                if (windows.size > 1) {
                                    IconButton(
                                        onClick = {
                                            windows = windows.filterIndexed { i, _ -> i != index }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.DeleteOutline,
                                            contentDescription = "Remove window"
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // 7-Day Circular Toggles (Mon..Sun)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                DayLabels.forEachIndexed { dayIndex, dayLetter ->
                                    val selected = window.days.contains(dayIndex)
                                    val bg = if (selected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHighest
                                    }
                                    val fg = if (selected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(CircleShape)
                                            .background(bg)
                                            .clickable {
                                                val updatedDays = if (selected && window.days.size > 1) {
                                                    window.days - dayIndex
                                                } else {
                                                    window.days + dayIndex
                                                }
                                                windows = windows.toMutableList().also {
                                                    it[index] = window.copy(days = updatedDays)
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = dayLetter,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = fg,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                SegmentedButton(
                                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                                    selected = window.action == WindowAction.ALLOW,
                                    onClick = {
                                        windows = windows.toMutableList().also {
                                            it[index] = window.copy(action = WindowAction.ALLOW)
                                        }
                                    }
                                ) {
                                    Text("Allow during window")
                                }
                                SegmentedButton(
                                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                                    selected = window.action == WindowAction.BLOCK,
                                    onClick = {
                                        windows = windows.toMutableList().also {
                                            it[index] = window.copy(action = WindowAction.BLOCK)
                                        }
                                    }
                                ) {
                                    Text("Block during window")
                                }
                            }
                        }
                    }
                }

                // Plain-English Preview Box
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Schedule Preview",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = previewSummary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}
