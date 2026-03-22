# Android Stability + MD3 Redesign Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix all Android app crashes (Python server never starts, PIN flow broken) then upgrade the UI to Material Design 3 on Android 15.

**Architecture:** Phase 1 patches the Chaquopy runtime layer (Python env vars, module import, race conditions, Python 3.9 syntax) and adds Android 15 edge-to-edge inset handling. Phase 2 migrates to MD3 theme and redesigns all four screens (PIN, Settings, Debug, Main) without adding any new features.

**Tech Stack:** Kotlin 1.9.20, Chaquopy 15.0.1 (Python 3.9 in-process), FastAPI 0.88.0, Material 1.12.0, Android API 35, ViewBinding, Coroutines 1.7.3.

**Spec:** `docs/superpowers/specs/2026-03-20-android-ui-stability-redesign.md`

---

## File Map

### Phase 1 — Stability

| File | Action | Purpose |
|---|---|---|
| `android/app/src/main/java/com/fintrack/pk/server/ServerProcessManager.kt` | Modify | Fix env vars, isRunning race, crashCallback |
| `android/app/src/main/python/main.py` | **Create** | Chaquopy entry point that bootstraps server.py |
| `server.py` | Modify | Python 3.9 type syntax |
| `database.py` | Modify | Python 3.9 type syntax |
| `parsers.py` | Modify | Python 3.9 type syntax |
| `merchants.py` | Modify | Python 3.9 type syntax |
| `android/app/src/main/java/com/fintrack/pk/ui/MainActivity.kt` | Modify | Edge-to-edge insets |
| `android/app/src/main/java/com/fintrack/pk/ui/PinAuthenticationActivity.kt` | Modify | Invisible button fix + insets |
| `android/app/src/main/java/com/fintrack/pk/ui/SettingsActivity.kt` | Modify | Edge-to-edge insets |
| `android/app/build.gradle.kts` | Modify | compileSdk/targetSdk 35, Material 1.12.0 |

### Phase 2 — MD3 Redesign

| File | Action | Purpose |
|---|---|---|
| `android/app/src/main/res/values/themes.xml` | Modify | MD3 parent + color token wiring |
| `android/app/src/main/res/values/colors.xml` | Modify | MD3 color tokens |
| `android/app/src/main/res/values/strings.xml` | Modify | All hardcoded strings moved here |
| `android/app/src/main/res/drawable/ic_fintrack_logo.xml` | **Create** | Logo vector for PIN screen |
| `android/app/src/main/res/drawable/ic_check_circle.xml` | **Create** | Gmail connected chip icon |
| `android/app/src/main/res/drawable/ic_cancel.xml` | **Create** | Gmail disconnected chip icon |
| `android/app/src/main/res/drawable/ic_share.xml` | **Create** | Debug FAB icon |
| `android/app/src/main/res/drawable/ic_refresh.xml` | **Create** | Debug toolbar menu icon |
| `android/app/src/main/res/drawable/pin_dot_filled.xml` | Modify | Enlarge to 20dp |
| `android/app/src/main/res/drawable/pin_dot_empty.xml` | Modify | Enlarge to 20dp |
| `android/app/src/main/res/layout/activity_main.xml` | Modify | MD3 toolbar, progress indicator, error card, remove banners |
| `android/app/src/main/res/layout/activity_pin_authentication.xml` | Modify | ConstraintLayout, tonal buttons |
| `android/app/src/main/res/layout/activity_settings.xml` | Modify | Card-based sections |
| `android/app/src/main/res/layout/activity_debug.xml` | Modify | Full implementation |
| `android/app/src/main/res/menu/debug_menu.xml` | **Create** | Refresh action for Debug toolbar |
| `android/app/src/main/java/com/fintrack/pk/ui/PinAuthenticationActivity.kt` | Modify | Dot animations, shake on error |
| `android/app/src/main/java/com/fintrack/pk/ui/SettingsActivity.kt` | Modify | Chip status, MD3 color refs, string refs |
| `android/app/src/main/java/com/fintrack/pk/ui/DebugActivity.kt` | Modify | Full implementation |
| `android/app/src/main/java/com/fintrack/pk/ui/MainActivity.kt` | Modify | Snackbar banners, progress indicator |

---

## Chunk 1: Phase 1 — Stability Fixes

### Task 1: SDK Update + Build Foundation

**Files:**
- Modify: `android/app/build.gradle.kts`

- [ ] **Step 1: Update SDK versions and Material library**

In `android/app/build.gradle.kts`, make two separate changes:

**a) `compileSdk` is at the top of the `android { }` block (NOT inside `defaultConfig`):**
```kotlin
android {
    namespace = "com.fintrack.pk"
    compileSdk = 35        // was 34  ← change here

    defaultConfig {
        ...
        targetSdk = 35     // was 33  ← change here too
```

**b) In the `dependencies { }` block:**
```kotlin
implementation("com.google.android.material:material:1.12.0")  // was 1.10.0
```

- [ ] **Step 2: Verify the build compiles**

```bash
cd android && ./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`. If you see Material API deprecation warnings, ignore them — they'll be addressed in Phase 2. If you see compile errors about missing API 35 symbols, check that you're using JDK 17 (`java -version`).

- [ ] **Step 3: Commit**

```bash
cd android && git add app/build.gradle.kts
git commit -m "build: bump compileSdk/targetSdk to 35, Material to 1.12.0"
```

---

### Task 2: Create Chaquopy Entry Point (`main.py`)

**Files:**
- Create: `android/app/src/main/python/main.py`

Background: `ServerProcessManager.kt` calls `python.getModule("main").callAttr("start_server", host, port)`. Chaquopy looks for `main.py` in `src/main/python/`. Without it, `getModule` fails silently and the server never starts.

- [ ] **Step 1: Create the Python source directory if it doesn't exist**

```bash
mkdir -p /Users/fawad/Documents/Projects/FinTracker/android/app/src/main/python
```

- [ ] **Step 2: Create `main.py`**

Create `android/app/src/main/python/main.py` with exactly this content:

```python
import sys
import os


def start_server(host, port):
    """Entry point called by Chaquopy from ServerProcessManager.kt."""
    # Inject the app files directory into sys.path so server.py can be imported
    app_dir = os.environ.get("FINTRACK_APP_DIR", "")
    if app_dir and app_dir not in sys.path:
        sys.path.insert(0, app_dir)

    import uvicorn
    from server import app as fastapi_app

    uvicorn.run(fastapi_app, host=str(host), port=int(port), log_level="info")
```

- [ ] **Step 3: Verify the module is picked up by Chaquopy**

Build the APK. Chaquopy bundles all `.py` files from `src/main/python/` into the APK automatically — no additional build config needed.

```bash
cd android && ./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`. Look for a Chaquopy log line like `Extracting Python files` during build.

- [ ] **Step 4: Commit**

```bash
cd android && git add app/src/main/python/main.py
git commit -m "feat: add Chaquopy main.py entry point for FastAPI server"
```

---

### Task 3: Fix Python Env Vars Reaching `os.environ`

**Files:**
- Modify: `android/app/src/main/java/com/fintrack/pk/server/ServerProcessManager.kt`

Background: `setupEnvironment()` calls `System.setProperty(...)` which sets JVM properties. Python's `os.environ` reads OS-level environment variables, not JVM properties. The server calls `os.getenv("FINTRACK_APP_DIR")` and gets `None`, so all file paths break.

