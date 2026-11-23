package com.smccloud.unifiwifitool.data.settings

import android.content.Context
import com.smccloud.unifiwifitool.ui.model.ThemePreference

/**
 * Non-secret app preferences and pairing metadata.
 *
 * Secrets (the per-device bearer token and relay session key) live in
 * [com.smccloud.unifiwifitool.data.keystore.SecureTokenStore] instead.
 */
class AppSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("unifi_app_settings", Context.MODE_PRIVATE)

    var themePreference: ThemePreference
        get() = when (prefs.getString(KEY_THEME, null)) {
            "light" -> ThemePreference.LIGHT
            "dark" -> ThemePreference.DARK
            else -> ThemePreference.SYSTEM
        }
        set(value) = prefs.edit().putString(KEY_THEME, value.name.lowercase()).apply()

    var useDynamicColor: Boolean
        get() = prefs.getBoolean(KEY_DYNAMIC_COLOR, false)
        set(value) = prefs.edit().putBoolean(KEY_DYNAMIC_COLOR, value).apply()

    var biometricLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC, true)
        set(value) = prefs.edit().putBoolean(KEY_BIOMETRIC, value).apply()

    /**
     * True while the app is running against the local demo repository because the
     * backend `/api/mobile/pair` endpoint is not deployed yet.
     */
    var demoMode: Boolean
        get() = prefs.getBoolean(KEY_DEMO_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_DEMO_MODE, value).apply()

    var instanceName: String?
        get() = prefs.getString(KEY_INSTANCE_NAME, null)
        set(value) = prefs.edit().putString(KEY_INSTANCE_NAME, value).apply()

    var instanceVersion: String?
        get() = prefs.getString(KEY_INSTANCE_VERSION, null)
        set(value) = prefs.edit().putString(KEY_INSTANCE_VERSION, value).apply()

    var relayEndpoint: String?
        get() = prefs.getString(KEY_RELAY_ENDPOINT, null)
        set(value) = prefs.edit().putString(KEY_RELAY_ENDPOINT, value).apply()

    var apiBaseUrl: String?
        get() = prefs.getString(KEY_API_BASE_URL, null)
        set(value) = prefs.edit().putString(KEY_API_BASE_URL, value).apply()

    var mobileDeviceName: String?
        get() = prefs.getString(KEY_DEVICE_NAME, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_NAME, value).apply()

    var tokenPrefix: String?
        get() = prefs.getString(KEY_TOKEN_PREFIX, null)
        set(value) = prefs.edit().putString(KEY_TOKEN_PREFIX, value).apply()

    var pairedAtUtc: String?
        get() = prefs.getString(KEY_PAIRED_AT, null)
        set(value) = prefs.edit().putString(KEY_PAIRED_AT, value).apply()

    fun clearPairing() {
        prefs.edit()
            .remove(KEY_INSTANCE_NAME)
            .remove(KEY_INSTANCE_VERSION)
            .remove(KEY_RELAY_ENDPOINT)
            .remove(KEY_API_BASE_URL)
            .remove(KEY_DEVICE_NAME)
            .remove(KEY_TOKEN_PREFIX)
            .remove(KEY_PAIRED_AT)
            .apply()
    }

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_DYNAMIC_COLOR = "dynamic_color"
        const val KEY_BIOMETRIC = "biometric_lock"
        const val KEY_DEMO_MODE = "demo_mode"
        const val KEY_INSTANCE_NAME = "instance_name"
        const val KEY_INSTANCE_VERSION = "instance_version"
        const val KEY_RELAY_ENDPOINT = "relay_endpoint"
        const val KEY_API_BASE_URL = "api_base_url"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_TOKEN_PREFIX = "token_prefix"
        const val KEY_PAIRED_AT = "paired_at"
    }
}
