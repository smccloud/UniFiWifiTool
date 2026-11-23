package com.smccloud.unifiwifitool.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.smccloud.unifiwifitool.data.keystore.SecureTokenStore
import com.smccloud.unifiwifitool.data.repository.AccessRepository
import com.smccloud.unifiwifitool.data.repository.AccessSnapshot
import com.smccloud.unifiwifitool.data.repository.DemoAccessRepository
import com.smccloud.unifiwifitool.data.repository.RemoteAccessRepository
import com.smccloud.unifiwifitool.data.repository.isControllerFailure
import com.smccloud.unifiwifitool.data.settings.AppSettingsStore
import com.smccloud.unifiwifitool.data.transport.TransportFactory
import com.smccloud.unifiwifitool.data.transport.TransportUnavailableException
import com.smccloud.unifiwifitool.ui.model.AccessMode
import com.smccloud.unifiwifitool.ui.model.AuditEntryUiModel
import com.smccloud.unifiwifitool.ui.model.DeviceScheduleUiModel
import com.smccloud.unifiwifitool.ui.model.DeviceUiModel
import com.smccloud.unifiwifitool.ui.model.PairedInstanceUiModel
import com.smccloud.unifiwifitool.ui.model.SampleUiData
import com.smccloud.unifiwifitool.ui.model.ThemePreference
import com.smccloud.unifiwifitool.ui.model.TunnelState
import com.smccloud.unifiwifitool.ui.navigation.AppDestination
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class AccessUiState(
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val useDynamicColor: Boolean = false,
    val biometricLockEnabled: Boolean = true,
    val isLocked: Boolean = false,
    val isPaired: Boolean = false,
    val demoMode: Boolean = false,
    val pairedInstance: PairedInstanceUiModel = SampleUiData.pairedInstance,
    val tunnelState: TunnelState = TunnelState.CONNECTED,
    val destination: AppDestination = AppDestination.OVERVIEW,
    val devices: List<DeviceUiModel> = emptyList(),
    val auditEntries: List<AuditEntryUiModel> = emptyList(),
    val searchQuery: String = "",
    val selectedFilter: String = "All",
    val activeDeviceId: Int? = null,
    val scheduleEditorDeviceId: Int? = null,
    val isSettingsOpen: Boolean = false,
    val isRefreshing: Boolean = false
) {
    val activeDevice: DeviceUiModel? get() = devices.firstOrNull { it.id == activeDeviceId }
    val scheduleEditorDevice: DeviceUiModel? get() = devices.firstOrNull { it.id == scheduleEditorDeviceId }
    val throttledCount: Int get() = devices.count { it.activeDiscipline != null }

    companion object {
        fun preview(): AccessUiState = AccessUiState(
            themePreference = ThemePreference.DARK,
            isPaired = true,
            demoMode = true,
            devices = SampleUiData.initialDevices,
            auditEntries = SampleUiData.initialAuditEntries
        )
    }
}

class AccessViewModel(application: Application) : AndroidViewModel(application), AccessActions {

    private val settings = AppSettingsStore(application)
    private val tokens = SecureTokenStore(application)
    private var repository: AccessRepository = buildRepository()

