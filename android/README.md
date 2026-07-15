# FinTrack PK - Android Application

This is the Android mobile application for FinTrack PK, a personal financial tracker that packages the Python FastAPI backend into a native Android app.

## Project Structure

```
android/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/fintrack/pk/
│   │   │   │   ├── data/           # Data models
│   │   │   │   ├── ui/             # Activities and UI components
│   │   │   │   ├── utils/          # Utility classes
│   │   │   │   └── FinTrackApplication.kt
│   │   │   ├── res/                # Resources (layouts, strings, etc.)
│   │   │   └── AndroidManifest.xml
│   │   └── test/                   # Unit tests
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── gradle/
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

## Requirements

- Android Studio Hedgehog (2023.1.1) or later
- JDK 17 or later
- Android SDK with API level 24-34
- Gradle 8.2 or later

## Key Features

- **Self-Contained**: Bundles Python runtime and all dependencies
- **Offline-First**: All data stored locally in SQLite
- **Secure**: Native Keystore-backed PIN lock; encrypted OAuth token storage; per-launch API capability token
- **Gmail Sync**: Automatic transaction import from bank emails
- **No Play Store Required**: Sideloadable APK

## Architecture

The app uses a hybrid architecture:

1. **Native Android Layer**: Manages app lifecycle, authentication, and process management
2. **Python Server Layer**: FastAPI backend running on localhost
3. **WebView Layer**: Renders the existing web frontend

## Building the Project

### Debug Build

```bash
./gradlew assembleDebug
```

The APK will be generated at: `app/build/outputs/apk/debug/app-debug.apk`

### Release Build

```bash
./gradlew assembleRelease
```

The APK will be generated at: `app/build/outputs/apk/release/app-release.apk`

## Installation

1. Enable "Install from Unknown Sources" on your Android device
2. Transfer the APK to your device
3. Open the APK file and follow the installation prompts
4. Grant necessary permissions (Storage, Network)

## Development Status

This project is currently under development. The following tasks are planned:

- [x] Task 1: Project structure and dependencies
- [ ] Task 2: Bundle Python runtime and dependencies
- [ ] Task 3: Implement ServerProcessManager
- [ ] Task 4: Implement PIN authentication
- [ ] Task 6: Implement MainActivity and lifecycle
- [ ] Task 7: Implement WebViewManager
- [ ] Task 8: Implement StorageManager
- [ ] Task 9: Implement NetworkMonitor
- [ ] Task 11: Implement Gmail OAuth integration
- [ ] Task 12: Implement settings UI
- [ ] Task 13: Integrate all components
- [ ] Task 14: Implement error handling and logging
- [ ] Task 15: Performance optimizations
- [ ] Task 17: APK packaging and signing
- [ ] Task 18: Final testing and validation

## Configuration

### Python Dependencies

The following Python packages are bundled via Chaquopy (versions pinned for
pre-built wheel availability on Python 3.9 — see the root `CLAUDE.md`):

- FastAPI 0.88.0
- Uvicorn 0.20.0
- SQLAlchemy 1.4.46
- Pydantic 1.10.13
- cryptography 3.4.8
- Google API Python Client 2.70.0
- Google Auth libraries (google-auth-oauthlib 0.8.0, google-auth-httplib2 0.1.0)

### Storage Locations

- Database: `/data/data/com.fintrack.pk/databases/fintrack.db`
- Logs: `/data/data/com.fintrack.pk/files/logs/`
- Config: `/data/data/com.fintrack.pk/files/config/`
- Python Runtime: `/data/data/com.fintrack.pk/files/python/`

## Security

This section describes the controls the shipped source actually enforces.

### App lock
- A native PIN is required at startup (setup on first run, login thereafter)
  and re-required after 5+ minutes in the background.
- The PIN is stored encrypted via the Android Keystore (hardware-backed when
  available), with a 3-attempt lockout. This is the single user lock; there
  is no separate web-UI PIN.

### Embedded API authorization
- The embedded FastAPI server binds only to loopback (127.0.0.1).
- Every `/api/*` request must present a per-launch capability token
  (256-bit, `SecureRandom`) as the `X-API-Token` header. The native layer
  generates it once per process, injects it into the Python server, and
  hands it to the WebView frontend via the JS bridge. There is no default
  PIN and no process-global session, and access dies with the process.
- `/health` is the only unauthenticated route (liveness probe).

### OAuth tokens & credentials
- Gmail uses a single native AppAuth authorization-code flow with explicit
  PKCE (S256) and state binding; the callback rejects forged, replayed, or
  mismatched redirects before any token exchange.
- Access and refresh tokens are stored in `EncryptedSharedPreferences`
  (Android Keystore master key). The refresh token and OAuth client secret
  are never written to plaintext files and never cross into the Python
  layer — Python receives only a short-lived access token via the token
  broker. Any legacy plaintext `token.json` is migrated and deleted.
- "Disconnect Gmail" revokes the grant at Google and clears all local OAuth
  state; reconnect uses `prompt=select_account` for deliberate account
  choice.

### Network & data
- Cleartext traffic is denied globally; the network security config permits
  cleartext only for the loopback server.
- All data lives in app-private storage; the token store, config directory,
  database, and logs are excluded from backup and device transfer.
- Logs redact OAuth codes, tokens, and secrets before they are written or
  exported.

### Release configuration
- The OAuth redirect scheme is variant-aware (dev builds use their own
  scheme) and consumed from the Gradle `appAuthRedirectScheme` placeholder.
- Registering the Android OAuth client(s) and release signing SHA-1/SHA-256
  fingerprints in Google Cloud Console is an external step required before a
  signed release can complete authentication.

## Testing

### Run Unit Tests

```bash
./gradlew test
```

### Run Instrumentation Tests

```bash
./gradlew connectedAndroidTest
```

## Troubleshooting

### Server fails to start

Check the logs in Settings > Debug Logs for detailed error messages.

### OAuth not working

Ensure the `credentials.json` file is properly configured with your Gmail API credentials.

### App crashes on startup

Check logcat output: `adb logcat | grep FinTrack`

## License

This project is part of FinTrack PK. All rights reserved.

## Support

For issues and questions, please refer to the main FinTrack PK documentation.
