package com.smccloud.unifiwifitool.data.transport

/**
 * Transport-agnostic client for the UniFi WiFi Access Manager mobile API.
 *
 * Implementations must send the device bearer token on every request and must
 * never log request or response bodies: they can contain SSIDs, MAC addresses,
 * and audit details.
 */
interface AccessApi {
    val endpointDescription: String

    suspend fun pair(request: PairRequestDto): PairResponseDto

    suspend fun systemStatus(): SystemStatusDto

    suspend fun listDevices(search: String? = null): List<DeviceDto>

    suspend fun listAudit(limit: Int = 50): List<AuditEntryDto>

    suspend fun setAccessMode(deviceId: Int, mode: String): DeviceDto

    suspend fun saveSchedule(deviceId: Int, schedule: ScheduleDto): DeviceDto

    suspend fun startDiscipline(deviceId: Int, request: DisciplineRequestDto): DisciplineDto

    suspend fun endDiscipline(deviceId: Int)

    suspend fun sync(): SystemStatusDto
}

/** Raised when the home instance or relay cannot be reached. */
class TransportUnavailableException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** Raised when the server rejects the request (auth, validation, controller error). */
class ApiException(
    val statusCode: Int,
    val detail: String?
) : Exception(detail ?: "HTTP $statusCode")
