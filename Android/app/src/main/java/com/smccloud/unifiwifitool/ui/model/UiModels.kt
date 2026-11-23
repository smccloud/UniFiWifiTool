package com.smccloud.unifiwifitool.ui.model

enum class ThemePreference(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark")
}

enum class TunnelState(
    val pillLabel: String,
    val bannerTitle: String?,
    val bannerDetail: String?,
    val mutationsAllowed: Boolean
) {
    CONNECTED(
        pillLabel = "Relay E2EE · Live",
        bannerTitle = null,
        bannerDetail = null,
        mutationsAllowed = true
    ),
    CONTROLLER_UNREACHABLE(
        pillLabel = "Controller Unreachable",
        bannerTitle = "UniFi controller is unreachable from home instance",
        bannerDetail = "Inventory and audit log remain available read-only. Access mutations are disabled until the controller responds.",
        mutationsAllowed = false
    ),
    HOME_OFFLINE(
        pillLabel = "Home Offline",
        bannerTitle = "Home instance is offline",
        bannerDetail = "Showing cached inventory from 4m ago. No destructive actions are queued while offline.",
        mutationsAllowed = false
    )
}

enum class AccessMode(val label: String, val badgeLabel: String) {
    ALLOWED("Allow", "ALLOWED"),
    BLOCKED("Block", "BLOCKED"),
    SCHEDULED("Schedule", "SCHEDULED"),
    IGNORED("Ignore", "IGNORED")
}

enum class WindowAction(val label: String) {
    ALLOW("Allow"),
    BLOCK("Block")
}

data class ScheduleWindowUiModel(
    val id: String,
    val days: Set<Int>, // 0 = Mon .. 6 = Sun
    val startTime: String,
    val endTime: String,
    val action: WindowAction
)

data class DeviceScheduleUiModel(
    val defaultAccess: AccessMode, // ALLOWED or BLOCKED
    val timezone: String,
    val windows: List<ScheduleWindowUiModel>,
    val plainEnglishSummary: String,
    val nextTransitionLabel: String?
)

data class DisciplineUiModel(
    val deviceId: Int,
    val deviceLabel: String,
    val deviceMac: String,
    val downloadKbps: Int,
    val uploadKbps: Int,
    val startedAtUtc: String,
    val expiresAtUtc: String,
    val remainingLabel: String,
    val progressFraction: Float,
    val actor: String
)

data class DeviceUiModel(
    val id: Int,
    val label: String,
    val hostname: String?,
    val mac: String,
    val ip: String?,
    val ssid: String?,
    val signalDbm: Int?,
    val isOnline: Boolean,
    val lastSeenLabel: String,
    val accessMode: AccessMode,
    val effectiveAccess: AccessMode,
    val observedBlocked: Boolean,
    val notes: String,
    val schedule: DeviceScheduleUiModel?,
    val activeDiscipline: DisciplineUiModel?
)

data class SpeedPresetUiModel(
    val id: String,
    val title: String,
    val subtitle: String,
    val downloadKbps: Int,
    val uploadKbps: Int
)

data class DurationPresetUiModel(
    val minutes: Int,
    val label: String
)

data class AuditEntryUiModel(
    val id: Int,
    val timestampUtc: String,
    val relativeTime: String,
    val action: String,
    val result: String, // "ok", "error", "dry_run"
    val targetLabel: String?,
    val targetMac: String?,
    val actor: String,
    val detailJson: String
)

data class PairedInstanceUiModel(
    val instanceName: String,
    val instanceVersion: String,
    val relayEndpoint: String,
    val mobileDeviceName: String,
    val tokenPrefix: String,
    val pairedAtUtc: String,
    val lastSyncLabel: String,
    val capabilities: List<String>
)

