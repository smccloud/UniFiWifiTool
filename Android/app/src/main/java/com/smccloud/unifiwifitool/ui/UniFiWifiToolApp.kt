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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smccloud.unifiwifitool.ui.components.ConnectionStateBanner
import com.smccloud.unifiwifitool.ui.components.TunnelStatusPill
import com.smccloud.unifiwifitool.ui.model.ThemePreference
import com.smccloud.unifiwifitool.ui.model.TunnelState
import com.smccloud.unifiwifitool.ui.navigation.AppDestination
import com.smccloud.unifiwifitool.ui.screens.audit.AuditScreen
import com.smccloud.unifiwifitool.ui.screens.devices.DeviceDetailSheet
import com.smccloud.unifiwifitool.ui.screens.devices.DevicesScreen
import com.smccloud.unifiwifitool.ui.screens.devices.ScheduleEditorDialog
import com.smccloud.unifiwifitool.ui.screens.discipline.DisciplineScreen
import com.smccloud.unifiwifitool.ui.screens.lock.LockScreen
import com.smccloud.unifiwifitool.ui.screens.overview.OverviewScreen
import com.smccloud.unifiwifitool.ui.screens.pairing.PairingScreen
import com.smccloud.unifiwifitool.ui.screens.settings.SettingsSheet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UniFiWifiToolApp(
    state: AccessUiState,
    actions: AccessActions,
    events: Flow<String> = emptyFlow(),
    canUseBiometrics: Boolean = false,
    onRequestBiometricUnlock: () -> Unit = {}
) {
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(events) {
        events.collect { message -> snackbarHostState.showSnackbar(message) }
    }

    if (!state.isPaired) {
        PairingScreen(
            initialDeviceName = state.pairedInstance.mobileDeviceName,
            biometricEnabled = state.biometricLockEnabled,
            onBiometricEnabledChange = actions::onBiometricLockChange,
            onCompletePairing = { deviceName, relayUrl, secret ->
                actions.onPair(deviceName, relayUrl, secret)
            }
        )
        return
    }

    if (state.isLocked) {
        LockScreen(
            canUseBiometrics = canUseBiometrics,
            onUnlockClick = onRequestBiometricUnlock
        )
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = state.pairedInstance.instanceName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        TunnelStatusPill(
                            tunnelState = state.tunnelState,
                            onClick = {
                                val next = when (state.tunnelState) {
                                    TunnelState.CONNECTED -> TunnelState.CONTROLLER_UNREACHABLE
                                    TunnelState.CONTROLLER_UNREACHABLE -> TunnelState.HOME_OFFLINE
                                    TunnelState.HOME_OFFLINE -> TunnelState.CONNECTED
                                }
                                actions.onPreviewTunnelState(next)
                            }
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val next = when (state.themePreference) {
                                ThemePreference.DARK -> ThemePreference.LIGHT
                                ThemePreference.LIGHT -> ThemePreference.DARK
                                ThemePreference.SYSTEM -> ThemePreference.LIGHT
                            }
                            actions.onThemePreferenceChange(next)
                        }
                    ) {
                        Icon(
                            imageVector = if (state.themePreference == ThemePreference.LIGHT) {
                                Icons.Outlined.DarkMode
                            } else {
                                Icons.Outlined.LightMode
                            },
                            contentDescription = "Toggle light or dark theme"
                        )
                    }
                    IconButton(onClick = actions::onOpenSettings) {
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
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                AppDestination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = state.destination == destination,
                        onClick = { actions.onSelectDestination(destination) },
                        icon = {
                            if (destination == AppDestination.DISCIPLINE && state.throttledCount > 0) {
                                BadgedBox(
                                    badge = { Badge { Text(state.throttledCount.toString()) } }
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
                tunnelState = state.tunnelState,
                onRetryClick = actions::onSync
            )

            when (state.destination) {
                AppDestination.OVERVIEW -> OverviewScreen(
                    instance = state.pairedInstance,
                    devices = state.devices,
                    auditEntries = state.auditEntries,
                    tunnelState = state.tunnelState,
                    onSelectDeviceFilter = { filter ->
                        actions.onSelectFilter(filter)
                        actions.onSelectDestination(AppDestination.DEVICES)
                    },
                    onOpenDevice = { actions.onOpenDevice(it.id) },
                    onEndDiscipline = actions::onEndDiscipline,
                    onOpenAuditTab = { actions.onSelectDestination(AppDestination.AUDIT) },
                    onSyncNow = actions::onSync
                )

                AppDestination.DEVICES -> DevicesScreen(
                    devices = state.devices,
                    searchQuery = state.searchQuery,
                    onSearchQueryChange = actions::onSearchQueryChange,
                    selectedFilter = state.selectedFilter,
                    onSelectFilter = actions::onSelectFilter,
                    onDeviceClick = { actions.onOpenDevice(it.id) }
                )

                AppDestination.DISCIPLINE -> DisciplineScreen(
                    devices = state.devices,
                    tunnelState = state.tunnelState,
                    onStartDiscipline = actions::onStartDiscipline,
                    onEndDiscipline = actions::onEndDiscipline
                )

                AppDestination.AUDIT -> AuditScreen(entries = state.auditEntries)
            }
        }
    }

    state.activeDevice?.let { device ->
        DeviceDetailSheet(
            device = device,
            tunnelState = state.tunnelState,
            onDismiss = actions::onDismissDeviceSheet,
            onChangeAccessMode = actions::onChangeAccessMode,
            onOpenScheduleEditor = { actions.onOpenScheduleEditor(it.id) },
            onStartDiscipline = actions::onStartDiscipline,
            onEndDiscipline = actions::onEndDiscipline
        )
    }

    state.scheduleEditorDevice?.let { device ->
        ScheduleEditorDialog(
            device = device,
            onDismiss = actions::onDismissScheduleEditor,
            onSaveSchedule = actions::onSaveSchedule
        )
    }

    if (state.isSettingsOpen) {
        SettingsSheet(
            instance = state.pairedInstance,
            themePreference = state.themePreference,
            onThemePreferenceChange = actions::onThemePreferenceChange,
            useDynamicColor = state.useDynamicColor,
            onDynamicColorChange = actions::onDynamicColorChange,
            biometricLockEnabled = state.biometricLockEnabled,
            onBiometricLockChange = actions::onBiometricLockChange,
            tunnelState = state.tunnelState,
            onTunnelStateChange = actions::onPreviewTunnelState,
            onUnpairDevice = actions::onUnpairDevice,
            onDismiss = actions::onDismissSettings
        )
    }
}