- [ ] **Step 1: Read `setupEnvironment()` to find where JVM properties are set**

Open `android/app/src/main/java/com/fintrack/pk/server/ServerProcessManager.kt` and locate the `setupEnvironment()` method (around line 207). Note the four `System.setProperty(...)` calls setting `FINTRACK_APP_DIR`, `FINTRACK_CONFIG_DIR`, `FINTRACK_LOGS_DIR`, `FINTRACK_DB_DIR`.

- [ ] **Step 2: Add a helper method to inject values into Python's `os.environ`**

Add this private method directly below `setupEnvironment()` in `ServerProcessManager.kt`:

```kotlin
/**
 * Inject directory paths into Python's os.environ so os.getenv() works.
 * System.setProperty() only sets JVM properties — Python os.environ does not see those.
 */
private fun injectPythonEnvironment(python: Python, dirs: Map<String, String>) {
    try {
        val pyOs = python.getModule("os")
        val pyEnviron = pyOs.get("environ")
        dirs.forEach { (key, value) ->
            pyEnviron?.callAttr("__setitem__", key, value)
        }
        Logger.logInfo("ServerProcessManager", "Python os.environ injected: ${dirs.keys}")
    } catch (e: Exception) {
        Logger.logError("ServerProcessManager", "Failed to inject Python environment", e)
    }
}
```

- [ ] **Step 3: Call `injectPythonEnvironment` immediately before `callAttr("start_server", ...)`**

In `startServer()`, find the line `pythonModule?.callAttr("start_server", SERVER_HOST, SERVER_PORT)` (around line 96). Just before it, add:

```kotlin
// Inject directory paths into Python os.environ (System.setProperty is JVM-only)
val envDirs = mapOf(
    "FINTRACK_APP_DIR" to (context.filesDir.absolutePath),
    "FINTRACK_CONFIG_DIR" to (File(context.filesDir, "config").absolutePath),
    "FINTRACK_LOGS_DIR" to (File(context.filesDir, "logs").absolutePath),
    "FINTRACK_DB_DIR" to File(context.filesDir, "databases").absolutePath
)
injectPythonEnvironment(python, envDirs)
```

Make sure `java.io.File` is imported at the top of the file (it likely already is).

- [ ] **Step 4: Verify the build compiles**

```bash
cd android && ./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
cd android && git add app/src/main/java/com/fintrack/pk/server/ServerProcessManager.kt
git commit -m "fix: inject server directory paths into Python os.environ

System.setProperty() is JVM-only. Python os.getenv() reads OS env vars.
Now injecting directly via Chaquopy into Python's os.environ dict before
calling start_server(), so FINTRACK_APP_DIR etc. resolve correctly."
```

---

### Task 4: Fix `isRunning` Race Condition + Missing `crashCallback`

**Files:**
- Modify: `android/app/src/main/java/com/fintrack/pk/server/ServerProcessManager.kt`

Background: After `serverThread?.start()`, the main thread immediately sets `isRunning = true` (line ~105). If the thread crashes before this point, the thread's catch block sets `isRunning = false`, then the main thread overrides it back to `true`. Additionally, the catch block never calls `crashCallback`, so `MainActivity` never receives notification of a server crash.

- [ ] **Step 1: Fix the `isRunning = true` assignment after `start()`**

Find this code block in `startServer()` (around line 104–106):
```kotlin
serverThread?.start()
isRunning = true
serverStartTime = System.currentTimeMillis()
```

Replace `isRunning = true` with a check that only sets true if the thread is still alive:
```kotlin
serverThread?.start()
isRunning = serverThread?.isAlive == true  // Only true if thread didn't crash instantly
serverStartTime = System.currentTimeMillis()
```

- [ ] **Step 2: Add `crashCallback` invocation in the server thread's catch block**

Find the catch block inside the server thread lambda (around line 97–101):
```kotlin
} catch (e: Exception) {
    lastError = "Server thread error: ${e.message}"
    Logger.logError("ServerProcessManager", lastError!!, e)
    isRunning = false
}
```

Add the callback invocation:
```kotlin
} catch (e: Exception) {
    lastError = "Server thread error: ${e.message}"
    Logger.logError("ServerProcessManager", lastError!!, e)
    isRunning = false
    crashCallback?.onServerCrashedAndExhausted()  // interface method, not a lambda invoke
}
```

- [ ] **Step 3: Verify the build compiles**

```bash
cd android && ./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Device verification — confirm crash path reaches error screen**

Install and test intentional crash handling:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

After installing, trigger a server crash by temporarily renaming `main.py` (in `src/main/python/`) to an invalid name so the import fails. Build + install, launch the app, enter PIN, and verify:
- `adb logcat | grep "Server thread"` shows a crash/exception log line
- `adb logcat | grep "isRunning"` shows it set to false
- The app shows the error screen (not the infinite loading indicator)

Restore `main.py` to its correct name before the next task.

- [ ] **Step 5: Commit**

```bash
cd android && git add app/src/main/java/com/fintrack/pk/server/ServerProcessManager.kt
git commit -m "fix: isRunning race condition + missing crashCallback on server crash

isRunning=true was set unconditionally after start(), overriding the
thread's catch block isRunning=false on instant crash. Now checks
thread.isAlive after start(). Also wires crashCallback so MainActivity
receives crash notification instead of silently staying on loading screen."
```

---

### Task 5: Python 3.9 Type Syntax Compatibility

**Files:**
- Modify: `server.py`, `database.py`, `parsers.py`, `merchants.py`

Background: Python 3.10+ union syntax (`X | Y`, built-in generics `list[x]`, `dict[x, y]`) raises `TypeError` on Python 3.9 (Chaquopy's runtime). The desktop runs Python 3.12 so this is invisible until the Android build.

- [ ] **Step 1: Check each file for incompatible syntax**

Run from the project root (requires Python 3.9 — use `python3.9` if available, otherwise `python3`):

```bash
cd /Users/fawad/Documents/Projects/FinTracker
python3.9 -m py_compile server.py && echo "server.py OK"
python3.9 -m py_compile database.py && echo "database.py OK"
python3.9 -m py_compile parsers.py && echo "parsers.py OK"
python3.9 -m py_compile merchants.py && echo "merchants.py OK"
```

If Python 3.9 isn't installed locally, scan manually with grep:
```bash
grep -n " | " server.py database.py parsers.py merchants.py | grep -v "#"
grep -n "list\[" server.py database.py parsers.py merchants.py
grep -n "dict\[" server.py database.py parsers.py merchants.py
grep -n "tuple\[" server.py database.py parsers.py merchants.py
grep -n "^match " server.py database.py parsers.py merchants.py
```

- [ ] **Step 2: Add `typing` imports to any file that needs them**

At the top of each file that has type annotations, ensure these imports are present:
```python
from typing import Optional, Union, List, Dict, Tuple, Any
```

Only add what's actually used in that file.

- [ ] **Step 3: Replace all incompatible syntax**

Rules:
- `int | str` → `Union[int, str]`
- `str | None` or `Optional parameter` → `Optional[str]`
- `list[SomeType]` → `List[SomeType]`
- `dict[str, Any]` → `Dict[str, Any]`
- `tuple[X, Y]` → `Tuple[X, Y]`
- `match x: case ...:` → `if x == ...: elif ...:`

Work through each file. Make changes surgically — do not refactor logic, only fix the type syntax.

- [ ] **Step 4: Verify all files pass 3.9 compile check**

```bash
cd /Users/fawad/Documents/Projects/FinTracker
python3.9 -m py_compile server.py database.py parsers.py merchants.py && echo "ALL OK"
```

Expected: `ALL OK` with no tracebacks.

- [ ] **Step 5: Verify desktop server still starts**

```bash
python server.py &
sleep 3
curl -s http://127.0.0.1:8000/ | head -c 100
kill %1
```

Expected: some HTML or JSON response (not a connection refused).

- [ ] **Step 6: Commit**

```bash
cd /Users/fawad/Documents/Projects/FinTracker
git add server.py database.py parsers.py merchants.py
git commit -m "fix: replace Python 3.10+ type syntax with typing module for 3.9 compat