object SampleUiData {
    val speedPresets = listOf(
        SpeedPresetUiModel("1m", "1 Mbps", "1000 ↓ / 1000 ↑ Kbps", 1000, 1000),
        SpeedPresetUiModel("768k", "768 Kbps", "768 ↓ / 768 ↑ Kbps", 768, 768),
        SpeedPresetUiModel("56k_asym", "56 / 33.6 Kbps", "56 ↓ / 34 ↑ Kbps (V.90)", 56, 34),
        SpeedPresetUiModel("33k", "33.6 Kbps", "34 ↓ / 34 ↑ Kbps", 34, 34),
        SpeedPresetUiModel("28k", "28.8 Kbps", "29 ↓ / 29 ↑ Kbps", 29, 29),
        SpeedPresetUiModel("14k", "14.4 Kbps", "14 ↓ / 14 ↑ Kbps", 14, 14)
    )

    val durationPresets = listOf(
        DurationPresetUiModel(15, "15m"),
        DurationPresetUiModel(30, "30m"),
        DurationPresetUiModel(60, "1h"),
        DurationPresetUiModel(120, "2h"),
        DurationPresetUiModel(480, "8h")
    )

    val pairedInstance = PairedInstanceUiModel(
        instanceName = "Home UDM-SE",
        instanceVersion = "1.2.0",
        relayEndpoint = "wss://relay.smccloud.com/v1/tunnel",
        mobileDeviceName = "Shaun's Pixel 9 Pro",
        tokenPrefix = "uwm_dev_9f3a…",
        pairedAtUtc = "2026-11-20 14:10 UTC",
        lastSyncLabel = "Updated 18s ago",
        capabilities = listOf("Integration API v1", "Classic Block/Unblock", "Bandwidth Profiles")
    )

    val initialDevices = listOf(
        DeviceUiModel(
            id = 1,
            label = "Living Room Apple TV",
            hostname = "Apple-TV-4K",
            mac = "a4:83:e7:19:4b:10",
            ip = "192.168.1.42",
            ssid = "Home-5G",
            signalDbm = -54,
            isOnline = true,
            lastSeenLabel = "Online now",
            accessMode = AccessMode.ALLOWED,
            effectiveAccess = AccessMode.ALLOWED,
            observedBlocked = false,
            notes = "Main family media player",
            schedule = null,
            activeDiscipline = null
        ),
        DeviceUiModel(
            id = 2,
            label = "Kids iPad Air",
            hostname = "iPad-Air",
            mac = "d8:bb:2c:81:09:ef",
            ip = "192.168.1.118",
            ssid = "Home-Kids",
            signalDbm = -61,
            isOnline = true,
            lastSeenLabel = "Online now",
            accessMode = AccessMode.SCHEDULED,
            effectiveAccess = AccessMode.ALLOWED,
            observedBlocked = false,
            notes = "School nights bedtime cutoff at 20:30",
            schedule = DeviceScheduleUiModel(
                defaultAccess = AccessMode.BLOCKED,
                timezone = "America/Chicago",
                windows = listOf(
                    ScheduleWindowUiModel(
                        id = "w1",
                        days = setOf(0, 1, 2, 3, 4),
                        startTime = "15:30",
                        endTime = "20:30",
                        action = WindowAction.ALLOW
                    ),
                    ScheduleWindowUiModel(
                        id = "w2",
                        days = setOf(5, 6),
                        startTime = "08:00",
                        endTime = "21:30",
                        action = WindowAction.ALLOW
                    )
                ),
                plainEnglishSummary = "Allowed Mon–Fri 15:30–20:30 and Sat–Sun 08:00–21:30; blocked otherwise.",
                nextTransitionLabel = "Blocks today at 20:30"
            ),
            activeDiscipline = DisciplineUiModel(
                deviceId = 2,
                deviceLabel = "Kids iPad Air",
                deviceMac = "d8:bb:2c:81:09:ef",
                downloadKbps = 56,
                uploadKbps = 34,
                startedAtUtc = "2026-11-23 16:00 UTC",
                expiresAtUtc = "2026-11-23 17:00 UTC",
                remainingLabel = "38m left",
                progressFraction = 0.63f,
                actor = "mobile: Shaun's Pixel 9 Pro"
            )
        ),
        DeviceUiModel(
            id = 3,
            label = "Gaming Console PS5",
            hostname = "PlayStation-5",
            mac = "70:9e:29:4c:33:a1",
            ip = "192.168.1.145",
            ssid = "Home-5G",
            signalDbm = -58,
            isOnline = false,
            lastSeenLabel = "Last seen 22m ago",
            accessMode = AccessMode.BLOCKED,
            effectiveAccess = AccessMode.BLOCKED,
            observedBlocked = true,
            notes = "Blocked until homework finished",
            schedule = null,
            activeDiscipline = null
        ),
        DeviceUiModel(
            id = 4,
            label = "Bedroom Nintendo Switch",
            hostname = "Nintendo-Switch",
            mac = "98:41:5c:0e:72:19",
            ip = "192.168.1.163",
            ssid = "Home-Kids",
            signalDbm = -68,
            isOnline = true,
            lastSeenLabel = "Online now",
            accessMode = AccessMode.SCHEDULED,
            effectiveAccess = AccessMode.ALLOWED,
            observedBlocked = false,
            notes = "Evening window only",
            schedule = DeviceScheduleUiModel(
                defaultAccess = AccessMode.ALLOWED,
                timezone = "America/Chicago",
                windows = listOf(
                    ScheduleWindowUiModel(
                        id = "w3",
                        days = setOf(0, 1, 2, 3, 4, 5, 6),
                        startTime = "22:00",
                        endTime = "07:00",
                        action = WindowAction.BLOCK
                    )
                ),
                plainEnglishSummary = "Blocked daily 22:00–07:00 (overnight); allowed otherwise.",
                nextTransitionLabel = "Blocks at 22:00"
            ),
            activeDiscipline = null
        ),
        DeviceUiModel(
            id = 5,
            label = "Roborock Vacuum",
            hostname = "roborock-s8",
            mac = "50:ec:50:3a:81:04",
            ip = "192.168.20.19",
            ssid = "Home-IoT",
            signalDbm = -71,
            isOnline = true,
            lastSeenLabel = "Online now",
            accessMode = AccessMode.IGNORED,
            effectiveAccess = AccessMode.IGNORED,
            observedBlocked = false,
            notes = "Unmanaged IoT client",
            schedule = null,
            activeDiscipline = null
        )
    )

