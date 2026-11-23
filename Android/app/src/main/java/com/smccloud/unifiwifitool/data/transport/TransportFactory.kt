package com.smccloud.unifiwifitool.data.transport

import com.smccloud.unifiwifitool.data.keystore.SecureTokenStore
import com.smccloud.unifiwifitool.data.settings.AppSettingsStore
import com.smccloud.unifiwifitool.data.transport.relay.AesGcmRelayCipher
import com.smccloud.unifiwifitool.data.transport.relay.RelayAccessApi
import com.smccloud.unifiwifitool.data.transport.relay.WebSocketRelayConnection

/**
 * Chooses the transport for the paired instance.
 *
 * - Relay mode (default): outbound WebSocket with per-frame AES-256-GCM.
 * - Direct mode: HTTPS to a LAN/VPN address the phone can already reach.
 *
 * When no pairing exists the caller falls back to the offline demo repository.
 */
object TransportFactory {

    fun create(
        settings: AppSettingsStore,
        tokens: SecureTokenStore
    ): AccessApi? {
        val token = tokens.readDeviceToken() ?: return null

        val relayUrl = settings.relayEndpoint?.takeIf { it.isNotBlank() }
        if (relayUrl != null) {
            val key = tokens.readRelaySessionKey() ?: return null
            return RelayAccessApi(
                connection = WebSocketRelayConnection(
                    url = relayUrl,
                    cipher = AesGcmRelayCipher(key)
                ),
                tokenProvider = { tokens.readDeviceToken() }
            )
        }

        val baseUrl = settings.apiBaseUrl?.takeIf { it.isNotBlank() } ?: return null
        return HttpAccessApi(
            baseUrl = baseUrl,
            tokenProvider = { token.ifBlank { null } }
        )
    }
}
