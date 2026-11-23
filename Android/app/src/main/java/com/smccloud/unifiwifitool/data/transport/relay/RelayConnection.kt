package com.smccloud.unifiwifitool.data.transport.relay

import kotlinx.serialization.Serializable

/** Plaintext request carried inside an encrypted relay frame. */
@Serializable
data class RelayPayloadRequest(
    val method: String,
    val path: String,
    val body: String? = null,
    /** Bearer token stays inside the sealed payload, never in the relay envelope. */
    val token: String? = null
)

/** Plaintext response carried inside an encrypted relay frame. */
@Serializable
data class RelayPayloadResponse(
    val status: Int,
    val body: String
)

/** Outer envelope visible to the relay service. */
@Serializable
data class RelayFrame(
    val id: String,
    /** Base64(AES-GCM(iv || ciphertext)) */
    val payload: String
)

data class RelayRequest(
    val method: String,
    val path: String,
    val body: String? = null,
    val token: String? = null
)

data class RelayResponse(
    val status: Int,
    val body: String
)

/**
 * Outbound-only connection to the home instance through the relay service.
 * Implementations must never log payloads or tokens.
 */
interface RelayConnection {
    val endpointDescription: String

    suspend fun connect()

    suspend fun request(request: RelayRequest): RelayResponse

    fun close()
}
