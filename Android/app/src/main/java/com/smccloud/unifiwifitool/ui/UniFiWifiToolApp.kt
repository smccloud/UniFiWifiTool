package com.smccloud.unifiwifitool.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smccloud.unifiwifitool.ui.components.ConnectionStateBanner
import com.smccloud.unifiwifitool.ui.components.TunnelStatusPill
import com.smccloud.unifiwifitool.ui.model.AccessMode
import com.smccloud.unifiwifitool.ui.model.AuditEntryUiModel
import com.smccloud.unifiwifitool.ui.model.DeviceScheduleUiModel
import com.smccloud.unifiwifitool.ui.model.DeviceUiModel
import com.smccloud.unifiwifitool.ui.model.DisciplineUiModel
import com.smccloud.unifiwifitool.ui.model.SampleUiData
import com.smccloud.unifiwifitool.ui.model.ThemePreference
import com.smccloud.unifiwifitool.ui.model.TunnelState
import com.smccloud.unifiwifitool.ui.navigation.AppDestination
import com.smccloud.unifiwifitool.ui.screens.audit.AuditScreen
import com.smccloud.unifiwifitool.ui.screens.devices.DeviceDetailSheet
import com.smccloud.unifiwifitool.ui.screens.devices.DevicesScreen
import com.smccloud.unifiwifitool.ui.screens.devices.ScheduleEditorDialog
import com.smccloud.unifiwifitool.ui.screens.discipline.DisciplineScreen
import com.smccloud.unifiwifitool.ui.screens.overview.OverviewScreen
import com.smccloud.unifiwifitool.ui.screens.pairing.PairingScreen
import com.smccloud.unifiwifitool.ui.screens.settings.SettingsSheet
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UniFiWifiToolApp(
    themePreference: ThemePreference,
    onThemePreferenceChange: (ThemePreference) -> Unit,
    useDynamicColor: Boolean,
    onDynamicColorChange: (Boolean) -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var isPaired by remember { mutableStateOf(true) }
    var biometricLockEnabled by remember { mutableStateOf(true) }
    var pairedInstance by remember { mutableStateOf(SampleUiData.pairedInstance) }
    var tunnelState by remember { mutableStateOf(TunnelState.CONNECTED) }

    var currentDestination by remember { mutableStateOf(AppDestination.OVERVIEW) }
    var devices by remember { mutableStateOf(SampleUiData.initialDevices) }
    var auditEntries by remember { mutableStateOf(SampleUiData.initialAuditEntries) }

    var searchQuery by remember { mutableStateOf("") }
    var selectedDeviceFilter by remember { mutableStateOf("All") }

    var activeSheetDeviceId by remember { mutableStateOf<Int?>(null) }
    var scheduleEditorDeviceId by remember { mutableStateOf<Int?>(null) }
    var showSettingsSheet by remember { mutableStateOf(false) }

    if (!isPaired) {
        PairingScreen(
            initialDeviceName = pairedInstance.mobileDeviceName,
            biometricEnabled = biometricLockEnabled,
            onBiometricEnabledChange = { biometricLockEnabled = it },
            onCompletePairing = { newDeviceName, newRelayUrl ->
                pairedInstance = pairedInstance.copy(
                    mobileDeviceName = newDeviceName,
                    relayEndpoint = newRelayUrl,
                    lastSyncLabel = "Updated just now"
                )
                tunnelState = TunnelState.CONNECTED
                isPaired = true
            }
        )
        return
    }

    val recordAudit: (String, DeviceUiModel?, String) -> Unit = { action, target, detailJson ->
        val nextId = (auditEntries.maxOfOrNull { it.id } ?: 500) + 1
        val entry = AuditEntryUiModel(
            id = nextId,
            timestampUtc = "2026-11-23 16:25 UTC",
            relativeTime = "Just now",
            action = action,
            result = "ok",
            targetLabel = target?.label,
            targetMac = target?.mac,
            actor = "mobile: ${pairedInstance.mobileDeviceName}",
            detailJson = detailJson
        )
        auditEntries = listOf(entry) + auditEntries
    }

    val handleAccessModeChange: (Int, AccessMode) -> Unit = { deviceId, newMode ->
        val target = devices.find { it.id == deviceId }
        if (target != null) {
            val effective = when (newMode) {
                AccessMode.ALLOWED -> AccessMode.ALLOWED
                AccessMode.BLOCKED -> AccessMode.BLOCKED
                AccessMode.SCHEDULED -> AccessMode.ALLOWED
                AccessMode.IGNORED -> AccessMode.IGNORED
            }
            devices = devices.map {
                if (it.id == deviceId) {
                    it.copy(
                        accessMode = newMode,
                        effectiveAccess = effective,
                        observedBlocked = newMode == AccessMode.BLOCKED
                    )
                } else {
                    it
                }
            }
            val actionName = when (newMode) {
                AccessMode.BLOCKED -> "block"
                AccessMode.ALLOWED -> "unblock"
                AccessMode.SCHEDULED -> "schedule_enable"
                AccessMode.IGNORED -> "device_ignore"
            }
            recordAudit(
                actionName,
                target,
                """{"previous_mode":"${target.accessMode.name.lowercase()}","new_mode":"${newMode.name.lowercase()}"}"""
            )
            scope.launch {
                snackbarHostState.showSnackbar("${target.label} set to ${newMode.label}")
            }
        }
    }

    val handleStartDiscipline: (Int, Int, Int, Int) -> Unit = { deviceId, downKbps, upKbps, minutes ->
        val target = devices.find { it.id == deviceId }
        if (target != null) {
            val limit = DisciplineUiModel(
                deviceId = target.id,
                deviceLabel = target.label,
                deviceMac = target.mac,
                downloadKbps = downKbps,
                uploadKbps = upKbps,
                startedAtUtc = "2026-11-23 16:25 UTC",
                expiresAtUtc = "in ${minutes}m",
                remainingLabel = "${minutes}m left",
                progressFraction = 1.0f,
                actor = "mobile: ${pairedInstance.mobileDeviceName}"
            )
            devices = devices.map {
                if (it.id == deviceId) it.copy(activeDiscipline = limit) else it
            }
            recordAudit(
                "discipline_start",
                target,
                """{"download_kbps":$downKbps,"upload_kbps":$upKbps,"duration_minutes":$minutes}"""
            )
            scope.launch {
                snackbarHostState.showSnackbar(
                    "Speed limit started on ${target.label} ($downKbps↓/$upKbps↑ Kbps)"
                )
            }
        }
    }

    val handleEndDiscipline: (Int) -> Unit = { deviceId ->
        val target = devices.find { it.id == deviceId }
        if (target != null && target.activeDiscipline != null) {
            devices = devices.map {
                if (it.id == deviceId) it.copy(activeDiscipline = null) else it
            }
            recordAudit(
                "discipline_end",
                target,
                """{"reason":"manual_restore"}"""
            )
            scope.launch {
                snackbarHostState.showSnackbar("Restored normal WiFi speed for ${target.label}")
            }
        }
    }

    val handleSaveSchedule: (Int, DeviceScheduleUiModel) -> Unit = { deviceId, schedule ->
        val target = devices.find { it.id == deviceId }
        if (target != null) {
            devices = devices.map {
                if (it.id == deviceId) {
                    it.copy(
                        accessMode = AccessMode.SCHEDULED,
                        schedule = schedule
                    )
                } else {
                    it
                }
            }
            recordAudit(
                "schedule_update",
                target,
                """{"windows":${schedule.windows.size},"default_access":"${schedule.defaultAccess.name.lowercase()}"}"""
            )
            scheduleEditorDeviceId = null
            scope.launch {
                snackbarHostState.showSnackbar("Saved schedule for ${target.label}")
            }
        }
    }

    val activeDisciplineCount = devices.count { it.activeDiscipline != null }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = pairedInstance.instanceName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        TunnelStatusPill(
                            tunnelState = tunnelState,
                            onClick = {
                                val next = when (tunnelState) {
                                    TunnelState.CONNECTED -> TunnelState.CONTROLLER_UNREACHABLE
                                    TunnelState.CONTROLLER_UNREACHABLE -> TunnelState.HOME_OFFLINE
                                    TunnelState.HOME_OFFLINE -> TunnelState.CONNECTED
                                }
                                tunnelState = next
                            }
                        )
                    }
                },
                actions = {
                    // Quick Light / Dark mode toggle in the TopAppBar
                    IconButton(
                        onClick = {
                            val next = when (themePreference) {
                                ThemePreference.DARK -> ThemePreference.LIGHT
                                ThemePreference.LIGHT -> ThemePreference.DARK
                                ThemePreference.SYSTEM -> ThemePreference.LIGHT
                            }
                            onThemePreferenceChange(next)
                        }
                    ) {
                        Icon(
                            imageVector = if (themePreference == ThemePreference.LIGHT) {
                                Icons.Outlined.DarkMode
                            } else {
                                Icons.Outlined.LightMode
                            },
                            contentDescription = "Toggle light or dark theme"
                        )
                    }
                    IconButton(onClick = { showSettingsSheet = true }) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = "Settings and connection"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ) {
                AppDestination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = currentDestination == destination,
                        onClick = { currentDestination = destination },
                        icon = {
                            if (destination == AppDestination.DISCIPLINE && activeDisciplineCount > 0) {
                                BadgedBox(
                                    badge = {
                                        Badge { Text(activeDisciplineCount.toString()) }
                                    }
                                ) {
                                    Icon(destination.icon, contentDescription = destination.title)
                                }
                            } else {
                                Icon(destination.icon, contentDescription = destination.title)
                            }
                        },
                        label = { Text(destination.title) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            ConnectionStateBanner(
                tunnelState = tunnelState,
                onRetryClick = { tunnelState = TunnelState.CONNECTED }
            )

            when (currentDestination) {
                AppDestination.OVERVIEW -> OverviewScreen(
                    instance = pairedInstance,
                    devices = devices,
                    auditEntries = auditEntries,
                    tunnelState = tunnelState,
                    onSelectDeviceFilter = { filter ->
                        selectedDeviceFilter = filter
                        currentDestination = AppDestination.DEVICES
                    },
                    onOpenDevice = { activeSheetDeviceId = it.id },
                    onEndDiscipline = handleEndDiscipline,
                    onOpenAuditTab = { currentDestination = AppDestination.AUDIT },
                    onSyncNow = {
                        pairedInstance = pairedInstance.copy(lastSyncLabel = "Updated just now")
                        scope.launch {
                            snackbarHostState.showSnackbar("Reconciled inventory with UniFi controller")
                        }
                    }
                )

                AppDestination.DEVICES -> DevicesScreen(
                    devices = devices,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    selectedFilter = selectedDeviceFilter,
                    onSelectFilter = { selectedDeviceFilter = it },
                    onDeviceClick = { activeSheetDeviceId = it.id }
                )

                AppDestination.DISCIPLINE -> DisciplineScreen(
                    devices = devices,
                    tunnelState = tunnelState,
                    onStartDiscipline = handleStartDiscipline,
                    onEndDiscipline = handleEndDiscipline
                )

                AppDestination.AUDIT -> AuditScreen(
                    entries = auditEntries
                )
            }
        }
    }

    // Device Control Modal Bottom Sheet
    val sheetDevice = devices.find { it.id == activeSheetDeviceId }
    if (sheetDevice != null) {
        DeviceDetailSheet(
            device = sheetDevice,
            tunnelState = tunnelState,
            onDismiss = { activeSheetDeviceId = null },
            onChangeAccessMode = handleAccessModeChange,
            onOpenScheduleEditor = { target ->
                activeSheetDeviceId = null
                scheduleEditorDeviceId = target.id
            },
            onStartDiscipline = handleStartDiscipline,
            onEndDiscipline = handleEndDiscipline
        )
    }

    // Full-Screen Schedule Editor Dialog
    val scheduleDevice = devices.find { it.id == scheduleEditorDeviceId }
    if (scheduleDevice != null) {
        ScheduleEditorDialog(
            device = scheduleDevice,
            onDismiss = { scheduleEditorDeviceId = null },
            onSaveSchedule = handleSaveSchedule
        )
    }

    // Settings, Theme Mode & Connection Modal Sheet
    if (showSettingsSheet) {
        SettingsSheet(
            instance = pairedInstance,
            themePreference = themePreference,
            onThemePreferenceChange = onThemePreferenceChange,
            useDynamicColor = useDynamicColor,
            onDynamicColorChange = onDynamicColorChange,
            biometricLockEnabled = biometricLockEnabled,
            onBiometricLockChange = { biometricLockEnabled = it },
            tunnelState = tunnelState,
            onTunnelStateChange = { tunnelState = it },
            onUnpairDevice = {
                showSettingsSheet = false
                isPaired = false
            },
            onDismiss = { showSettingsSheet = false }
        )
    }
}
