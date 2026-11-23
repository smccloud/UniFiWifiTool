package com.smccloud.unifiwifitool.ui

import com.smccloud.unifiwifitool.ui.model.AccessMode
import com.smccloud.unifiwifitool.ui.model.DeviceScheduleUiModel
import com.smccloud.unifiwifitool.ui.model.ThemePreference
import com.smccloud.unifiwifitool.ui.model.TunnelState
import com.smccloud.unifiwifitool.ui.navigation.AppDestination

/**
 * Actions the UI can dispatch. Implemented by [AccessViewModel] in production and
 * by a no-op object in Compose previews.
 */
interface AccessActions {
    fun onSelectDestination(destination: AppDestination)
    fun onSearchQueryChange(query: String)
    fun onSelectFilter(filter: String)

    fun onOpenDevice(deviceId: Int)
    fun onDismissDeviceSheet()
    fun onOpenScheduleEditor(deviceId: Int)
    fun onDismissScheduleEditor()

    fun onChangeAccessMode(deviceId: Int, mode: AccessMode)
    fun onStartDiscipline(deviceId: Int, downloadKbps: Int, uploadKbps: Int, durationMinutes: Int)
    fun onEndDiscipline(deviceId: Int)
    fun onSaveSchedule(deviceId: Int, schedule: DeviceScheduleUiModel)
    fun onSync()

    fun onOpenSettings()
    fun onDismissSettings()
    fun onThemePreferenceChange(preference: ThemePreference)
    fun onDynamicColorChange(enabled: Boolean)
    fun onBiometricLockChange(enabled: Boolean)
    fun onPreviewTunnelState(state: TunnelState)
    fun onUnpairDevice()

    fun onPair(deviceName: String, relayUrl: String, pairingSecret: String)
    fun onRequestUnlock()
    fun onUnlockResult(success: Boolean)
}

/** No-op implementation used by @Preview composables. */
object NoOpAccessActions : AccessActions {
    override fun onSelectDestination(destination: AppDestination) = Unit
    override fun onSearchQueryChange(query: String) = Unit
    override fun onSelectFilter(filter: String) = Unit
    override fun onOpenDevice(deviceId: Int) = Unit
    override fun onDismissDeviceSheet() = Unit
    override fun onOpenScheduleEditor(deviceId: Int) = Unit
    override fun onDismissScheduleEditor() = Unit
    override fun onChangeAccessMode(deviceId: Int, mode: AccessMode) = Unit
    override fun onStartDiscipline(
        deviceId: Int,
        downloadKbps: Int,
        uploadKbps: Int,
        durationMinutes: Int
    ) = Unit
    override fun onEndDiscipline(deviceId: Int) = Unit
    override fun onSaveSchedule(deviceId: Int, schedule: DeviceScheduleUiModel) = Unit
    override fun onSync() = Unit
    override fun onOpenSettings() = Unit
    override fun onDismissSettings() = Unit
    override fun onThemePreferenceChange(preference: ThemePreference) = Unit
    override fun onDynamicColorChange(enabled: Boolean) = Unit
    override fun onBiometricLockChange(enabled: Boolean) = Unit
    override fun onPreviewTunnelState(state: TunnelState) = Unit
    override fun onUnpairDevice() = Unit
    override fun onPair(deviceName: String, relayUrl: String, pairingSecret: String) = Unit
    override fun onRequestUnlock() = Unit
    override fun onUnlockResult(success: Boolean) = Unit
}
