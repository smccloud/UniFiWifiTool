package com.smccloud.unifiwifitool.data.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.CertificatePinner
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Direct HTTPS client for LAN, VPN, or a user-provided tunnel where the phone can
 * reach the instance itself. TLS verification stays on; callers may add
 * certificate pins for an explicit trust decision.
 */
class HttpAccessApi(
    private val baseUrl: String,
    private val tokenProvider: () -> String?,
    certificatePins: Map<String, List<String>> = emptyMap()
) : AccessApi {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .apply {
            if (certificatePins.isNotEmpty()) {
                certificatePinner(
                    CertificatePinner.Builder()
                        .apply {
                            certificatePins.forEach { (host, pins) ->
                                pins.forEach { pin -> add(host, pin) }
                            }
                        }
                        .build()
                )
            }
        }
        .build()

    override val endpointDescription: String = baseUrl

    override suspend fun pair(request: PairRequestDto): PairResponseDto =
        postAuthorized("/api/mobile/pair", json.encodeToString(request), authorized = false)

    override suspend fun systemStatus(): SystemStatusDto = get("/api/system/status")

    override suspend fun listDevices(search: String?): List<DeviceDto> {
        val url = urlBuilder("/api/devices")
        if (!search.isNullOrBlank()) url.addQueryParameter("search", search)
        return get(url.build().toString())
    }

    override suspend fun listAudit(limit: Int): List<AuditEntryDto> =
        get(urlBuilder("/api/audit").addQueryParameter("limit", limit.toString()).build().toString())

    override suspend fun setAccessMode(deviceId: Int, mode: String): DeviceDto =
        patch("/api/devices/$deviceId", json.encodeToString(AccessModeRequestDto(mode)))

    override suspend fun saveSchedule(deviceId: Int, schedule: ScheduleDto): DeviceDto =
        post("/api/devices/$deviceId/schedule", json.encodeToString(schedule))

    override suspend fun startDiscipline(deviceId: Int, request: DisciplineRequestDto): DisciplineDto =
        post("/api/devices/$deviceId/discipline", json.encodeToString(request))

    override suspend fun endDiscipline(deviceId: Int) {
        delete<Unit>("/api/devices/$deviceId/discipline")
    }

    override suspend fun sync(): SystemStatusDto = post("/api/system/sync", "{}")

    private suspend inline fun <reified T> post(path: String, body: String): T =
        execute("POST", path, body, authorized = true)

    private suspend inline fun <reified T> postAuthorized(
        path: String,
        body: String,
        authorized: Boolean
    ): T = execute("POST", path, body, authorized)

    private suspend inline fun <reified T> patch(path: String, body: String): T =
        execute("PATCH", path, body, authorized = true)

    private suspend inline fun <reified T> get(path: String): T =
        execute("GET", path, body = null, authorized = true)

    private suspend inline fun <reified T> delete(path: String): T =
        execute("DELETE", path, body = null, authorized = true)

    private suspend inline fun <reified T> execute(
        method: String,
        path: String,
        body: String?,
        authorized: Boolean
    ): T {
        val requestBuilder = Request.Builder().url(resolve(path))
        if (authorized) {
            val token = tokenProvider()
                ?: throw ApiException(401, "No paired device token")
            requestBuilder.header("Authorization", "Bearer $token")
        }
        when (method) {
            "GET" -> requestBuilder.get()
            "DELETE" -> requestBuilder.delete()
            "POST", "PATCH" -> requestBuilder.method(
                method,
                (body ?: "{}").toRequestBody(JSON_MEDIA_TYPE)
            )
            else -> error("Unsupported method $method")
        }

        val response = try {
            client.newCall(requestBuilder.build()).await()
        } catch (error: IOException) {
            throw TransportUnavailableException(
                error.message ?: "Home instance unreachable",
                error
            )
        }

        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                val detail = runCatching {
                    json.decodeFromString<ErrorDto>(text).detail
                }.getOrNull()
                throw ApiException(it.code, detail ?: text.take(200))
            }
            return if (T::class == Unit::class) {
                @Suppress("UNCHECKED_CAST")
                Unit as T
            } else {
                json.decodeFromString<T>(text)
            }
        }
    }

    private fun resolve(path: String): String =
        if (path.startsWith("http://") || path.startsWith("https://")) {
            path
        } else {
            baseUrl.trimEnd('/') + "/" + path.removePrefix("/")
        }

    private fun urlBuilder(path: String) = resolve(path).toHttpUrl().newBuilder()

    private suspend fun Call.await(): Response = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            enqueue(object : okhttp3.Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) continuation.resume(response)
                }
            })
            continuation.invokeOnCancellation { cancel() }
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
