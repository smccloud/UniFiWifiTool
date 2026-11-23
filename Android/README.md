# UniFi WiFi Access Manager — Android app

Jetpack Compose app using Material Design 3 for issue #10. The UI is complete
enough to exercise every remote-control flow against either the offline demo
repository or the outbound relay transport.

## Build

Requirements: JDK 17+ (Android Studio's JBR is fine) and the Android SDK with
`platforms;android-36`.

```bash
cd Android
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` (git-ignored) points Gradle at your SDK:

```properties
sdk.dir=C\:\\Users\\you\\AppData\\Local\\Android\\Sdk
```

The project uses AGP 9.1 with its built-in Kotlin support, Kotlin 2.4 for the
Compose and serialization compiler plugins, and Gradle 9.5 via the committed
wrapper.

Jenkins builds this module when the pipeline's `BUILD_TARGET` parameter is
`android` (default is `docker`). The stage requires JDK 17+ and Android SDK
platform 36 on the agent; point `ANDROID_SDK_DIR` at it if it is not already
exported as `ANDROID_HOME`/`ANDROID_SDK_ROOT`. It runs
`./gradlew --no-daemon :app:assembleDebug` and archives the debug APK.

## Layers

| Package | Responsibility |
| --- | --- |
| `ui/theme` | M3 color roles (light/dark), semantic status tokens, type and shape scales, dynamic color. |
| `ui/screens` | Pairing, overview, devices, schedule editor, discipline, audit, settings, biometric lock. |
| `ui/AccessViewModel.kt` | Single `AccessUiState` + `AccessActions`, offline handling, audit-attributed mutations. |
| `data/settings` | Non-secret preferences and pairing metadata. |
| `data/keystore` | AES-256-GCM device token and relay session key in the Android Keystore. |
| `data/biometric` | Coroutine wrapper around `BiometricPrompt`. |
| `data/transport` | `AccessApi` abstraction, direct HTTPS client, wire DTOs. |
| `data/transport/relay` | Outbound WebSocket relay with per-frame AES-GCM envelopes. |
| `data/repository` | Remote repository plus an offline demo repository used before pairing. |

## Security properties

- The device bearer token and relay session key never leave the Android Keystore
  as plaintext; SharedPreferences only sees IV + ciphertext.
- Relay envelopes contain only a request id and an AES-GCM sealed payload. Method,
  path, bearer token, and bodies stay inside the ciphertext, so the relay cannot
  read inventory or audit data.
- Mutations are refused while the home instance or controller is unreachable;
  nothing is queued offline.
- No third-party analytics or tracking SDKs.

## Demo mode

The backend side of pairing (`POST /api/mobile/pair`) is still pending. Until it
ships, "Exchange Pairing Secret & Connect" enters an explicitly labelled offline
demo mode backed by `DemoAccessRepository`; the full UI, validation, and audit
attribution flows work without a home instance. No demo token is written to the
Keystore.

## Status

- Builds and assembles a debug APK (`:app:assembleDebug`).
- Backend pairing, push registration, and server-side device revocation are
  tracked by issue #10.
