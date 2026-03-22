# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

FinTracker PK is a personal finance tracker that parses bank alert emails (SCB, Meezan) via Gmail. It has two deployment targets:

1. **Desktop/Web** — FastAPI server + vanilla HTML frontend, run directly with Python 3.12
2. **Android** — Same Python server embedded via Chaquopy (Python 3.9), wrapped in a native Kotlin app with a WebView frontend

## Running the Desktop App

```bash
python server.py          # Starts at http://127.0.0.1:8000
```

## Android Build Commands

All commands run from the `android/` directory:

```bash
cd android

# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease

# Install on connected device
./gradlew installDebug

# Run unit tests
./gradlew test

# Run instrumentation tests (requires connected device/emulator)
./gradlew connectedAndroidTest

# Clean build artifacts
./gradlew clean

# View build logs with full stack trace
./gradlew assembleDebug --stacktrace
```

The debug APK outputs to: `android/app/build/outputs/apk/debug/app-debug.apk`

Install manually via ADB:
```bash
adb install android/app/build/outputs/apk/debug/app-debug.apk
```

## Critical: Python Version Split

The desktop and Android targets use **different dependency versions**. This is intentional and must be maintained:

| Package | Desktop (`requirements.txt`) | Android (`build.gradle.kts`) | Reason |
|---|---|---|---|
| Python | 3.12 | **3.9** | Chaquopy constraint |
| FastAPI | 0.115.0 | 0.88.0 | Pydantic 1.x compat |
| Pydantic | 2.x | **1.10.13** | No Rust build on Android |
| cryptography | latest | **3.4.8** | Last pre-built wheel |
| google-api-python-client | 2.149.0 | 2.70.0 | Pre-built wheels |

**Rule**: Never upgrade Android packages without verifying pre-built wheel availability on Chaquopy's package index. Packages requiring Rust compilation (pydantic v2, cryptography ≥38, newer google-auth) will fail to build.

**Python syntax rule**: All Python code that runs on Android must be compatible with **Python 3.9**. Avoid:
- `int | str` union syntax (use `Union[int, str]` from `typing`)
- `dict[str, int]` generics (use `Dict[str, int]` from `typing`)
- `str | None` (use `Optional[str]`)
- Match statements (`match`/`case`)

## Android Architecture

The Android app is a thin native wrapper around the existing Python/web stack:

```
FinTrackApplication.kt      ← App entry, initializes Python platform (MUST run before any Python)
  └─ Python.start(AndroidPlatform(this))   ← Chaquopy init, must be called in Application.onCreate()

MainActivity.kt             ← WebView host; orchestrates startup sequence:
  1. Show loading screen
  2. Start Python FastAPI server (ServerProcessManager)
  3. Poll health check (OkHttp, 5s intervals)
  4. Load WebView → http://127.0.0.1:8000

PinAuthenticationActivity   ← Started via startActivityForResult() FROM MainActivity (not as launcher)
ServerProcessManager.kt     ← Launches server.py via ProcessBuilder; restarts on crash (max 5 attempts)
PythonRuntimeManager.kt     ← Handles first-launch Chaquopy runtime extraction
```

**Critical launch flow**: `MainActivity` must be the launcher activity in `AndroidManifest.xml`. `PinAuthenticationActivity` is started with `startActivityForResult()` from `MainActivity`. If `PinAuthenticationActivity` is the launcher, the app exits after PIN entry (no activity to return to).

## Known Android Issues

### Issue 1: Python Platform Not Initialized
`Python.start(AndroidPlatform(this))` must be called in `FinTrackApplication.onCreate()` before `ServerProcessManager` attempts to run any Python. The check `if (!Python.isStarted())` guards against double-init. If you see `Python runtime not initialized` errors, verify `FinTrackApplication` is registered in `AndroidManifest.xml` as `android:name=".FinTrackApplication"`.

### Issue 2: Python 3.9 Type Syntax
Any `X | Y` union syntax or built-in generic aliases (`list[x]`, `dict[x, y]`) will crash at runtime on Android. Use `typing` module imports instead.

### Issue 3: GridLayout PIN Pad
`PinAuthenticationActivity` builds the number pad programmatically. GridLayout requires explicit `rowSpec`/`columnSpec` using `GridLayout.spec(index / 3)` / `GridLayout.spec(index % 3)`. Using `GridLayout.spec(GridLayout.UNDEFINED, 1f)` causes all buttons to stack in column 0.

## Key Storage Paths (Android)

```
/data/data/com.fintrack.pk/
  files/
    python/       ← Chaquopy runtime
    config/       ← credentials.json, token.json (OAuth)
    logs/         ← app_logs.txt, server_logs.txt, crash_log.txt
  databases/
    fintrack.db   ← SQLite
```

Environment variables set by `ServerProcessManager` before launching `server.py`:
- `FINTRACK_APP_DIR`, `FINTRACK_CONFIG_DIR`, `FINTRACK_LOGS_DIR`, `FINTRACK_DB_DIR`

`server.py` and `database.py` must read these env vars to locate files when running on Android (they fall back to local paths on desktop).

## Python Code Structure

- `server.py` — All FastAPI routes. Key: `/api/sync` accepts `{date_from?, date_to?}` and returns `{emails_found, parsed_ok, pending_review, skipped_parse, skipped_duplicate}`
- `database.py` — SQLite wrapper. Schema includes `source` column (`gmail`|`manual`) and `merchant_categories` table for category learning
- `parsers.py` — Regex-based email parsers for SCB and Meezan formats; outputs normalized transaction dicts
- `merchants.py` — Merchant name normalization (strip bank prefixes, lowercase, fuzzy cleanup)
- `auth_gmail.py` — Gmail OAuth using `InstalledAppFlow`; token cached to `token.json`

## No Test Suite for Desktop

The desktop Python app has no automated tests (intentional — personal local tool). The Android layer has unit tests (`test/`) and instrumentation tests (`androidTest/`).
