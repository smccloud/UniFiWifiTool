package com.smccloud.unifiwifitool.data.transport.relay

import com.smccloud.unifiwifitool.data.transport.AccessApi
import com.smccloud.unifiwifitool.data.transport.ApiException
import com.smccloud.unifiwifitool.data.transport.AuditEntryDto
import com.smccloud.unifiwifitool.data.transport.DeviceDto
import com.smccloud.unifiwifitool.data.transport.DisciplineDto
import com.smccloud.unifiwifitool.data.transport.DisciplineRequestDto
import com.smccloud.unifiwifitool.data.transport.ErrorDto
import com.smccloud.unifiwifitool.data.transport.PairRequestDto
import com.smccloud.unifiwifitool.data.transport.PairResponseDto
import com.smccloud.unifiwifitool.data.transport.ScheduleDto
import com.smccloud.unifiwifitool.data.transport.SystemStatusDto
import com.smccloud.unifiwifitool.data.transport.AccessModeRequestDto
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * [AccessApi] implementation that tunnels every request through the outbound,
 * end-to-end encrypted relay connection.
 */
class RelayAccessApi(
    private val connection: RelayConnection,
    private val tokenProvider: () -> String?
) : AccessApi {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    override val endpointDescription: String = connection.endpointDescription

    override suspend fun pair(request: PairRequestDto): PairResponseDto =
        call("POST", "/api/mobile/pair", json.encodeToString(request), includeToken = false)

    override suspend fun systemStatus(): SystemStatusDto = get("/api/system/status")

    override suspend fun listDevices(search: String?): List<DeviceDto> =
        get("/api/devices" + (search?.takeIf { it.isNotBlank() }?.let { "?search=${encode(it)}" } ?: ""))

    override suspend fun listAudit(limit: Int): List<AuditEntryDto> = get("/api/audit?limit=$limit")

    override suspend fun setAccessMode(deviceId: Int, mode: String): DeviceDto =
        call("PATCH", "/api/devices/$deviceId", json.encodeToString(AccessModeRequestDto(mode)))

    override suspend fun saveSchedule(deviceId: Int, schedule: ScheduleDto): DeviceDto =
        call("POST", "/api/devices/$deviceId/schedule", json.encodeToString(schedule))

    override suspend fun startDiscipline(deviceId: Int, request: DisciplineRequestDto): DisciplineDto =
        call("POST", "/api/devices/$deviceId/discipline", json.encodeToString(request))

    override suspend fun endDiscipline(deviceId: Int) {
        call<Unit>("DELETE", "/api/devices/$deviceId/discipline", null)
    }

    override suspend fun sync(): SystemStatusDto = call("POST", "/api/system/sync", "{}")

    private suspend inline fun <reified T> get(path: String): T = call("GET", path, null)

    private suspend inline fun <reified T> call(
        method: String,
        path: String,
        body: String?,
        includeToken: Boolean = true
    ): T {
        connection.connect()
        val response = connection.request(
            RelayRequest(
                method = method,
                path = path,
                body = body,
                token = if (includeToken) tokenProvider() else null
            )
        )
        if (response.status !in 200..299) {
            val detail = runCatching {
                json.decodeFromString<ErrorDto>(response.body).detail
            }.getOrNull()
            throw ApiException(response.status, detail ?: response.body.take(200))
        }
        return if (T::class == Unit::class) {
            @Suppress("UNCHECKED_CAST")
            Unit as T
        } else {
            json.decodeFromString<T>(response.body)
        }
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
}
