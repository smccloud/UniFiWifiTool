package com.smccloud.unifiwifitool.data.repository

import com.smccloud.unifiwifitool.data.transport.AccessApi
import com.smccloud.unifiwifitool.data.transport.ApiException
import com.smccloud.unifiwifitool.data.transport.AuditEntryDto
import com.smccloud.unifiwifitool.data.transport.DeviceDto
import com.smccloud.unifiwifitool.data.transport.DisciplineDto
import com.smccloud.unifiwifitool.data.transport.DisciplineRequestDto
import com.smccloud.unifiwifitool.data.transport.ScheduleDto
import com.smccloud.unifiwifitool.data.transport.ScheduleWindowDto
import com.smccloud.unifiwifitool.data.transport.SystemStatusDto
import com.smccloud.unifiwifitool.ui.model.AccessMode
import com.smccloud.unifiwifitool.ui.model.AuditEntryUiModel
import com.smccloud.unifiwifitool.ui.model.DeviceScheduleUiModel
import com.smccloud.unifiwifitool.ui.model.DeviceUiModel
import com.smccloud.unifiwifitool.ui.model.DisciplineUiModel
import com.smccloud.unifiwifitool.ui.model.SampleUiData
import com.smccloud.unifiwifitool.ui.model.ScheduleWindowUiModel
import com.smccloud.unifiwifitool.ui.model.WindowAction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class AccessSnapshot(
    val devices: List<DeviceUiModel>,
    val auditEntries: List<AuditEntryUiModel>,
    val status: SystemStatusDto?
)

interface AccessRepository {
    suspend fun snapshot(): AccessSnapshot

    suspend fun setAccessMode(deviceId: Int, mode: AccessMode): DeviceUiModel

    suspend fun startDiscipline(
        deviceId: Int,
        downloadKbps: Int,
        uploadKbps: Int,
        durationMinutes: Int
    ): DisciplineUiModel

    suspend fun endDiscipline(deviceId: Int)

    suspend fun saveSchedule(deviceId: Int, schedule: DeviceScheduleUiModel): DeviceUiModel

    suspend fun sync(): AccessSnapshot
}

/**
 * Repository backed by the tunnel/HTTPS API. The bearer token is read from the
 * Android Keystore on every request by the transport, never cached in memory
 * longer than necessary.
 */
class RemoteAccessRepository(private val api: AccessApi) : AccessRepository {

    override suspend fun snapshot(): AccessSnapshot {
        val devices = api.listDevices().map(::toUiModel)
        val audit = runCatching { api.listAudit() }.getOrDefault(emptyList()).map(::toUiModel)
        val status = runCatching { api.systemStatus() }.getOrNull()
        return AccessSnapshot(devices, audit, status)
    }

    override suspend fun setAccessMode(deviceId: Int, mode: AccessMode): DeviceUiModel =
        toUiModel(api.setAccessMode(deviceId, mode.wireValue))

    override suspend fun startDiscipline(
        deviceId: Int,
        downloadKbps: Int,
        uploadKbps: Int,
        durationMinutes: Int
    ): DisciplineUiModel = toUiModel(
        api.startDiscipline(
            deviceId,
            DisciplineRequestDto(downloadKbps, uploadKbps, durationMinutes)
        )
    )

    override suspend fun endDiscipline(deviceId: Int) = api.endDiscipline(deviceId)

    override suspend fun saveSchedule(
        deviceId: Int,
        schedule: DeviceScheduleUiModel
    ): DeviceUiModel = toUiModel(
        api.saveSchedule(
            deviceId,
            ScheduleDto(
                defaultAccess = schedule.defaultAccess.wireValue,
                timezone = schedule.timezone,
                windows = schedule.windows.map {
                    ScheduleWindowDto(
                        days = it.days.sorted(),
                        start = it.startTime,
                        end = it.endTime,
                        action = if (it.action == WindowAction.ALLOW) "allow" else "block"
                    )
                }
            )
        )
    )

    override suspend fun sync(): AccessSnapshot {
        api.sync()
        return snapshot()
    }
}

/**
 * Offline repository used before pairing (and for design review) so every screen
 * stays interactive without a home instance. Mutations are local only.
 */
class DemoAccessRepository : AccessRepository {

    private val mutex = Mutex()
    private var devices = SampleUiData.initialDevices
    private var audit = SampleUiData.initialAuditEntries

    override suspend fun snapshot(): AccessSnapshot = mutex.withLock {
        AccessSnapshot(devices, audit, null)
    }

    override suspend fun setAccessMode(deviceId: Int, mode: AccessMode): DeviceUiModel =
        mutex.withLock {
            val target = devices.first { it.id == deviceId }
            val effective = when (mode) {
                AccessMode.SCHEDULED -> AccessMode.ALLOWED
                else -> mode
            }
            devices = devices.map {
                if (it.id == deviceId) {
                    it.copy(
                        accessMode = mode,
                        effectiveAccess = effective,
                        observedBlocked = mode == AccessMode.BLOCKED
                    )
                } else {
                    it
                }
            }
            recordAudit(
                action = when (mode) {
                    AccessMode.BLOCKED -> "block"
                    AccessMode.ALLOWED -> "unblock"
                    AccessMode.SCHEDULED -> "schedule_enable"
                    AccessMode.IGNORED -> "device_ignore"
                },
                target = target
            )
            devices.first { it.id == deviceId }
        }

