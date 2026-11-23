package com.smccloud.unifiwifitool.data.transport.relay

import com.smccloud.unifiwifitool.data.transport.TransportUnavailableException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import android.util.Base64

/**
 * WebSocket transport to the relay. Each request is sealed with the pairing
 * session key, so the relay can only observe frame sizes and timing.
 */
class WebSocketRelayConnection(
    private val url: String,
    private val cipher: RelayCipher,
    private val client: OkHttpClient = defaultClient()
) : RelayConnection {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val pending = ConcurrentHashMap<String, CompletableDeferred<RelayResponse>>()
    private var webSocket: WebSocket? = null

    override val endpointDescription: String = url

    override suspend fun connect() {
        if (webSocket != null) return

        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder().url(url).build()
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    this@WebSocketRelayConnection.webSocket = webSocket
                    if (continuation.isActive) continuation.resume(Unit)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching { json.decodeFromString<RelayFrame>(text) }
                        .onSuccess { frame ->
                            val deferred = pending.remove(frame.id) ?: return
                            runCatching {
                                val plaintext = cipher.open(Base64.decode(frame.payload, Base64.NO_WRAP))
                                json.decodeFromString<RelayPayloadResponse>(
                                    plaintext.toString(Charsets.UTF_8)
                                )
                            }.onSuccess { payload ->
                                deferred.complete(RelayResponse(payload.status, payload.body))
                            }.onFailure { error ->
                                deferred.completeExceptionally(
                                    TransportUnavailableException("Malformed relay frame", error)
                                )
                            }
                        }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    this@WebSocketRelayConnection.webSocket = null
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            TransportUnavailableException(t.message ?: "Relay connection failed", t)
                        )
                    }
                    failAll(t)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    this@WebSocketRelayConnection.webSocket = null
                    failAll(TransportUnavailableException("Relay connection closed: $reason"))
                }
            }

            val socket = client.newWebSocket(request, listener)
            continuation.invokeOnCancellation {
                socket.close(1000, "cancelled")
                this@WebSocketRelayConnection.webSocket = null
            }
        }
    }

    override suspend fun request(request: RelayRequest): RelayResponse {
        val socket = webSocket ?: throw TransportUnavailableException("Relay is not connected")

        val id = UUID.randomUUID().toString()
        val inner = json.encodeToString(
            RelayPayloadRequest(
                method = request.method,
                path = request.path,
                body = request.body,
                token = request.token
            )
        )
        val sealed = cipher.seal(inner.toByteArray(Charsets.UTF_8))
        val frame = json.encodeToString(
            RelayFrame(id = id, payload = Base64.encodeToString(sealed, Base64.NO_WRAP))
        )

        val deferred = CompletableDeferred<RelayResponse>()
        pending[id] = deferred

        if (!socket.send(frame)) {
            pending.remove(id)
            throw TransportUnavailableException("Relay connection is closing")
        }

        return try {
            withTimeout(REQUEST_TIMEOUT_MS) { deferred.await() }
        } catch (error: Exception) {
            pending.remove(id)
            throw TransportUnavailableException("Relay request timed out", error)
        }
    }

    override fun close() {
        webSocket?.close(1000, "client closed")
        webSocket = null
        failAll(TransportUnavailableException("Relay connection closed"))
    }

    private fun failAll(error: Throwable) {
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 20_000L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