Chaquopy embeds Python 3.9. X|Y unions and built-in generics (list[x],
dict[x,y]) are Python 3.10+ only. Replace with typing.Union, Optional,
List, Dict throughout all four backend modules."
```

---

### Task 6: Android 15 Edge-to-Edge Insets

**Files:**
- Modify: `android/app/src/main/java/com/fintrack/pk/ui/MainActivity.kt`
- Modify: `android/app/src/main/java/com/fintrack/pk/ui/PinAuthenticationActivity.kt`
- Modify: `android/app/src/main/java/com/fintrack/pk/ui/SettingsActivity.kt`

Background: Android 15 (API 35) enforces edge-to-edge when `targetSdk >= 35`. Without inset handling, the Toolbar overlaps the status bar and the system nav bar covers bottom content.

- [ ] **Step 1: Opt in to edge-to-edge in all three activities**

In each of the three activities, add `enableEdgeToEdge()` as the **first line** of `onCreate()`, before `setContentView(...)`. This is the AndroidX opt-in that tells the system your app handles insets itself:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)   // ← super FIRST always
    enableEdgeToEdge()                   // ← then opt-in, before setContentView
    setContentView(...)
```

Add import if missing: `import androidx.activity.enableEdgeToEdge`

This call is available from `androidx.activity:activity-ktx:1.8.0+` (already a dependency).

- [ ] **Step 3: Add insets to `MainActivity.kt`**

`MainActivity` uses `lateinit var + findViewById` (no ViewBinding). After `setContentView(...)`:

```kotlin
// Android 15 edge-to-edge: app bar extends behind status bar
// appBarLayout is a lateinit var field initialized via findViewById
ViewCompat.setOnApplyWindowInsetsListener(appBarLayout) { view, insets ->
    val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
    view.setPadding(0, top, 0, 0)
    insets
}
// Navigation bar padding on the window content frame
val rootContent = findViewById<android.view.View>(android.R.id.content)
ViewCompat.setOnApplyWindowInsetsListener(rootContent) { view, insets ->
    val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
    view.setPadding(0, 0, 0, nav)
    insets
}
```

If `appBarLayout` is not already a `lateinit var` field, add it:
```kotlin
private lateinit var appBarLayout: com.google.android.material.appbar.AppBarLayout
// and in initializeViews(): appBarLayout = findViewById(R.id.appBarLayout)
```

Ensure imports at the top:
```kotlin
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
```

- [ ] **Step 4: Add insets to `PinAuthenticationActivity.kt`**

`PinAuthenticationActivity` also uses `lateinit var + findViewById`. After `setContentView(...)`:

```kotlin
// pinRoot is the ConstraintLayout root id defined in the new layout
val pinRoot = findViewById<android.view.View>(R.id.pinRoot)
ViewCompat.setOnApplyWindowInsetsListener(pinRoot) { view, insets ->
    val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
    view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
    insets
}
```

- [ ] **Step 5: Add insets to `SettingsActivity.kt`**

```kotlin
// settingsRoot is the NestedScrollView root id in the new layout
val settingsRoot = findViewById<android.view.View>(R.id.settingsRoot)
ViewCompat.setOnApplyWindowInsetsListener(settingsRoot) { view, insets ->
    val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
    view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
    insets
}
```

- [ ] **Step 6: Verify the build compiles**

```bash
cd android && ./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
cd android && git add app/src/main/java/com/fintrack/pk/ui/MainActivity.kt \
    app/src/main/java/com/fintrack/pk/ui/PinAuthenticationActivity.kt \
    app/src/main/java/com/fintrack/pk/ui/SettingsActivity.kt
git commit -m "fix: handle Android 15 edge-to-edge window insets in all activities

API 35 enforces edge-to-edge. enableEdgeToEdge() opts in, WindowInsetsCompat
listeners apply correct padding per activity to prevent toolbar/nav bar overlap."
```

---

### Task 7: GridLayout Invisible Button Fix

**Files:**
- Modify: `android/app/src/main/java/com/fintrack/pk/ui/PinAuthenticationActivity.kt`

Background: The empty button at grid index 9 is set to `View.INVISIBLE` but remains clickable and focusable, which can cause accidental touch events.

- [ ] **Step 1: Locate the empty button branch in `setupNumberPad()`**

In `PinAuthenticationActivity.kt`, find `setupNumberPad()`. Find the `if (label.isEmpty())` block. Currently it only sets `visibility = View.INVISIBLE`.

- [ ] **Step 2: Disable interaction on the invisible button**

Change the block to:
```kotlin
if (label.isEmpty()) {
    visibility = View.INVISIBLE
    isClickable = false
    isFocusable = false
}
```

- [ ] **Step 3: Verify the build compiles**

