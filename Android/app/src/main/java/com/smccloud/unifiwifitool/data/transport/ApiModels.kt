package com.smccloud.unifiwifitool.data.transport

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire models for the planned mobile API (see issue #10).
 *
 * The backend exposes the existing `/api` JSON endpoints with snake_case fields;
 * these DTOs mirror the subset the app needs and keep Kotlin models camelCase.
 */

@Serializable
data class PairRequestDto(
    @SerialName("pairing_secret") val pairingSecret: String,
    @SerialName("device_name") val deviceName: String
)

@Serializable
data class PairResponseDto(
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_token") val deviceToken: String,
    @SerialName("token_prefix") val tokenPrefix: String,
    @SerialName("relay_session_key") val relaySessionKey: String? = null,
    @SerialName("instance_name") val instanceName: String,
    @SerialName("instance_version") val instanceVersion: String,
    @SerialName("paired_at") val pairedAt: String,
    val capabilities: List<String> = emptyList()
)

@Serializable
data class SystemStatusDto(
    @SerialName("app_version") val appVersion: String = "",
    @SerialName("controller_configured") val controllerConfigured: Boolean = false,
    @SerialName("controller_reachable") val controllerReachable: Boolean = false,
    @SerialName("capabilities") val capabilities: List<String> = emptyList(),
    @SerialName("device_count") val deviceCount: Int = 0,
    @SerialName("blocked_count") val blockedCount: Int = 0,
    @SerialName("last_sync_at") val lastSyncAt: String? = null
)

@Serializable
data class ScheduleWindowDto(
    val days: List<Int>,
    val start: String,
    val end: String,
    val action: String
)

@Serializable
data class ScheduleDto(
    @SerialName("default_access") val defaultAccess: String,
    val timezone: String,
    val windows: List<ScheduleWindowDto> = emptyList()
)

@Serializable
data class DisciplineDto(
    val id: Int? = null,
    @SerialName("device_id") val deviceId: Int,
    @SerialName("download_kbps") val downloadKbps: Int,
    @SerialName("upload_kbps") val uploadKbps: Int,
    @SerialName("started_at") val startedAt: String,
    @SerialName("expires_at") val expiresAt: String,
    val actor: String,
    @SerialName("end_reason") val endReason: String? = null
)

@Serializable
data class DeviceDto(
    val id: Int,
    val mac: String,
    val label: String = "",
    val hostname: String? = null,
    val notes: String = "",
    @SerialName("access_mode") val accessMode: String = "ignored",
    @SerialName("effective_access") val effectiveAccess: String = "ignored",
    @SerialName("observed_blocked") val observedBlocked: Boolean = false,
    @SerialName("last_seen") val lastSeen: String? = null,
    @SerialName("is_online") val isOnline: Boolean = false,
    val ip: String? = null,
    val ssid: String? = null,
    @SerialName("signal_dbm") val signalDbm: Int? = null,
    val schedule: ScheduleDto? = null,
    @SerialName("active_discipline") val activeDiscipline: DisciplineDto? = null
)

@Serializable
data class AuditEntryDto(
    val id: Int,
    val timestamp: String,
    val actor: String,
    val action: String,
    val result: String,
    @SerialName("target_mac") val targetMac: String? = null,
    @SerialName("target_label") val targetLabel: String? = null,
    val detail: String? = null
)

@Serializable
data class AccessModeRequestDto(
    @SerialName("access_mode") val accessMode: String
)

@Serializable
data class DisciplineRequestDto(
    @SerialName("download_kbps") val downloadKbps: Int,
    @SerialName("upload_kbps") val uploadKbps: Int,
    @SerialName("duration_minutes") val durationMinutes: Int
)

@Serializable
data class ErrorDto(
    val detail: String? = null
)
