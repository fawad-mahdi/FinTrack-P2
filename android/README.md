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
- **Secure**: PIN authentication with Android Keystore
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

The following Python packages are bundled via Chaquopy:

- FastAPI 0.104.1
- Uvicorn 0.24.0
- SQLAlchemy 2.0.23
- Google API Python Client 2.108.0
- Google Auth libraries

### Storage Locations

- Database: `/data/data/com.fintrack.pk/databases/fintrack.db`
- Logs: `/data/data/com.fintrack.pk/files/logs/`
- Config: `/data/data/com.fintrack.pk/files/config/`
- Python Runtime: `/data/data/com.fintrack.pk/files/python/`

## Security

- Server binds only to localhost (127.0.0.1)
- PIN stored in Android Keystore (hardware-backed when available)
- All data in app-private storage
- OAuth tokens stored securely
- No external network access to server

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