```bash
cd android && ./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Install on device and verify PIN pad**

Run from the `android/` directory (consistent with all prior tasks):
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Launch the app. Verify:
- All 12 grid positions are visible (1-9, empty, 0, ⌫)
- Tapping the empty slot does nothing
- All number buttons and backspace respond to taps

- [ ] **Step 5: Verify full Phase 1 flow on device**

Run this test sequence manually:
1. App launches → reaches PIN screen (not home screen)
2. Enter PIN → app shows loading screen (not home screen)
3. Check logcat: `adb logcat | grep -E "ServerProcessManager|Python|FINTRACK"`
4. Within 30 seconds, logcat should show `Application startup complete` from uvicorn
5. WebView loads the FinTrack web UI

If step 3-5 fail, check:
- `adb logcat | grep "FINTRACK_APP_DIR"` — should show the actual `filesDir` path
- `adb logcat | grep "Server thread"` — look for crash messages

- [ ] **Step 6: Commit**

```bash
cd android && git add app/src/main/java/com/fintrack/pk/ui/PinAuthenticationActivity.kt
git commit -m "fix: disable clickable/focusable on invisible PIN pad empty button"
```

---

## Chunk 2: Phase 2 — MD3 Theme Foundation

### Task 8: MD3 Theme + Color Tokens

**Files:**
- Modify: `android/app/src/main/res/values/themes.xml`
- Modify: `android/app/src/main/res/values/colors.xml`

- [ ] **Step 1: Add MD3 color tokens to `colors.xml`**

Open `android/app/src/main/res/values/colors.xml`. Add these entries at the end, before the closing `</resources>` tag. Do NOT remove any existing color entries yet — they're still referenced by existing layouts:

```xml
<!-- MD3 Color Tokens -->
<color name="md_theme_primary">#1565C0</color>
<color name="md_theme_onPrimary">#FFFFFF</color>
<color name="md_theme_primaryContainer">#D3E4FF</color>
<color name="md_theme_onPrimaryContainer">#001B3F</color>
<color name="md_theme_secondary">#E65100</color>
<color name="md_theme_surface">#F8FAFE</color>
<color name="md_theme_surfaceVariant">#DFE2EB</color>
<color name="md_theme_onSurfaceVariant">#44474F</color>
<color name="md_theme_error">#BA1A1A</color>
<color name="md_theme_onError">#FFFFFF</color>
<color name="md_theme_outline">#72788D</color>
```

- [ ] **Step 2: Migrate `themes.xml` to MD3**

Open `android/app/src/main/res/values/themes.xml`. Replace the entire `Theme.FinTrackPK` style block (keeping `Theme.FinTrackPK.NoActionBar` and `Theme.FinTrackPK.Splash` intact):

```xml
<style name="Theme.FinTrackPK" parent="Theme.Material3.DayNight.NoActionBar">
    <item name="colorPrimary">@color/md_theme_primary</item>
    <item name="colorOnPrimary">@color/md_theme_onPrimary</item>
    <item name="colorPrimaryContainer">@color/md_theme_primaryContainer</item>
    <item name="colorOnPrimaryContainer">@color/md_theme_onPrimaryContainer</item>
    <item name="colorSecondary">@color/md_theme_secondary</item>
    <item name="colorSurface">@color/md_theme_surface</item>
    <item name="colorSurfaceVariant">@color/md_theme_surfaceVariant</item>
    <item name="colorOnSurfaceVariant">@color/md_theme_onSurfaceVariant</item>
    <item name="colorOutline">@color/md_theme_outline</item>
    <item name="colorError">@color/md_theme_error</item>
    <item name="colorOnError">@color/md_theme_onError</item>
    <item name="android:statusBarColor">@android:color/transparent</item>
    <item name="android:navigationBarColor">@android:color/transparent</item>
</style>
```

Update `Theme.FinTrackPK.NoActionBar` to inherit from `Theme.FinTrackPK` (so it picks up all the MD3 color tokens you just set):
```xml
<style name="Theme.FinTrackPK.NoActionBar" parent="Theme.FinTrackPK">
    <!-- inherits all MD3 color tokens from Theme.FinTrackPK -->
</style>
```

- [ ] **Step 3: Verify the build compiles**

```bash
cd android && ./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`. If you see `attr/colorPrimary not found` or similar, check that `colors.xml` tokens are correctly spelled.

- [ ] **Step 4: Commit**

```bash
cd android && git add app/src/main/res/values/themes.xml app/src/main/res/values/colors.xml
git commit -m "feat: migrate theme to Material Design 3 with MD3 color tokens"
```

---

### Task 9: New Drawable Resources

**Files:**
- Create: `res/drawable/ic_fintrack_logo.xml`
- Create: `res/drawable/ic_check_circle.xml`
- Create: `res/drawable/ic_cancel.xml`
- Create: `res/drawable/ic_share.xml`
- Create: `res/drawable/ic_refresh.xml`
- Modify: `res/drawable/pin_dot_filled.xml`
- Modify: `res/drawable/pin_dot_empty.xml`

- [ ] **Step 1: Create logo drawable**

Create `android/app/src/main/res/drawable/ic_fintrack_logo.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="48dp"
    android:height="48dp"
    android:viewportWidth="48"
    android:viewportHeight="48">
    <!-- Circle background -->
    <path
        android:fillColor="?attr/colorPrimary"
        android:pathData="M24,4 C12.95,4 4,12.95 4,24 C4,35.05 12.95,44 24,44 C35.05,44 44,35.05 44,24 C44,12.95 35.05,4 24,4 Z" />
    <!-- F letterform -->
    <path
        android:fillColor="?attr/colorOnPrimary"
        android:pathData="M18,14 L30,14 L30,19 L23,19 L23,23 L29,23 L29,28 L23,28 L23,34 L18,34 Z" />
</vector>
```

- [ ] **Step 2: Create icon drawables**

Create `android/app/src/main/res/drawable/ic_check_circle.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/transparent"
        android:strokeColor="#000000"
        android:strokeWidth="2"
        android:pathData="M12,2 C6.48,2 2,6.48 2,12 C2,17.52 6.48,22 12,22 C17.52,22 22,17.52 22,12 C22,6.48 17.52,2 12,2 Z" />
    <path
        android:fillColor="@android:color/white"
        android:pathData="M10,17 L5,12 L6.41,10.59 L10,14.17 L17.59,6.58 L19,8 Z" />
</vector>
```

Create `android/app/src/main/res/drawable/ic_cancel.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M12,2 C6.47,2 2,6.47 2,12 C2,17.53 6.47,22 12,22 C17.53,22 22,17.53 22,12 C22,6.47 17.53,2 12,2 Z M17,15.59 L15.59,17 L12,13.41 L8.41,17 L7,15.59 L10.59,12 L7,8.41 L8.41,7 L12,10.59 L15.59,7 L17,8.41 L13.41,12 Z" />
</vector>
```

Create `android/app/src/main/res/drawable/ic_share.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M18,16.08 C17.24,16.08 16.56,16.38 16.04,16.85 L8.91,12.7 C8.96,12.47 9,12.24 9,12 C9,11.76 8.96,11.53 8.91,11.3 L15.96,7.19 C16.5,7.69 17.21,8 18,8 C19.66,8 21,6.66 21,5 C21,3.34 19.66,2 18,2 C16.34,2 15,3.34 15,5 C15,5.24 15.04,5.47 15.09,5.7 L8.04,9.81 C7.5,9.31 6.79,9 6,9 C4.34,9 3,10.34 3,12 C3,13.66 4.34,15 6,15 C6.79,15 7.5,14.69 8.04,14.19 L15.16,18.35 C15.11,18.56 15.08,18.78 15.08,19 C15.08,20.61 16.39,21.92 18,21.92 C19.61,21.92 20.92,20.61 20.92,19 C20.92,17.39 19.61,16.08 18,16.08 Z" />
</vector>
```

Create `android/app/src/main/res/drawable/ic_refresh.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M17.65,6.35 C16.2,4.9 14.21,4 12,4 C7.58,4 4.01,7.58 4.01,12 C4.01,16.42 7.58,20 12,20 C15.73,20 18.84,17.45 19.73,14 L17.65,14 C16.83,16.33 14.61,18 12,18 C8.69,18 6,15.31 6,12 C6,8.69 8.69,6 12,6 C13.66,6 15.14,6.69 16.22,7.78 L13,11 L20,11 L20,4 Z" />
</vector>
```

- [ ] **Step 3: Enlarge PIN dot drawables to 20dp**

Update `android/app/src/main/res/drawable/pin_dot_filled.xml` — change `android:width` and `android:height` from `16dp` to `20dp`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="oval">
    <solid android:color="@color/md_theme_primary" />
    <size
        android:width="20dp"
        android:height="20dp" />
</shape>
```

Update `android/app/src/main/res/drawable/pin_dot_empty.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="oval">
    <stroke
        android:width="2dp"
        android:color="@color/md_theme_outline" />
    <size
        android:width="20dp"
        android:height="20dp" />
</shape>
```

- [ ] **Step 4: Verify the build compiles**

