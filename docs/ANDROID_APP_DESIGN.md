# UniFi WiFi Access Manager — Android App Design & M3 Framework

Status: Design & UI/UX Framework (Issue #10)  
Target Platform: Android 10+ (API 29+), Jetpack Compose, Material Design 3 (Material You)

---

## 1. Design Philosophy & Scope

The Android companion app provides fast, high-confidence remote control of a home UniFi WiFi Access Manager instance over an **outbound-only encrypted relay** (or direct LAN/VPN fallback) without exposing the Web UI to the public internet.

### Core Principles
- **Standard Material Design 3 (M3)**: Uses canonical M3 components, tokens, elevation overlays, and motion so the app feels native on modern Android devices.
- **First-class Light & Dark Modes**: Supports `System default`, `Light`, and `Dark` theme modes, plus an optional **Material You Dynamic Color** toggle on Android 12+ (defaulting to the UniFi WiFi Access Manager brand teal/slate seed palette so status semantics remain unmistakable).
- **Zero-ambiguous State**: Because actions affect live home network access, the UI clearly distinguishes **Observed Controller State**, **Effective Policy State**, and **Offline / Stale Tunnel State**.
- **Guardrails on Destructive Actions**: Blocking a device, starting Discipline Mode, or clearing a schedule requires fresh state verification and an explicit confirmation surface (M3 Modal Bottom Sheet or Alert Dialog).

---

## 2. Material Design 3 Theme Framework (Light & Dark Mode)

The brand palette is anchored to the Web UI's signature cyan-teal accent (`#30D6C4` dark / `#0F766E` light) and deep slate surfaces, mapped onto full Material 3 color roles.

### 2.1 Core M3 Color Roles

| M3 Color Role | Light Mode Hex | Dark Mode Hex | Usage |
| :--- | :--- | :--- | :--- |
| `primary` | `#0F766E` | `#30D6C4` | Primary FABs, active indicators, filled buttons, key toggles |
| `onPrimary` | `#FFFFFF` | `#05201D` | Text and icons on `primary` |
| `primaryContainer` | `#CCFBF1` | `#0D3B38` | Selected filter chips, active navigation pill, tonal buttons |
| `onPrimaryContainer` | `#042F2E` | `#A7F3D0` | Text/icons inside `primaryContainer` |
| `secondary` | `#2F6FED` | `#6AA2FF` | Scheduled mode accents, secondary informational badges |
| `onSecondary` | `#FFFFFF` | `#081E4B` | Text/icons on `secondary` |
| `secondaryContainer` | `#DBEAFE` | `#172B4D` | Schedule window pills, time-limit chips |
| `onSecondaryContainer` | `#1E3A8A` | `#C2D6FF` | Text/icons inside `secondaryContainer` |
| `tertiary` | `#B7791F` | `#F6B955` | Discipline Mode (speed limit) active indicators & timers |
| `onTertiary` | `#FFFFFF` | `#2C1A00` | Text/icons on `tertiary` |
| `tertiaryContainer` | `#FEF3C7` | `#3D2808` | Discipline Mode cards, Kbps preset chips |
| `onTertiaryContainer` | `#78350F` | `#FFD188` | Text/icons inside `tertiaryContainer` |
| `error` | `#D93A4A` | `#FF7080` | Blocked device state, revoked token, tunnel/controller error |
| `onError` | `#FFFFFF` | `#3B050C` | Text/icons on `error` |
| `errorContainer` | `#FFE4E6` | `#3B1219` | Block confirmation action, offline/error banners |
| `onErrorContainer` | `#881337` | `#FFC0C6` | Text/icons inside `errorContainer` |

### 2.2 Surface & Container Hierarchy

M3 replaces drop-shadow elevation with tonal surface containers in dark mode and subtle crisp borders/surfaces in light mode.

| M3 Surface Role | Light Mode Hex | Dark Mode Hex | Component Mapping |
| :--- | :--- | :--- | :--- |
| `background` | `#F3F6FB` | `#070C16` | Root app canvas behind scrollable content |
| `onBackground` | `#122033` | `#E7EEFB` | Primary body copy |
| `surface` | `#F7F9FD` | `#0A1220` | Scaffold background, top app bar at rest |
| `surfaceDim` | `#E4EAF3` | `#070C16` | Disabled containers, dimmed backdrop |
| `surfaceBright` | `#FFFFFF` | `#1A2942` | Elevated menus, popovers |
| `surfaceContainerLowest` | `#FFFFFF` | `#050911` | Inset code/MAC blocks, technical logs |
| `surfaceContainerLow` | `#FFFFFF` | `#0E1829` | Default device list cards, audit row cards |
| `surfaceContainer` | `#F5F9FE` | `#13203A` | Navigation bar, search bar, pinned headers |
| `surfaceContainerHigh` | `#EEF3FA` | `#182744` | Modal bottom sheets, dialogs, active cards |
| `surfaceContainerHighest` | `#E4EAF3` | `#1F3152` | Pressed states, text field fills |
| `onSurface` | `#122033` | `#E7EEFB` | Primary card headlines and device labels |
| `onSurfaceVariant` | `#5D6D84` | `#8EA2C0` | Secondary metadata (MAC, IP, SSID, timestamps) |
| `outline` | `#D7E0EC` | `#1F314D` | Card borders, input outlines, dividers |
| `outlineVariant` | `#E8EEF6` | `#182740` | Subtle list row separators |

### 2.3 Semantic Status Tokens (Static across Dynamic Color)

Even when a user enables Android 12+ Dynamic Color, network access states use harmonized semantic tokens so safety-critical states never shift meaning:

| Semantic State | Light Container / Ink | Dark Container / Ink | Icon |
| :--- | :--- | :--- | :--- |
| **Allowed / Online** | `#E6F7EE` / `#08663C` | `#102B23` / `#7BF0B2` | `check_circle` |
| **Blocked** | `#FDECEE` / `#A51F2D` | `#2E121B` / `#FF9CA6` | `block` |
| **Scheduled** | `#EBF2FF` / `#1E4FA8` | `#132242` / `#C2D6FF` | `schedule` |
| **Discipline (Throttled)** | `#FEF5E6` / `#8A5A12` | `#2E2110` / `#FFD188` | `speed` |
| **Unmanaged / Ignored** | `#EEF3FA` / `#5D6D84` | `#13203A` / `#8EA2C0` | `remove_circle_outline` |

### 2.4 Theme Switching Behavior
- **Options**: `System default` (follows `isSystemInDarkTheme()`), `Light`, `Dark`.
- **Dynamic Color**: Optional switch (`Use device wallpaper colors`), off by default so the UniFi WiFi Access Manager palette is preserved out of the box.
- **System Bars**: Edge-to-edge rendering (`enableEdgeToEdge()`) with transparent status and navigation bars; status bar icons automatically flip between light and dark appearance based on the active surface.

---

## 3. Typography, Shapes & Layout Grid

### 3.1 Typography Scale
- **Primary UI Font**: System Sans (`Roboto` / `Google Sans` via default M3 `Typography`).
- **Monospace Font**: `JetBrains Mono` (or system monospace fallback) exclusively for MAC addresses, IPv4/IPv6 addresses, Kbps figures, and audit request IDs.

| M3 Text Style | Size / Line Height | Weight | App Usage |
| :--- | :--- | :--- | :--- |
| `headlineMedium` | 28sp / 36sp | SemiBold | Screen titles in Large TopAppBar / Pairing welcome |
| `titleLarge` | 22sp / 28sp | SemiBold | TopAppBar title, bottom sheet device header |
| `titleMedium` | 16sp / 24sp | Medium | Device card primary label, section headers |
| `bodyLarge` | 16sp / 24sp | Regular | Form inputs, confirmation dialog copy |
| `bodyMedium` | 14sp / 20sp | Regular | Schedule plain-English summary, audit description |
| `labelLarge` | 14sp / 20sp | Medium | Button labels, filter chip text, segmented buttons |
| `labelMedium` | 12sp / 16sp | Medium | Status badges (`ALLOWED`, `BLOCKED`, `56 KBPS`) |
| `labelSmall` (Mono) | 11sp / 16sp | Regular | MAC address (`aa:bb:cc:dd:ee:ff`), IP, last-seen age |

### 3.2 Shape Scale
- `extraSmall` (`4.dp`): Technical MAC/IP inline tags.
- `small` (`8.dp`): Status badges, filter chips, schedule day pills.
- `medium` (`14.dp`): Standard device cards, audit entry cards, summary tiles (matches the Web UI's `14px` radius).
- `large` (`20.dp`): Navigation drawer, large status banners.
- `extraLarge` (`28.dp`): Modal Bottom Sheets, Alert Dialogs, Search Bar pill.

### 3.3 Responsive Layout & Spacing
- **Compact Width (Phones < 600dp)**: 4-column margin grid, `16.dp` horizontal screen padding, `12.dp` vertical card gap, bottom `NavigationBar`.
- **Medium/Expanded Width (Foldables & Tablets ≥ 600dp)**: 8/12-column grid, left `NavigationRail`, list-detail two-pane layout (`ListDetailPaneScaffold`) for Devices and Audit Log.
- **Touch Targets**: Minimum `48.dp × 48.dp` on all interactive controls.

---

## 4. Navigation Architecture & Screen Map

### 4.1 Top-Level Navigation (`NavigationBar` / `NavigationRail`)

Four primary destinations focused on remote day-to-day operations:

1. **Overview** (`dashboard` icon)
   - Live connection & tunnel health banner, active counts, active Discipline timers, and quick actions.
2. **Devices** (`devices` icon)
   - Curated inventory + live connected client filter, search, one-tap status sheet, schedule & Discipline controls.
3. **Discipline** (`speed` icon)
   - Dedicated view of all currently throttled devices with live countdowns, one-tap **Restore speed**, and quick-start presets.
4. **Audit** (`history` icon)
   - Filterable activity timeline showing both Web UI and attributed mobile actions (e.g., `mobile: Shaun's Pixel`).

*(App settings, theme mode, biometric lock, and pairing management live in the top-right **Settings / Connection** sheet accessed from the TopAppBar.)*

```
┌──────────────────────────────────────────────────────────┐
│ TopAppBar: [Instance Name + Tunnel Pill]   [Sync] [Gear] │
├──────────────────────────────────────────────────────────┤
│                                                          │
│                  Active Screen Content                   │
│             (Pull-to-Refresh + Offline Banner)           │
│                                                          │
├─────────────┬──────────────┬───────────────┬─────────────┤
│  Overview   │   Devices    │  Discipline   │    Audit    │
└─────────────┴──────────────┴───────────────┴─────────────┘
```

---

## 5. Screen-by-Screen UI Framework

### 5.1 Onboarding & QR Pairing Flow (First Launch / Unpaired)
1. **Welcome & Security Explainer**: Explains that no inbound ports are needed; offers **Scan Pairing QR Code** (primary filled button) or **Enter Connection Details Manually** (text button).
2. **QR Scanner Screen**: Camera preview with an M3 rounded viewfinder cutout, torch toggle, and automatic validation of the short-lived pairing URI (`unifiwifi://pair?relay=...&instance=...&secret=...`).
3. **Device Naming & Biometric Setup**:
   - Pre-fills the device name (e.g., `Pixel 9 Pro`) which will appear in the Web UI's Paired Devices list and audit logs.
   - Toggle to enable **Biometric App Lock** (fingerprint / face unlock via Android `BiometricPrompt`).

### 5.2 Overview Screen
- **Connection Status Pill (TopAppBar)**:
  - `Connected · Relay E2EE` (green dot)
  - `Stale · Last updated 4m ago` (amber dot)
  - `Home Instance Offline` (red dot)
- **Summary Bento Grid (2×2 M3 Tonal Cards)**:
  - **Allowed** count, **Blocked** count, **Scheduled** count, **Throttled (Discipline)** count. Tapping any tile jumps to the Devices tab pre-filtered to that state.
- **Active Discipline Section**:
  - Lists any devices currently under a speed limit with a progress bar, remaining time (`23m left` / `Expires 18:30`), effective Kbps (`56 ↓ / 34 ↑ Kbps`), and an inline **End** tonal button.
- **Controller & Enforcement Card**:
  - Shows UniFi controller health, active capabilities (`Integration API`, `Classic Block/Unblock`, `Bandwidth Profiles`), and a **Sync Now** button.
- **Recent Activity Preview**:
  - Last 3 audit entries with a **View all** link.

### 5.3 Devices Screen & Device Detail Bottom Sheet
- **Docked M3 SearchBar**: Searches by device label, hostname, MAC, IP, or SSID.
- **Horizontal Filter Chip Row**:
  - `All`, `Online now`, `Allowed`, `Blocked`, `Scheduled`, `Throttled`, `Ignored`.
- **Device Card Layout (`surfaceContainerLow`)**:
  - **Row 1**: Device label (or hostname fallback) + primary semantic status badge (`ALLOWED`, `BLOCKED`, `SCHEDULED`).
  - **Row 2**: Online indicator dot + SSID + signal dBm chip (when online) or `Last seen 2h ago` (when offline).
  - **Row 3**: Monospace MAC (`aa:bb:cc:dd:ee:ff`) and IP + next schedule transition (`Blocks at 22:00`) or active speed-limit pill (`34 Kbps until 17:00`).
  - **Quick Action**: Tapping the card opens the **Device Control Bottom Sheet**; long-pressing or tapping the trailing action button opens a fast **Allow / Block / Throttle** confirmation sheet.

#### Device Control Modal Bottom Sheet (`ModalBottomSheet`)
- **Header**: Device name, editable label/notes icon, MAC/IP/SSID summary, and live controller sync status.
- **Access Mode Segmented Button (`SingleChoiceSegmentedButtonRow`)**:
  - `Allow` | `Block` | `Schedule` | `Ignore`
  - Switching to `Block` (or changing mode on an active device) prompts an inline M3 confirmation bar before sending the mutation.
- **Discipline Mode Card (Expandable)**:
  - Quick preset chips matching the Web UI: `1 Mbps`, `768 Kbps`, `56 / 33.6 Kbps`, `33.6 Kbps`, `28.8 Kbps`, `14.4 Kbps`, plus `Custom`.
  - Duration chips: `15m`, `30m`, `1h`, `2h`, `Until tomorrow`, `Custom`.
  - Exact controller preview (`56 Kbps ↓ / 34 Kbps ↑`) and **Start speed limit** / **End speed limit** button.
- **Schedule Summary Card**:
  - Plain-English preview (e.g., *"Allowed Mon–Fri 09:00–17:00, blocked otherwise"*), next transition timestamp, and an **Edit Schedule** action that opens the full-screen M3 Schedule Editor dialog.

### 5.4 Schedule Editor (Full-Screen M3 Dialog)
- **Preset Selector**: Assist chips for `Allow weekdays`, `Allow evenings`, `Block overnight`, `Block weekdays`, `Weekend access`.
- **Fallback State (`default_access`)**: Segmented button (`Allowed outside windows` vs `Blocked outside windows`).
- **Timezone Picker**: Defaults to the instance's configured IANA timezone.
- **Window Cards**:
  - 7 circular day toggles (`M T W T F S S`) using `primaryContainer` when active.
  - Start / End time pills opening the standard M3 `TimePickerDialog` (supports overnight windows where `end <= start`).
  - Window action toggle (`Allow window` vs `Block window`), noting that block windows take priority.
- **Live Plain-English Preview Footer** + **Save Schedule** primary button.

### 5.5 Discipline Screen
- Dedicated operational view for parents/admins managing temporary speed limits.
- **Active Limits List**: Large countdown cards with circular/linear progress, download/upload Kbps badges, actor attribution (`Started by mobile: Shaun's Pixel`), and one-tap **Restore Normal Speed**.
- **Start New Limit FAB (`ExtendedFloatingActionButton`)**: Opens a searchable device picker sheet followed immediately by the speed/duration preset sheet.

### 5.6 Audit Log Screen
- **Filter Chips**: Action type (`block`, `unblock`, `discipline_start`, `discipline_end`, `mobile_pair`), Result (`ok`, `error`, `dry_run`), and Actor (`All`, `This phone`).
- **Audit Entry Card**:
  - Semantic icon + Action title + Result badge.
  - Target device label + monospace MAC.
  - Actor chip (`mobile: Shaun's Pixel` vs `web: admin`) and relative/UTC timestamp.
  - Expandable JSON detail drawer for troubleshooting.

### 5.7 Settings & Connection Sheet
- **Paired Instance Card**: Instance version, relay URL, device token ID prefix, paired timestamp, and **Disconnect / Unpair this phone** (destructive button).
- **Appearance Section**:
  - Theme Mode (`System default`, `Light`, `Dark`) via M3 `SingleChoiceSegmentedButtonRow`.
  - `Dynamic color (Material You)` switch (Android 12+).
- **Security Section**:
  - `Require biometric unlock` switch + re-lock timeout (`Immediately`, `After 1 minute`, `After 5 minutes`).
  - Certificate pinning / custom CA fingerprint status.
- **Notifications Section**:
  - Toggles for push/background alerts: `Device blocked/unblocked`, `Discipline limit started/ended`, `Controller unreachable`.
  - Privacy note reminding that notification payloads never carry SSIDs, MACs, or device names until fetched over the E2EE tunnel.

---

## 6. Offline, Stale & Error State Framework

Per Issue #10, the app **never queues destructive mutations offline**.

| Condition | Visual Treatment | Action Behavior |
| :--- | :--- | :--- |
| **Tunnel & Controller Healthy** | Subtle green dot in TopAppBar; `Updated just now` | All read and write actions enabled |
| **Refreshing / In-flight** | TopAppBar linear indeterminate indicator (`2.dp`) | Mutating button shows inline spinner and is disabled against double-tap |
| **Relay Reachable, Home Instance Offline** | Persistent M3 `errorContainer` banner at top of screen with `Last updated <time>` | Cached inventory is browsable read-only; all mutating buttons disabled with tooltip *"Home instance offline"* |
| **Home Instance Online, UniFi Controller Unreachable** | Persistent M3 `tertiaryContainer` warning banner showing controller error | Read-only inventory and audit log remain live; controller mutations disabled |
| **Token Revoked from Web UI** | Immediate modal lock screen explaining the credential was revoked | Wipes local Keystore token and returns to the QR Pairing screen |

---

## 7. Planned Android Module & Architecture Framework

The project is organised as a single `:app` module without third-party analytics
or tracking SDKs:

```
Android/
  app/src/main/java/com/smccloud/unifiwifitool/
    MainActivity.kt          # FragmentActivity, edge-to-edge, BiometricGate wiring
    ui/
      AccessViewModel.kt     # AccessUiState + AccessActions, offline handling
      AccessActions.kt       # UI action contract + preview no-op
      model/                 # UI models and preview sample data
      theme/                 # M3 light/dark tokens, type, shape, status colors
      navigation/            # Top-level destinations
      components/            # Badges, tunnel pill, banner, device card, dialogs
      screens/               # Pairing, overview, devices, discipline, audit, lock, settings
    data/
      settings/              # Non-secret preferences and pairing metadata
      keystore/              # Android Keystore AES-256-GCM token storage
      biometric/             # BiometricPrompt coroutine wrapper
      transport/             # AccessApi, direct HTTPS client, wire DTOs
        relay/               # Outbound WebSocket + AES-GCM frame cipher
      repository/            # Remote repository and offline demo repository
```

## 8. Android implementation status

Implemented on `feature/mobile-app`:

- M3 Compose UI for every screen and flow in this document, including light,
  dark, and optional Android 12+ dynamic color.
- `AccessViewModel` owns all UI state, refuses mutations while the home
  instance or controller is unreachable, and records mobile-attributed audit
  entries.
- Device token and relay session key are sealed with a non-exportable Android
  Keystore AES-256-GCM key; only IV + ciphertext reach SharedPreferences.
- `BiometricGate` wraps `BiometricPrompt`; the app locks on launch when enabled.
- Transport split: direct HTTPS client (TLS verification on, optional pins) and
  an outbound relay WebSocket whose frames carry only a request id plus an
  AES-GCM sealed payload.
- `./gradlew assembleDebug` produces a debug APK (verified with AGP 9.1,
  Kotlin 2.4, Gradle 9.5, SDK 36).

Pending (tracked by issue #10): the backend `/api/mobile/pair` endpoint, relay
service, push registration, and server-side per-device revocation. Until then
the pairing button enters an explicitly labelled offline demo mode.