    val initialAuditEntries = listOf(
        AuditEntryUiModel(
            id = 504,
            timestampUtc = "2026-11-23 16:00 UTC",
            relativeTime = "22m ago",
            action = "discipline_start",
            result = "ok",
            targetLabel = "Kids iPad Air",
            targetMac = "d8:bb:2c:81:09:ef",
            actor = "mobile: Shaun's Pixel 9 Pro",
            detailJson = """{"download_kbps":56,"upload_kbps":34,"duration_minutes":60,"transport":"relay_e2ee"}"""
        ),
        AuditEntryUiModel(
            id = 503,
            timestampUtc = "2026-11-23 15:42 UTC",
            relativeTime = "40m ago",
            action = "block",
            result = "ok",
            targetLabel = "Gaming Console PS5",
            targetMac = "70:9e:29:4c:33:a1",
            actor = "mobile: Shaun's Pixel 9 Pro",
            detailJson = """{"reason":"manual_mode_change","previous_mode":"allowed","new_mode":"blocked"}"""
        ),
        AuditEntryUiModel(
            id = 502,
            timestampUtc = "2026-11-23 12:15 UTC",
            relativeTime = "4h ago",
            action = "schedule_apply",
            result = "ok",
            targetLabel = "Bedroom Nintendo Switch",
            targetMac = "98:41:5c:0e:72:19",
            actor = "system: scheduler",
            detailJson = """{"window":"22:00-07:00","effective":"allowed"}"""
        ),
        AuditEntryUiModel(
            id = 501,
            timestampUtc = "2026-11-20 14:10 UTC",
            relativeTime = "3d ago",
            action = "mobile_pair",
            result = "ok",
            targetLabel = "Shaun's Pixel 9 Pro",
            targetMac = null,
            actor = "web: admin",
            detailJson = """{"token_prefix":"uwm_dev_9f3a","scopes":["read","mutate_access","discipline","audit"]}"""
        )
    )
}