```bash
cd android && ./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
cd android && git add app/src/main/res/drawable/
git commit -m "feat: add MD3 icon drawables and enlarge PIN dot indicators to 20dp"
```

---

### Task 10: New String Resources

**Files:**
- Modify: `android/app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add all new string entries**

Open `android/app/src/main/res/values/strings.xml`. Add before the closing `</resources>` tag:

```xml
<!-- Settings screen sections -->
<string name="settings_section_gmail">Gmail Connection</string>
<string name="settings_section_security">Security</string>
<string name="settings_section_about">About</string>

<!-- Settings buttons -->
<string name="settings_gmail_connect">Connect Gmail</string>
<string name="settings_gmail_reconnect">Reconnect Gmail</string>
<string name="settings_gmail_disconnect">Disconnect Gmail</string>
<string name="settings_change_pin">Change PIN</string>
<string name="settings_view_logs">View Logs</string>
<string name="settings_version">Version %1$s (%2$d)</string>

<!-- Gmail chip status -->
<string name="settings_gmail_status_connected">Connected</string>
<string name="settings_gmail_status_disconnected">Not Connected</string>

<!-- Debug screen -->
<string name="debug_title">Logs</string>
<string name="debug_tab_app">App</string>
<string name="debug_tab_server">Server</string>
<string name="debug_no_logs">No logs yet.</string>
<string name="debug_export">Export</string>
<string name="debug_export_chooser">Export Logs</string>
<string name="debug_refresh">Refresh</string>

<!-- PIN screen -->
<string name="pin_reset_button">Reset PIN &amp; Data</string>
```

- [ ] **Step 2: Verify the build compiles**

```bash
cd android && ./gradlew assembleDebug
```

- [ ] **Step 3: Commit**

```bash
cd android && git add app/src/main/res/values/strings.xml
git commit -m "feat: add string resources for settings and debug screens"
```

---

## Chunk 3: Phase 2 — Screen Redesigns

### Task 11: PIN Authentication Screen Redesign

**Files:**
- Modify: `android/app/src/main/res/layout/activity_pin_authentication.xml`
- Modify: `android/app/src/main/java/com/fintrack/pk/ui/PinAuthenticationActivity.kt`

- [ ] **Step 1: Replace `activity_pin_authentication.xml` with MD3 layout**

Replace the entire file content with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/pinRoot"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="?attr/colorSurface">

    <ImageView
        android:id="@+id/appLogo"
        android:layout_width="64dp"
        android:layout_height="64dp"
        android:src="@drawable/ic_fintrack_logo"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        android:layout_marginTop="48dp" />

    <TextView
        android:id="@+id/appName"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/app_name"
        android:textAppearance="?attr/textAppearanceHeadlineMedium"
        android:textColor="?attr/colorOnSurface"
        app:layout_constraintTop_toBottomOf="@id/appLogo"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        android:layout_marginTop="8dp" />

    <TextView
        android:id="@+id/pinTitle"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:textAppearance="?attr/textAppearanceHeadlineSmall"
        android:textColor="?attr/colorOnSurface"
        app:layout_constraintTop_toBottomOf="@id/appName"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        android:layout_marginTop="32dp" />

    <TextView
        android:id="@+id/pinSubtitle"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:textAppearance="?attr/textAppearanceBodyMedium"
        android:textColor="?attr/colorOnSurfaceVariant"
        app:layout_constraintTop_toBottomOf="@id/pinTitle"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        android:layout_marginTop="4dp" />

    <LinearLayout
        android:id="@+id/pinDotsLayout"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        app:layout_constraintTop_toBottomOf="@id/pinSubtitle"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        android:layout_marginTop="32dp">

        <ImageView android:id="@+id/pinDot1" android:layout_width="20dp" android:layout_height="20dp" android:src="@drawable/pin_dot_empty" android:layout_marginHorizontal="8dp" />
        <ImageView android:id="@+id/pinDot2" android:layout_width="20dp" android:layout_height="20dp" android:src="@drawable/pin_dot_empty" android:layout_marginHorizontal="8dp" />
        <ImageView android:id="@+id/pinDot3" android:layout_width="20dp" android:layout_height="20dp" android:src="@drawable/pin_dot_empty" android:layout_marginHorizontal="8dp" />
        <ImageView android:id="@+id/pinDot4" android:layout_width="20dp" android:layout_height="20dp" android:src="@drawable/pin_dot_empty" android:layout_marginHorizontal="8dp" />
    </LinearLayout>

    <TextView
        android:id="@+id/pinErrorText"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:textAppearance="?attr/textAppearanceBodySmall"
        android:textColor="?attr/colorError"
        android:visibility="invisible"
        app:layout_constraintTop_toBottomOf="@id/pinDotsLayout"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        android:layout_marginTop="8dp" />

    <GridLayout
        android:id="@+id/numberPad"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:columnCount="3"
        android:rowCount="4"
        android:useDefaultMargins="false"
        app:layout_constraintTop_toBottomOf="@id/pinErrorText"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        android:layout_marginTop="24dp" />

    <com.google.android.material.button.MaterialButton
        android:id="@+id/resetPinButton"
        style="@style/Widget.Material3.Button.TextButton"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/pin_reset_button"
        app:layout_constraintTop_toBottomOf="@id/numberPad"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintBottom_toBottomOf="parent"
        android:layout_marginTop="16dp"
        android:layout_marginBottom="16dp" />

</androidx.constraintlayout.widget.ConstraintLayout>
```

- [ ] **Step 2: Add dot fill animation and shake-on-error to `PinAuthenticationActivity.kt`**

Add these two private methods to `PinAuthenticationActivity`:

```kotlin
private fun animateDotFill(view: View) {
    val scaleX = ObjectAnimator.ofFloat(view, "scaleX", 0.8f, 1.0f)
    val scaleY = ObjectAnimator.ofFloat(view, "scaleY", 0.8f, 1.0f)
    AnimatorSet().apply {
        playTogether(scaleX, scaleY)
        duration = 150
        interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        start()
    }
}

private fun shakeDotsOnError() {
    val shake = android.view.animation.TranslateAnimation(-12f, 12f, 0f, 0f).apply {
        duration = 50
        repeatCount = 4
        repeatMode = android.view.animation.Animation.REVERSE
    }
    binding.pinDotsLayout.startAnimation(shake)
}
```

Add these imports at the top:
```kotlin
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.View
```

- [ ] **Step 3: Update pinDot field types and wire animations**

The existing `pinDot1–4` fields are typed as `View`. The new layout declares them as `ImageView`. Update the field declarations and the `updatePinDots()` method:

a) Change field declarations (currently `private lateinit var pinDot1: View` etc.) to:
```kotlin
private lateinit var pinDot1: ImageView
private lateinit var pinDot2: ImageView
private lateinit var pinDot3: ImageView
private lateinit var pinDot4: ImageView
```

b) Change `initializeViews()` casts from `findViewById<View>` to `findViewById<ImageView>`.

c) Change `updatePinDots()` from `setBackgroundResource(drawable)` to `setImageResource(drawable)`.

d) After each `setImageResource(R.drawable.pin_dot_filled)` call, add `animateDotFill(dotView)`:
```kotlin
// Example for each filled transition:
pinDot1.setImageResource(R.drawable.pin_dot_filled)
animateDotFill(pinDot1)
```

In the PIN error handling (where `pinErrorText` becomes visible, e.g. on wrong PIN), add `shakeDotsOnError()` before or after showing the error text.