    override suspend fun startDiscipline(
        deviceId: Int,
        downloadKbps: Int,
        uploadKbps: Int,
        durationMinutes: Int
    ): DisciplineUiModel = mutex.withLock {
        val target = devices.first { it.id == deviceId }
        val limit = DisciplineUiModel(
            deviceId = target.id,
            deviceLabel = target.label,
            deviceMac = target.mac,
            downloadKbps = downloadKbps,
            uploadKbps = uploadKbps,
            startedAtUtc = nowLabel(),
            expiresAtUtc = "in ${durationMinutes}m",
            remainingLabel = "${durationMinutes}m left",
            progressFraction = 1f,
            actor = "mobile: ${SampleUiData.pairedInstance.mobileDeviceName}"
        )
        devices = devices.map {
            if (it.id == deviceId) it.copy(activeDiscipline = limit) else it
        }
        recordAudit(
            action = "discipline_start",
            target = target,
            detail = """{"download_kbps":$downloadKbps,"upload_kbps":$uploadKbps,"duration_minutes":$durationMinutes}"""
        )
        limit
    }

    override suspend fun endDiscipline(deviceId: Int) {
        mutex.withLock {
            val target = devices.first { it.id == deviceId }
            devices = devices.map {
                if (it.id == deviceId) it.copy(activeDiscipline = null) else it
            }
            recordAudit(action = "discipline_end", target = target)
        }
    }

    override suspend fun saveSchedule(
        deviceId: Int,
        schedule: DeviceScheduleUiModel
    ): DeviceUiModel = mutex.withLock {
        val target = devices.first { it.id == deviceId }
        devices = devices.map {
            if (it.id == deviceId) {
                it.copy(accessMode = AccessMode.SCHEDULED, schedule = schedule)
            } else {
                it
            }
        }
        recordAudit(
            action = "schedule_update",
            target = target,
            detail = """{"windows":${schedule.windows.size},"default_access":"${schedule.defaultAccess.wireValue}"}"""
        )
        devices.first { it.id == deviceId }
    }

    override suspend fun sync(): AccessSnapshot = snapshot()

    private fun recordAudit(action: String, target: DeviceUiModel, detail: String = "{}") {
        val entry = AuditEntryUiModel(
            id = (audit.maxOfOrNull { it.id } ?: 500) + 1,
            timestampUtc = nowLabel(),
            relativeTime = "Just now",
            action = action,
            result = "ok",
            targetLabel = target.label,
            targetMac = target.mac,
            actor = "mobile: ${SampleUiData.pairedInstance.mobileDeviceName}",
            detailJson = detail
        )
        audit = listOf(entry) + audit
    }

    private fun nowLabel(): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now())
}

private val AccessMode.wireValue: String
    get() = when (this) {
        AccessMode.ALLOWED -> "allowed"
        AccessMode.BLOCKED -> "blocked"
        AccessMode.SCHEDULED -> "scheduled"
        AccessMode.IGNORED -> "ignored"
    }

private fun parseAccessMode(value: String?): AccessMode = when (value?.lowercase()) {
    "allowed" -> AccessMode.ALLOWED
    "blocked" -> AccessMode.BLOCKED
    "scheduled" -> AccessMode.SCHEDULED
    else -> AccessMode.IGNORED
}

fun toUiModel(dto: DeviceDto): DeviceUiModel = DeviceUiModel(
    id = dto.id,
    label = dto.label.ifBlank { dto.hostname ?: dto.mac },
    hostname = dto.hostname,
    mac = dto.mac,
    ip = dto.ip,
    ssid = dto.ssid,
    signalDbm = dto.signalDbm,
    isOnline = dto.isOnline,
    lastSeenLabel = if (dto.isOnline) "Online now" else "Last seen ${dto.lastSeen ?: "unknown"}",
    accessMode = parseAccessMode(dto.accessMode),
    effectiveAccess = parseAccessMode(dto.effectiveAccess),
    observedBlocked = dto.observedBlocked,
    notes = dto.notes,
    schedule = dto.schedule?.let { schedule ->
        DeviceScheduleUiModel(
            defaultAccess = parseAccessMode(schedule.defaultAccess),
            timezone = schedule.timezone,
            windows = schedule.windows.mapIndexed { index, window ->
                ScheduleWindowUiModel(
                    id = "w$index",
                    days = window.days.toSet(),
                    startTime = window.start,
                    endTime = window.end,
                    action = if (window.action == "block") WindowAction.BLOCK else WindowAction.ALLOW
                )
            },
            plainEnglishSummary = "${schedule.windows.size} weekly window(s) configured.",
            nextTransitionLabel = null
        )
    },
    activeDiscipline = dto.activeDiscipline?.let(::toUiModel)
)

fun toUiModel(dto: DisciplineDto): DisciplineUiModel = DisciplineUiModel(
    deviceId = dto.deviceId,
    deviceLabel = "",
    deviceMac = "",
    downloadKbps = dto.downloadKbps,
    uploadKbps = dto.uploadKbps,
    startedAtUtc = dto.startedAt,
    expiresAtUtc = dto.expiresAt,
    remainingLabel = "Active",
    progressFraction = 1f,
    actor = dto.actor
)

fun toUiModel(dto: AuditEntryDto): AuditEntryUiModel = AuditEntryUiModel(
    id = dto.id,
    timestampUtc = dto.timestamp,
    relativeTime = dto.timestamp,
    action = dto.action,
    result = dto.result,
    targetLabel = dto.targetLabel,
    targetMac = dto.targetMac,
    actor = dto.actor,
    detailJson = dto.detail ?: "{}"
)

/** Maps non-2xx controller failures to the degraded banner instead of a crash. */
fun Throwable.isControllerFailure(): Boolean =
    this is ApiException && (statusCode == 502 || statusCode == 503)
