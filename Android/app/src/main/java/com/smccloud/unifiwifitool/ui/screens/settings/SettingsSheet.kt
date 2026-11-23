package com.smccloud.unifiwifitool.ui.screens.settings

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smccloud.unifiwifitool.ui.components.MonoTag
import com.smccloud.unifiwifitool.ui.model.PairedInstanceUiModel
import com.smccloud.unifiwifitool.ui.model.ThemePreference
import com.smccloud.unifiwifitool.ui.model.TunnelState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    instance: PairedInstanceUiModel,
    themePreference: ThemePreference,
    onThemePreferenceChange: (ThemePreference) -> Unit,
    useDynamicColor: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
    biometricLockEnabled: Boolean,
    onBiometricLockChange: (Boolean) -> Unit,
    tunnelState: TunnelState,
    onTunnelStateChange: (TunnelState) -> Unit,
    onUnpairDevice: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var notifyBlockEvents by remember { mutableStateOf(true) }
    var notifyDisciplineEvents by remember { mutableStateOf(true) }
    var notifyControllerOffline by remember { mutableStateOf(true) }

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
            Text(
                text = "Appearance, Security & Relay",
                style = MaterialTheme.typography.titleLarge
            )

            // Appearance Card (Light / Dark / System + Material You Dynamic Color)
            OutlinedCard(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Theme Mode (Material Design 3)",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        val prefs = ThemePreference.entries
                        prefs.forEachIndexed { index, pref ->
                            SegmentedButton(
                                shape = SegmentedButtonDefaults.itemShape(index, prefs.size),
                                selected = themePreference == pref,
                                onClick = { onThemePreferenceChange(pref) }
                            ) {
                                Text(pref.label)
                            }
                        }
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Material You Dynamic Color",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = "Use Android wallpaper palette for surfaces while keeping semantic status badges static.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = useDynamicColor,
                                onCheckedChange = onDynamicColorChange
                            )
                        }
                    }
                }
            }

            // Security & Biometric App Lock Card
            OutlinedCard(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "App Lock & Credentials",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Require Biometric Unlock",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "Gates the Android Keystore device token with BiometricPrompt on launch.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = biometricLockEnabled,
                            onCheckedChange = onBiometricLockChange
                        )
                    }
                }
            }

            // Privacy-Preserving Notifications
            OutlinedCard(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Notifications",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Push payloads contain only opaque event counts; device names and MACs are fetched over the encrypted relay.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
                    )
                    SettingToggleRow(
                        title = "Block & unblock events",
                        checked = notifyBlockEvents,
                        onCheckedChange = { notifyBlockEvents = it }
                    )
                    SettingToggleRow(
                        title = "Discipline speed-limit start & expiry",
                        checked = notifyDisciplineEvents,
                        onCheckedChange = { notifyDisciplineEvents = it }
                    )
                    SettingToggleRow(
                        title = "UniFi controller unreachable",
                        checked = notifyControllerOffline,
                        onCheckedChange = { notifyControllerOffline = it }
                    )
                }
            }

            // Paired Instance & Simulated Tunnel State
            OutlinedCard(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Paired Home Instance",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "${instance.instanceName} · v${instance.instanceVersion}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Attributed as: mobile: ${instance.mobileDeviceName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MonoTag(text = instance.relayEndpoint)
                        MonoTag(text = instance.tokenPrefix)
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    Text(
                        text = "Preview Tunnel / Offline State",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        val states = TunnelState.entries
                        states.forEachIndexed { index, state ->
                            SegmentedButton(
                                shape = SegmentedButtonDefaults.itemShape(index, states.size),
                                selected = tunnelState == state,
                                onClick = { onTunnelStateChange(state) }
                            ) {
                                Text(
                                    text = when (state) {
                                        TunnelState.CONNECTED -> "Live"
                                        TunnelState.CONTROLLER_UNREACHABLE -> "No UniFi"
                                        TunnelState.HOME_OFFLINE -> "Offline"
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedButton(
                        onClick = onUnpairDevice,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Unpair This Phone & Wipe Keystore Token")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