- [ ] **Step 4: Update `setupNumberPad()` button style to MD3 tonal**

In `setupNumberPad()`, find where buttons are created. Change the button creation to use `MaterialButton` with tonal style. Replace the `Button(this)` constructor with:

```kotlin
val button = com.google.android.material.button.MaterialButton(
    this,
    null,
    com.google.android.material.R.attr.materialButtonTonalStyle
).apply {
    text = label
    textSize = 24f
    minimumHeight = 0
    minHeight = 0
    minimumWidth = 0
    minWidth = 0
    insetTop = 0
    insetBottom = 0
    layoutParams = GridLayout.LayoutParams().apply {
        width = buttonSize
        height = buttonSize
        rowSpec = GridLayout.spec(index / 3)
        columnSpec = GridLayout.spec(index % 3)
        setMargins(8, 8, 8, 8)
    }
    if (label.isEmpty()) {
        visibility = View.INVISIBLE
        isClickable = false
        isFocusable = false
    } else if (label == "⌫") {
        setOnClickListener { onBackspace() }
    } else {
        setOnClickListener { onNumberPressed(label) }
    }
}
```

Add import: `import com.google.android.material.button.MaterialButton`

- [ ] **Step 5: Verify the build compiles**

```bash
cd android && ./gradlew assembleDebug
```

- [ ] **Step 6: Commit**

```bash
cd android && git add app/src/main/res/layout/activity_pin_authentication.xml \
    app/src/main/java/com/fintrack/pk/ui/PinAuthenticationActivity.kt
git commit -m "feat: MD3 PIN screen — ConstraintLayout, logo, tonal buttons, dot animations"
```

---

### Task 12: Settings Screen Redesign

**Files:**
- Modify: `android/app/src/main/res/layout/activity_settings.xml`
- Modify: `android/app/src/main/java/com/fintrack/pk/ui/SettingsActivity.kt`

- [ ] **Step 1: Replace `activity_settings.xml` with card-based MD3 layout**

Replace the entire file content with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.core.widget.NestedScrollView
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/settingsRoot"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="?attr/colorSurface">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="16dp">

        <!-- Gmail Card -->
        <com.google.android.material.card.MaterialCardView
            style="@style/Widget.Material3.CardView.Outlined"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginBottom="12dp"
            app:cardCornerRadius="12dp"
            app:contentPaddingLeft="16dp"
            app:contentPaddingRight="16dp"
            app:contentPaddingTop="16dp"
            app:contentPaddingBottom="16dp">

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical">

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="@string/settings_section_gmail"
                    android:textAppearance="?attr/textAppearanceTitleMedium"
                    android:textColor="?attr/colorPrimary"
                    android:layout_marginBottom="12dp" />

                <com.google.android.material.chip.Chip
                    android:id="@+id/gmailStatusChip"
                    style="@style/Widget.Material3.Chip.Assist"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_marginBottom="12dp"
                    app:chipIcon="@drawable/ic_cancel"
                    android:text="@string/settings_gmail_status_disconnected" />

                <com.google.android.material.button.MaterialButton
                    android:id="@+id/connectGmailButton"
                    style="@style/Widget.Material3.Button"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="@string/settings_gmail_connect"
                    android:layout_marginBottom="8dp" />

                <com.google.android.material.button.MaterialButton
                    android:id="@+id/disconnectGmailButton"
                    style="@style/Widget.Material3.Button.OutlinedButton"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="@string/settings_gmail_disconnect"
                    android:textColor="?attr/colorError"
                    app:strokeColor="?attr/colorError"
                    android:visibility="gone" />
            </LinearLayout>
        </com.google.android.material.card.MaterialCardView>

        <!-- Security Card -->
        <com.google.android.material.card.MaterialCardView
            style="@style/Widget.Material3.CardView.Outlined"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginBottom="12dp"
            app:cardCornerRadius="12dp"
            app:contentPaddingLeft="16dp"
            app:contentPaddingRight="16dp"
            app:contentPaddingTop="16dp"
            app:contentPaddingBottom="16dp">

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical">

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="@string/settings_section_security"
                    android:textAppearance="?attr/textAppearanceTitleMedium"
                    android:textColor="?attr/colorPrimary"
                    android:layout_marginBottom="12dp" />

                <com.google.android.material.button.MaterialButton
                    android:id="@+id/changePinButton"
                    style="@style/Widget.Material3.Button.OutlinedButton"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="@string/settings_change_pin" />
            </LinearLayout>
        </com.google.android.material.card.MaterialCardView>

        <!-- About Card -->
        <com.google.android.material.card.MaterialCardView
            style="@style/Widget.Material3.CardView.Outlined"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            app:cardCornerRadius="12dp"
            app:contentPaddingLeft="16dp"
            app:contentPaddingRight="16dp"
            app:contentPaddingTop="16dp"
            app:contentPaddingBottom="16dp">

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical">

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="@string/settings_section_about"
                    android:textAppearance="?attr/textAppearanceTitleMedium"
                    android:textColor="?attr/colorPrimary"
                    android:layout_marginBottom="12dp" />

                <TextView
                    android:id="@+id/appVersionText"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:textAppearance="?attr/textAppearanceBodyMedium"
                    android:textColor="?attr/colorOnSurfaceVariant"
                    android:layout_marginBottom="12dp" />

                <com.google.android.material.button.MaterialButton
                    android:id="@+id/viewLogsButton"
                    style="@style/Widget.Material3.Button.TextButton"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="@string/settings_view_logs" />
            </LinearLayout>
        </com.google.android.material.card.MaterialCardView>

    </LinearLayout>
</androidx.core.widget.NestedScrollView>
```

- [ ] **Step 2: Update `SettingsActivity.kt` — remove old views and add chip + new button fields**

`SettingsActivity` uses `lateinit var` with `findViewById` (not ViewBinding). You must explicitly remove old field declarations that reference views deleted from the layout, and add new ones.

a) **Remove** the field declaration `private lateinit var gmailStatusText: TextView` and its `findView` call `gmailStatusText = findViewById(R.id.gmailStatusText)` in `initializeViews()`.

b) **Add** new field declarations:
```kotlin
private lateinit var gmailStatusChip: com.google.android.material.chip.Chip
private lateinit var connectGmailButton: com.google.android.material.button.MaterialButton
private lateinit var disconnectGmailButton: com.google.android.material.button.MaterialButton
```

c) **Add** their `findViewById` calls in `initializeViews()`:
```kotlin
gmailStatusChip = findViewById(R.id.gmailStatusChip)
connectGmailButton = findViewById(R.id.connectGmailButton)
disconnectGmailButton = findViewById(R.id.disconnectGmailButton)
```

d) Update the Gmail status method with chip-based updates. Use the `lateinit var` fields directly (no `binding.` — `SettingsActivity` uses `lateinit var + findViewById`):

```kotlin
private fun updateGmailStatus(isConnected: Boolean) {
    if (isConnected) {
        gmailStatusChip.text = getString(R.string.settings_gmail_status_connected)
        gmailStatusChip.chipIcon = ContextCompat.getDrawable(this, R.drawable.ic_check_circle)
        gmailStatusChip.setChipIconTintResource(R.color.md_theme_primary)
        connectGmailButton.text = getString(R.string.settings_gmail_reconnect)
        disconnectGmailButton.visibility = View.VISIBLE
    } else {
        gmailStatusChip.text = getString(R.string.settings_gmail_status_disconnected)
        gmailStatusChip.chipIcon = ContextCompat.getDrawable(this, R.drawable.ic_cancel)
        gmailStatusChip.setChipIconTintResource(R.color.md_theme_error)
        connectGmailButton.text = getString(R.string.settings_gmail_connect)
        disconnectGmailButton.visibility = View.GONE
    }
}
```

Add imports if missing:
```kotlin
import androidx.core.content.ContextCompat
import android.view.View
```

- [ ] **Step 3: Update version text string reference**

Find where `appVersionText` is set and update it to use the new string resource:
```kotlin
binding.appVersionText.text = getString(
    R.string.settings_version,
    BuildConfig.VERSION_NAME,
    BuildConfig.VERSION_CODE
)
```

- [ ] **Step 4: Verify the build compiles**

```bash
cd android && ./gradlew assembleDebug
```

If you see unresolved reference errors for view IDs that no longer exist (e.g., `gmailStatusText`, `connectButton`), update those references in `SettingsActivity.kt` to match the new view IDs from the layout.

- [ ] **Step 5: Commit**

```bash
cd android && git add app/src/main/res/layout/activity_settings.xml \
    app/src/main/java/com/fintrack/pk/ui/SettingsActivity.kt