    private val _state = MutableStateFlow(initialState())
    val state = _state.asStateFlow()

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        refresh()
    }

    override fun onSelectDestination(destination: AppDestination) {
        _state.update { it.copy(destination = destination) }
    }

    override fun onSearchQueryChange(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    override fun onSelectFilter(filter: String) {
        _state.update { it.copy(selectedFilter = filter) }
    }

    override fun onOpenDevice(deviceId: Int) {
        _state.update { it.copy(activeDeviceId = deviceId) }
    }

    override fun onDismissDeviceSheet() {
        _state.update { it.copy(activeDeviceId = null) }
    }

    override fun onOpenScheduleEditor(deviceId: Int) {
        _state.update { it.copy(activeDeviceId = null, scheduleEditorDeviceId = deviceId) }
    }

    override fun onDismissScheduleEditor() {
        _state.update { it.copy(scheduleEditorDeviceId = null) }
    }

    override fun onOpenSettings() {
        _state.update { it.copy(isSettingsOpen = true) }
    }

    override fun onDismissSettings() {
        _state.update { it.copy(isSettingsOpen = false) }
    }

    override fun onThemePreferenceChange(preference: ThemePreference) {
        settings.themePreference = preference
        _state.update { it.copy(themePreference = preference) }
    }

    override fun onDynamicColorChange(enabled: Boolean) {
        settings.useDynamicColor = enabled
        _state.update { it.copy(useDynamicColor = enabled) }
    }

    override fun onBiometricLockChange(enabled: Boolean) {
        settings.biometricLockEnabled = enabled
        _state.update { it.copy(biometricLockEnabled = enabled) }
    }

    override fun onPreviewTunnelState(state: TunnelState) {
        _state.update { it.copy(tunnelState = state) }
    }

    override fun onSync() {
        viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true) }
            runCatching { repository.sync() }
                .onSuccess { applySnapshot(it); notify("Reconciled inventory with UniFi controller") }
                .onFailure { handleFailure(it) }
            _state.update { it.copy(isRefreshing = false) }
        }
    }

    override fun onChangeAccessMode(deviceId: Int, mode: AccessMode) {
        if (!canMutate()) return
        viewModelScope.launch {
            runCatching { repository.setAccessMode(deviceId, mode) }
                .onSuccess { updated ->
                    _state.update { current ->
                        current.copy(
                            devices = current.devices.map { if (it.id == updated.id) updated else it },
                            tunnelState = TunnelState.CONNECTED
                        )
                    }
                    notify("${updated.label} set to ${mode.label}")
                }
                .onFailure { handleFailure(it) }
        }
    }

    override fun onStartDiscipline(
        deviceId: Int,
        downloadKbps: Int,
        uploadKbps: Int,
        durationMinutes: Int
    ) {
        if (!canMutate()) return
        viewModelScope.launch {
            runCatching {
                repository.startDiscipline(deviceId, downloadKbps, uploadKbps, durationMinutes)
            }
                .onSuccess { limit ->
                    _state.update { current ->
                        current.copy(
                            devices = current.devices.map {
                                if (it.id == deviceId) {
                                    it.copy(
                                        activeDiscipline = limit.copy(
                                            deviceLabel = it.label,
                                            deviceMac = it.mac
                                        )
                                    )
                                } else {
                                    it
                                }
                            }
                        )
                    }
                    notify("Speed limit started: $downloadKbps↓/$uploadKbps↑ Kbps")
                }
                .onFailure { handleFailure(it) }
        }
    }

    override fun onEndDiscipline(deviceId: Int) {
        if (!canMutate()) return
        viewModelScope.launch {
            runCatching { repository.endDiscipline(deviceId) }
                .onSuccess {
                    _state.update { current ->
                        current.copy(
                            devices = current.devices.map {
                                if (it.id == deviceId) it.copy(activeDiscipline = null) else it
                            }
                        )
                    }
                    notify("Restored normal WiFi speed")
                }
                .onFailure { handleFailure(it) }
        }
    }

    override fun onSaveSchedule(deviceId: Int, schedule: DeviceScheduleUiModel) {
        if (!canMutate()) return
        viewModelScope.launch {
            runCatching { repository.saveSchedule(deviceId, schedule) }
                .onSuccess { updated ->
                    _state.update { current ->
                        current.copy(
                            devices = current.devices.map { if (it.id == updated.id) updated else it },
                            scheduleEditorDeviceId = null
                        )
                    }
                    notify("Saved schedule for ${updated.label}")
                }
                .onFailure { handleFailure(it) }
        }
    }

    override fun onUnpairDevice() {
        tokens.clearAll()
        settings.clearPairing()
        settings.demoMode = false
        repository = DemoAccessRepository()
        _state.update { current ->
            current.copy(
                isPaired = false,
                demoMode = false,
                isSettingsOpen = false,
                isLocked = false,
                devices = emptyList(),
                auditEntries = emptyList(),
                activeDeviceId = null,
                scheduleEditorDeviceId = null,
                tunnelState = TunnelState.CONNECTED
            )
        }
        notify("Device token wiped from the Android Keystore")
    }

    override fun onPair(deviceName: String, relayUrl: String, pairingSecret: String) {
        viewModelScope.launch {
            // The /api/mobile/pair endpoint lands with the backend side of issue #10.
            // Until then, pair into an offline demo repository so the full UI flow
            // can be exercised without a home instance.
            settings.mobileDeviceName = deviceName
            settings.relayEndpoint = relayUrl
            settings.instanceName = SampleUiData.pairedInstance.instanceName
            settings.instanceVersion = SampleUiData.pairedInstance.instanceVersion
            settings.tokenPrefix = "demo_pending"
            settings.pairedAtUtc = nowUtc()
            settings.demoMode = true
            repository = DemoAccessRepository()
            _state.update { current ->
                current.copy(
                    isPaired = true,
                    demoMode = true,
                    isLocked = false,
                    pairedInstance = SampleUiData.pairedInstance.copy(
                        mobileDeviceName = deviceName,
                        relayEndpoint = relayUrl,
                        tokenPrefix = "demo_pending",
                        pairedAtUtc = settings.pairedAtUtc ?: nowUtc(),
                        lastSyncLabel = "Demo data · not connected"
                    )
                )
            }
            notify("Paired in offline demo mode; backend pairing endpoint is pending")
            refresh()
        }
    }

    override fun onRequestUnlock() {
        _state.update { it.copy(isLocked = true) }
    }

    override fun onUnlockResult(success: Boolean) {
        if (success) {
            _state.update { it.copy(isLocked = false) }
            refresh()
        } else {
            notify("Biometric unlock failed")
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true) }
            runCatching { repository.snapshot() }
                .onSuccess {
                    applySnapshot(it)
                    _state.update { current -> current.copy(tunnelState = TunnelState.CONNECTED) }
                }
                .onFailure { handleFailure(it) }
            _state.update { it.copy(isRefreshing = false) }
        }
    }

    private fun applySnapshot(snapshot: AccessSnapshot) {
        _state.update { current ->
            current.copy(
                devices = snapshot.devices,
                auditEntries = snapshot.auditEntries,
                pairedInstance = current.pairedInstance.copy(
                    lastSyncLabel = "Updated ${nowUtc()}"
                )
            )
        }
    }

    private fun canMutate(): Boolean {
        if (!_state.value.tunnelState.mutationsAllowed) {
            notify("Home instance unreachable; no changes are queued offline")
            return false
        }
        return true
    }

    private fun handleFailure(error: Throwable) {
        _state.update { current ->
            current.copy(
                tunnelState = when {
                    error is TransportUnavailableException -> TunnelState.HOME_OFFLINE
                    error.isControllerFailure() -> TunnelState.CONTROLLER_UNREACHABLE
                    else -> current.tunnelState
                }
            )
        }
        notify(error.message ?: "Request failed")
    }

    private fun notify(message: String) {
        _events.trySend(message)
    }

    private fun initialState(): AccessUiState {
        val token = tokens.readDeviceToken()
        val demoMode = settings.demoMode
        val paired = token != null || demoMode
        return AccessUiState(
            themePreference = settings.themePreference,
            useDynamicColor = settings.useDynamicColor,
            biometricLockEnabled = settings.biometricLockEnabled,
            isLocked = paired && settings.biometricLockEnabled,
            isPaired = paired,
            demoMode = demoMode,
            pairedInstance = SampleUiData.pairedInstance.copy(
                instanceName = settings.instanceName ?: "Home UDM-SE",
                instanceVersion = settings.instanceVersion ?: "1.2.0",
                relayEndpoint = settings.relayEndpoint ?: SampleUiData.pairedInstance.relayEndpoint,
                mobileDeviceName = settings.mobileDeviceName ?: SampleUiData.pairedInstance.mobileDeviceName,
                tokenPrefix = settings.tokenPrefix ?: "demo_pending",
                pairedAtUtc = settings.pairedAtUtc ?: SampleUiData.pairedInstance.pairedAtUtc
            ),
            devices = emptyList(),
            auditEntries = emptyList()
        )
    }

    private fun buildRepository(): AccessRepository {
        val api = TransportFactory.create(settings, tokens) ?: return DemoAccessRepository()
        return RemoteAccessRepository(api)
    }

    private fun nowUtc(): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC).format(Instant.now()) + " UTC"

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer { AccessViewModel(application) }
        }
    }
}