git commit -m "feat: MD3 Settings screen — card sections, Gmail chip status indicator"
```

---

### Task 13: Debug / Logs Screen Implementation

**Files:**
- Modify: `android/app/src/main/res/layout/activity_debug.xml`
- Create: `android/app/src/main/res/menu/debug_menu.xml`
- Modify: `android/app/src/main/java/com/fintrack/pk/ui/DebugActivity.kt`

- [ ] **Step 1: Replace `activity_debug.xml` with full implementation**

Replace the entire file content:

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.coordinatorlayout.widget.CoordinatorLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/debugRoot"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="?attr/colorSurface">

    <com.google.android.material.appbar.AppBarLayout
        android:id="@+id/appBarLayout"
        android:layout_width="match_parent"
        android:layout_height="wrap_content">

        <com.google.android.material.appbar.MaterialToolbar
            android:id="@+id/toolbar"
            android:layout_width="match_parent"
            android:layout_height="?attr/actionBarSize"
            app:title="@string/debug_title" />
    </com.google.android.material.appbar.AppBarLayout>

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:orientation="vertical"
        app:layout_behavior="@string/appbar_scrolling_view_behavior">

        <com.google.android.material.tabs.TabLayout
            android:id="@+id/tabLayout"
            style="@style/Widget.Material3.TabLayout"
            android:layout_width="match_parent"
            android:layout_height="wrap_content" />

        <ViewSwitcher
            android:id="@+id/logSwitcher"
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_weight="1">

            <ScrollView
                android:layout_width="match_parent"
                android:layout_height="match_parent">
                <TextView
                    android:id="@+id/appLogsText"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:fontFamily="monospace"
                    android:textSize="12sp"
                    android:textIsSelectable="true"
                    android:padding="8dp"
                    android:textColor="?attr/colorOnSurface" />
            </ScrollView>

            <ScrollView
                android:layout_width="match_parent"
                android:layout_height="match_parent">
                <TextView
                    android:id="@+id/serverLogsText"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:fontFamily="monospace"
                    android:textSize="12sp"
                    android:textIsSelectable="true"
                    android:padding="8dp"
                    android:textColor="?attr/colorOnSurface" />
            </ScrollView>

        </ViewSwitcher>
    </LinearLayout>

    <com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
        android:id="@+id/fab"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom|end"
        android:layout_margin="16dp"
        app:icon="@drawable/ic_share"
        android:text="@string/debug_export" />

</androidx.coordinatorlayout.widget.CoordinatorLayout>
```

- [ ] **Step 2: Create `debug_menu.xml`**

Create `android/app/src/main/res/menu/debug_menu.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<menu xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto">
    <item
        android:id="@+id/action_refresh"
        android:title="@string/debug_refresh"
        android:icon="@drawable/ic_refresh"
        app:showAsAction="ifRoom" />
</menu>
```

- [ ] **Step 3: Implement `DebugActivity.kt`**

Replace the entire class body with:

```kotlin
package com.fintrack.pk.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.fintrack.pk.R
import com.fintrack.pk.databinding.ActivityDebugBinding
import com.google.android.material.tabs.TabLayout
import androidx.core.app.ShareCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DebugActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDebugBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDebugBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        enableEdgeToEdge()   // opt-in before insets (super.onCreate already called above)

        ViewCompat.setOnApplyWindowInsetsListener(binding.appBarLayout) { view, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(0, top, 0, 0)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.debugRoot) { view, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            view.setPadding(0, 0, 0, nav)
            insets
        }

        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.debug_tab_app))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.debug_tab_server))

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                binding.logSwitcher.displayedChild = tab.position
                loadLogs(tab.position)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        binding.fab.setOnClickListener { exportLogs() }
        loadLogs(0)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.debug_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_refresh -> { loadLogs(binding.tabLayout.selectedTabPosition); true }
        android.R.id.home -> { finish(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun loadLogs(tab: Int) {
        val fileName = if (tab == 0) "app_logs.txt" else "server_logs.txt"
        val file = File(filesDir, "logs/$fileName")
        val textView = if (tab == 0) binding.appLogsText else binding.serverLogsText
        val scrollView = textView.parent as? ScrollView

        textView.text = if (file.exists()) {
            val content = file.readLines().takeLast(500).joinToString("\n")
            content.ifEmpty { getString(R.string.debug_no_logs) }
        } else {
            getString(R.string.debug_no_logs)
        }
        scrollView?.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun exportLogs() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val exportFile = File(cacheDir, "fintrack_logs_$timestamp.txt")
        val appLogs = File(filesDir, "logs/app_logs.txt").takeIf { it.exists() }?.readText() ?: "(empty)"
        val serverLogs = File(filesDir, "logs/server_logs.txt").takeIf { it.exists() }?.readText() ?: "(empty)"
        exportFile.writeText("=== APP LOGS ===\n$appLogs\n\n=== SERVER LOGS ===\n$serverLogs")

        ShareCompat.IntentBuilder(this)
            .setType("text/plain")
            .setStream(FileProvider.getUriForFile(this, "$packageName.fileprovider", exportFile))
            .setChooserTitle(getString(R.string.debug_export_chooser))
            .startChooser()
    }
}
```

- [ ] **Step 4: Verify the build compiles**

```bash
cd android && ./gradlew assembleDebug
```

- [ ] **Step 5: Commit**

```bash
cd android && git add app/src/main/res/layout/activity_debug.xml \
    app/src/main/res/menu/debug_menu.xml \
    app/src/main/java/com/fintrack/pk/ui/DebugActivity.kt
git commit -m "feat: implement Debug screen — App/Server log tabs, auto-scroll, Export FAB"
```

---

### Task 14: Main Screen Chrome Update

**Files:**
- Modify: `android/app/src/main/res/layout/activity_main.xml`
- Modify: `android/app/src/main/java/com/fintrack/pk/ui/MainActivity.kt`

- [ ] **Step 1: Update `activity_main.xml`**

Make these targeted changes to the existing layout (do not rewrite the entire file — the WebView and SwipeRefreshLayout wiring must stay intact):

a) Replace `<androidx.appcompat.widget.Toolbar` with `<com.google.android.material.appbar.MaterialToolbar` and add the MD3 style:
```xml
<com.google.android.material.appbar.MaterialToolbar
    android:id="@+id/toolbar"
    android:layout_width="match_parent"
    android:layout_height="?attr/actionBarSize"
    style="@style/Widget.Material3.Toolbar.Surface"
    app:title="FinTrack PK" />
```

b) Add `LinearProgressIndicator` directly below `AppBarLayout` (still inside `CoordinatorLayout`, above `SwipeRefreshLayout`):
```xml
<com.google.android.material.progressindicator.LinearProgressIndicator
    android:id="@+id/progressIndicator"
    style="@style/Widget.Material3.LinearProgressIndicator"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:indeterminate="true"
    android:visibility="gone"
    app:layout_behavior="@string/appbar_scrolling_view_behavior" />
```

c) Remove `loadingLayout` LinearLayout (the one with ProgressBar and loading text) entirely.

d) Wrap the error views (`errorTitle`, `errorMessage`, `retryButton`, `viewLogsButton`) in a `MaterialCardView`:
```xml
<com.google.android.material.card.MaterialCardView
    android:id="@+id/errorCard"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_gravity="center"
    android:layout_margin="24dp"
    app:cardBackgroundColor="?attr/colorErrorContainer"
    app:cardCornerRadius="16dp"
    app:contentPaddingLeft="16dp"
    app:contentPaddingRight="16dp"
    app:contentPaddingTop="16dp"
    app:contentPaddingBottom="16dp"
    android:visibility="gone">
    <!-- keep existing error title, message, and button views inside -->
</com.google.android.material.card.MaterialCardView>
```

e) Remove the `oauthBanner` and `networkStatusBanner` `MaterialCardView` overlay elements.

- [ ] **Step 2: Update `MainActivity.kt` — field declarations and removed view cleanup**

`MainActivity` uses `lateinit var` + `findViewById` (not ViewBinding). Do the following:

a) **Remove** these `lateinit var` field declarations (their layout views are being deleted):
```kotlin
// DELETE these lines:
private lateinit var loadingLayout: LinearLayout
private lateinit var loadingText: TextView
private lateinit var oauthBanner: com.google.android.material.card.MaterialCardView
private lateinit var oauthBannerText: TextView
// Also delete networkStatusBanner / networkStatusText if declared
```

b) **Add** a field for the new progress indicator:
```kotlin
private lateinit var progressIndicator: com.google.android.material.progressindicator.LinearProgressIndicator
```

c) In the `initializeViews()` / `setupViews()` method, **remove** `findViewById` calls for the deleted views and **add**:
```kotlin
progressIndicator = findViewById(R.id.progressIndicator)
```

d) Find and replace loading methods:
```kotlin
private fun showLoading(message: String? = null) {
    progressIndicator.visibility = View.VISIBLE
    webView.visibility = View.INVISIBLE
}

private fun hideLoading() {
    progressIndicator.visibility = View.GONE
    webView.visibility = View.VISIBLE
}
```

e) Add `android:id="@+id/coordinatorRoot"` to the root `CoordinatorLayout` in `activity_main.xml` (Task 14 Step 1 layout changes), then replace `showOAuthBanner` using that ID:
```kotlin
private fun showOAuthBanner(message: String) {
    val coordinatorRoot = findViewById<androidx.coordinatorlayout.widget.CoordinatorLayout>(R.id.coordinatorRoot)
    Snackbar.make(coordinatorRoot, message, Snackbar.LENGTH_INDEFINITE)
        .setAction("OK") { it.dismiss() }
        .show()
}
```

f) Replace `showNetworkBanner` the same way:
```kotlin
private fun showNetworkBanner(message: String) {
    val coordinatorRoot = findViewById<androidx.coordinatorlayout.widget.CoordinatorLayout>(R.id.coordinatorRoot)
    Snackbar.make(coordinatorRoot, message, Snackbar.LENGTH_LONG).show()
}
```

Also add `android:id="@+id/coordinatorRoot"` to the root element of `activity_main.xml` when doing Step 1 layout edits.

g) Update pull-to-refresh tint (find the pull-to-refresh setup, likely `setupPullToRefresh()`):
```kotlin
swipeRefreshLayout.setColorSchemeColors(
    com.google.android.material.color.MaterialColors.getColor(
        this,
        com.google.android.material.R.attr.colorPrimary,
        android.graphics.Color.BLUE
    )
)
```

h) Scan for any remaining references to deleted views:
```bash
grep -n "loadingLayout\|loadingText\|oauthBanner\|oauthBannerText\|networkStatusBanner\|networkStatusText" \
    android/app/src/main/java/com/fintrack/pk/ui/MainActivity.kt
```
Replace each remaining reference with the equivalent Snackbar call or progress indicator toggle.

Add imports at the top if missing:
```kotlin
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.color.MaterialColors
```

- [ ] **Step 3: Verify the build compiles**

```bash
cd android && ./gradlew assembleDebug
```

Fix any unresolved reference errors from removed view IDs before proceeding.

- [ ] **Step 4: Final device verification**

Install and run the complete app:
```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

Run this full verification checklist:
- [ ] App launches without crash to PIN screen
- [ ] PIN pad shows all 12 buttons in 4×3 grid with tonal style
- [ ] Correct PIN → loading indicator appears (no separate loading screen)
- [ ] WebView loads FinTrack web UI (http://127.0.0.1:8000)
- [ ] Status bar shows toolbar background color; nav bar shows surface color (edge-to-edge working)
- [ ] Settings screen shows three cards (Gmail, Security, About)
- [ ] Gmail chip shows correct icon and text for connection state
- [ ] Opening Settings → View Logs navigates to Debug screen with App/Server tabs
- [ ] Debug Export FAB shares a text file with both log sections
- [ ] Error screen (force-test by stopping server) shows card with retry button
- [ ] Network offline → Snackbar appears (no overlay banner)

- [ ] **Step 5: Commit**

```bash
cd android && git add app/src/main/res/layout/activity_main.xml \
    app/src/main/java/com/fintrack/pk/ui/MainActivity.kt
git commit -m "feat: MD3 main screen — MaterialToolbar, LinearProgressIndicator, Snackbar banners"
```

---

## Final Checklist

### Phase 1 — Stability (must all pass)
- [ ] `adb logcat | grep FINTRACK_APP_DIR` shows actual filesDir path at server start
- [ ] `adb logcat | grep "Application startup complete"` appears within 15s of PIN success
- [ ] PIN pad: 12 buttons visible, empty slot has no touch response
- [ ] Correct PIN → app does not exit to home screen
- [ ] WebView loads FinTrack web UI
- [ ] `python3.9 -m py_compile server.py database.py parsers.py merchants.py` — no errors
- [ ] Android 15 device: no content clipped under status/nav bars

### Phase 2 — UI
- [ ] All screens use MD3 theme (tonal buttons, MD3 typography, outlined cards)
- [ ] PIN screen: logo + animated 20dp dots + shake on error
- [ ] Settings: Gmail chip, card layout, string resources (no hardcoded text in XML)
- [ ] Debug: App/Server tabs, auto-scroll to latest, Export FAB works
- [ ] Main: MaterialToolbar + LinearProgressIndicator + Snackbar banners
