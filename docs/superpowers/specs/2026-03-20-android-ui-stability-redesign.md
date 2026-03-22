# Android UI Stability & MD3 Redesign Spec

**Date:** 2026-03-20
**Project:** FinTracker PK — Android App
**Scope:** Stability fixes (Phase 1) + Material Design 3 UI redesign (Phase 2)
**Out of scope:** No new features; web frontend unchanged; Python backend logic unchanged
**Target device:** Android 15 (API 35)
**Python runtime:** Chaquopy, Python 3.9 (in-process — NOT a separate process)

---

## Context

The Android app embeds a Python FastAPI server via Chaquopy (in-process, not ProcessBuilder). The server runs in a background thread via `python.getModule("main").callAttr("start_server", host, port)`. A WebView renders the existing HTML/JS frontend at http://127.0.0.1:8000.

The app currently fails at:
- **B**: PIN pad possibly stuck; after PIN entry, server startup fails → error screen or silent exit
- **C**: Python server never starts → WebView stays on loading screen

Both B and C trace to: Python env vars set as JVM system properties but Python reads `os.environ`; and the `main` module import may not resolve to the actual `server.py`. Secondary: a race condition leaves `isRunning = true` even after the server thread has already crashed, causing misleading health check state.

---

## Phase 1: Stability Fixes

### Fix 1 — Python Env Vars Not Reaching `os.environ`

**File:** `android/app/src/main/java/com/fintrack/pk/server/ServerProcessManager.kt`

**Problem:** `setupEnvironment()` uses `System.setProperty("FINTRACK_APP_DIR", ...)`. These are JVM system properties. Python's `os.getenv()` reads the OS process environment — it does not see JVM properties. When `server.py` calls `os.getenv("FINTRACK_APP_DIR")`, it returns `None` and all file paths fall back to relative paths that don't exist on Android.

**Fix:** In `setupEnvironment()`, after setting JVM properties, also inject values directly into Python's `os.environ` via Chaquopy before calling `start_server`:

```kotlin
private fun setupPythonEnvironment(python: Python, dirs: Map<String, String>) {
    val pyOs = python.getModule("os")
    val pyEnviron = pyOs.get("environ")
    dirs.forEach { (key, value) ->
        pyEnviron?.callAttr("__setitem__", key, value)
    }
}
```

Call this with the same directory map immediately before `pythonModule?.callAttr("start_server", ...)`. Keep the existing `System.setProperty` calls as a fallback.

**Verification:** Log `python.getModule("os").get("environ")?.callAttr("get", "FINTRACK_APP_DIR")` before server start — must return the actual `filesDir` path, not `None`.

---

### Fix 2 — `main` Module Import May Fail Silently

**Files:** `ServerProcessManager.kt`, `app/build.gradle.kts`, new file `android/app/src/main/python/main.py`

**Problem:** `python.getModule("main")` imports a Python module named `main`. If no `main.py` exists in Chaquopy's source set (`src/main/python/`), `getModule` throws an exception that is swallowed — `pythonModule` is null, `?.callAttr(...)` is silently skipped, the server thread exits immediately, and the app stays on the loading screen forever.

**Fix — two steps:**

Step A: Check whether `android/app/src/main/python/` exists and contains `main.py`. If not, create it:

```python
# android/app/src/main/python/main.py
import sys
import os

def start_server(host, port):
    app_dir = os.environ.get("FINTRACK_APP_DIR", "")
    if app_dir and app_dir not in sys.path:
        sys.path.insert(0, app_dir)

    import uvicorn
    from server import app as fastapi_app
    uvicorn.run(fastapi_app, host=str(host), port=int(port), log_level="info")
```

Step B: Also ensure `server.py`, `database.py`, `parsers.py`, and `merchants.py` are accessible via the injected `sys.path`. Either copy them into `src/main/python/` (so Chaquopy bundles them into the APK), or verify `PythonRuntimeManager` copies them to `filesDir` on first launch.

**Verification:** Server startup log shows uvicorn's `Application startup complete` within 15 seconds of `start_server()` being called.

---

### Fix 3 — `isRunning` Race Condition After Thread Crash

**File:** `ServerProcessManager.kt` (lines ~100–105)

**Problem:** The thread's catch block sets `isRunning = false` on crash (line ~100) — correct. But immediately after `serverThread?.start()` on the main thread, line ~105 unconditionally sets `isRunning = true`. If the thread crashes before line 105 executes (instant ImportError, port conflict, etc.), the crash sets `isRunning = false`, then line 105 overrides it back to `true`. Health monitoring then sees `isRunning=true` but `thread.isAlive=false` and takes an extra cycle to detect failure.

**Fix:** Change line ~105 from:
```kotlin
isRunning = true
```
to:
```kotlin
isRunning = serverThread?.isAlive == true
```

This sets `isRunning = true` only if the thread actually started successfully.

**Additional fix in the same catch block:** The `crashCallback` is never invoked on server thread crash. Add it:
```kotlin
} catch (e: Exception) {
    lastError = "Server thread error: ${e.message}"
    Logger.logError("ServerProcessManager", lastError!!, e)
    isRunning = false
    crashCallback?.invoke(e.message ?: "Server crashed")  // ADD THIS LINE
}
```

**Verification:** After an intentional crash (e.g., bad import), `isServerRunning()` returns `false` immediately and `MainActivity` shows the error screen within one health-check cycle (≤5 seconds).

---

### Fix 4 — Python 3.9 Type Syntax Compatibility

**Files:** `server.py`, `database.py`, `parsers.py`, `merchants.py`

**Problem:** Python 3.10+ syntax (`X | Y`, `list[x]`, `dict[x, y]`) is valid on desktop (Python 3.12) but raises `TypeError` or `SyntaxError` at runtime on Android (Python 3.9).

**Fix:** Audit all four files and replace:
- `X | Y` → `Union[X, Y]` (add `from typing import Union`)
- `X | None` → `Optional[X]` (add `from typing import Optional`)
- `list[x]` → `List[x]` (add `from typing import List`)
- `dict[x, y]` → `Dict[x, y]` (add `from typing import Dict`)
- Any `match`/`case` statements → `if`/`elif` chains

**Verification:** `python3.9 -c "import ast; ast.parse(open('server.py').read()); print('OK')"` for each file — all print `OK` with no exceptions.

---

### Fix 5 — Android 15 Edge-to-Edge Insets + SDK Update

**Files:** `MainActivity.kt`, `PinAuthenticationActivity.kt`, `SettingsActivity.kt`, `app/build.gradle.kts`

**SDK update** (required for edge-to-edge to apply):
```kotlin
compileSdk = 35
targetSdk = 35
```

**Problem:** Android 15 enforces edge-to-edge when `targetSdk >= 35`. Without inset handling, the `Toolbar` overlaps the status bar and system nav bar covers bottom content.

**Fix per activity** — add after `setContentView()`:

`MainActivity.kt` — separate insets for app bar (top only) and root (bottom only):
```kotlin
ViewCompat.setOnApplyWindowInsetsListener(binding.appBarLayout) { view, insets ->
    val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
    view.setPadding(0, top, 0, 0)
    insets
}
ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
    val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
    view.setPadding(0, 0, 0, nav)
    insets
}
```

`PinAuthenticationActivity.kt` and `SettingsActivity.kt` — apply full system bars to root view:
```kotlin
ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
    val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
    view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
    insets
}
```

**Verification:** On Android 15 — app bar background extends to behind status bar; nav bar area shows app background color; no content clipped under either bar.

---

### Fix 6 — GridLayout PIN Pad (Verify + Minor Fix)

**File:** `PinAuthenticationActivity.kt`, `activity_pin_authentication.xml`

**Status:** The Kotlin code already uses the correct explicit `rowSpec`/`columnSpec`. The XML already has `android:columnCount="3"` and `android:rowCount="4"`. No structural changes needed.

**One remaining fix:** The invisible empty button at index 9 still has `isClickable = true` and `isFocusable = true`. Add inside the `label.isEmpty()` branch of `setupNumberPad()`:
```kotlin
if (label.isEmpty()) {
    visibility = View.INVISIBLE
    isClickable = false
    isFocusable = false
}
```

**Verification:** All 12 grid positions render; empty slot at position 9 does not receive touch or focus; buttons 1–9, 0, ⌫ are fully tappable.

---

## Phase 2: Material Design 3 UI Redesign

### 2.0 — Foundation: Theme + SDK

**Files:** `res/values/themes.xml`, `res/values/colors.xml`, `app/build.gradle.kts`

**Material library update (already in Phase 1 SDK bump):**
```kotlin
implementation("com.google.android.material:material:1.12.0")
```

**`themes.xml` — change parent:**
```xml
<style name="Theme.FinTrackPK" parent="Theme.Material3.DayNight.NoActionBar">
    <item name="colorPrimary">@color/md_theme_primary</item>
    <item name="colorOnPrimary">@color/md_theme_onPrimary</item>
    <item name="colorPrimaryContainer">@color/md_theme_primaryContainer</item>
    <item name="colorOnPrimaryContainer">@color/md_theme_onPrimaryContainer</item>
    <item name="colorSecondary">@color/md_theme_secondary</item>
    <item name="colorSurface">@color/md_theme_surface</item>
    <item name="colorSurfaceVariant">@color/md_theme_surfaceVariant</item>
    <item name="colorOutline">@color/md_theme_outline</item>
    <item name="colorError">@color/md_theme_error</item>
    <item name="colorOnSurfaceVariant">@color/md_theme_onSurfaceVariant</item>
    <item name="android:statusBarColor">@android:color/transparent</item>
    <item name="android:navigationBarColor">@android:color/transparent</item>
</style>
```

**`colors.xml` — add MD3 tokens** (keep existing colors until layout references are updated):
```xml
<color name="md_theme_primary">#1565C0</color>
<color name="md_theme_onPrimary">#FFFFFF</color>
<color name="md_theme_primaryContainer">#D3E4FF</color>
<color name="md_theme_onPrimaryContainer">#001B3F</color>
<color name="md_theme_secondary">#E65100</color>
<color name="md_theme_surface">#F8FAFE</color>
<color name="md_theme_surfaceVariant">#DFE2EB</color>
<color name="md_theme_error">#BA1A1A</color>
<color name="md_theme_onError">#FFFFFF</color>
<color name="md_theme_outline">#72788D</color>
<color name="md_theme_onSurfaceVariant">#44474F</color>
```

---

### 2.1 — PIN Authentication Screen

**Files:** `activity_pin_authentication.xml`, `PinAuthenticationActivity.kt`, new `res/drawable/ic_fintrack_logo.xml`

**New logo drawable** — create `res/drawable/ic_fintrack_logo.xml` as a simple vector (letter "F" on primary-colored circle background, 48dp viewport). This is used instead of `ic_launcher_foreground` which is not directly referenceable as an `ImageView` src:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="48dp" android:height="48dp"
    android:viewportWidth="48" android:viewportHeight="48">
    <path android:fillColor="?attr/colorPrimary"
        android:pathData="M24,4 C12.95,4 4,12.95 4,24 C4,35.05 12.95,44 24,44 C35.05,44 44,35.05 44,24 C44,12.95 35.05,4 24,4 Z"/>
    <path android:fillColor="?attr/colorOnPrimary"
        android:pathData="M18,14 L30,14 L30,19 L23,19 L23,23 L29,23 L29,28 L23,28 L23,34 L18,34 Z"/>
</vector>
```

**`activity_pin_authentication.xml` — redesign:**
- Root: `ConstraintLayout` (replaces LinearLayout)
- Logo `ImageView`: `src="@drawable/ic_fintrack_logo"`, 64dp × 64dp, constrained top 32dp + status bar inset (handled in Kotlin)
- App name `TextView`: `textAppearance="?attr/textAppearanceHeadlineMedium"`, constrained below logo 8dp
- PIN title (`@+id/pinTitle`): `textAppearance="?attr/textAppearanceHeadlineSmall"`
- PIN subtitle (`@+id/pinSubtitle`): `textAppearance="?attr/textAppearanceBodyMedium"`, `android:textColor="?attr/colorOnSurfaceVariant"`
- PIN dot drawables updated: `pin_dot_filled.xml` → solid fill `?attr/colorPrimary` (use `@color/md_theme_primary`), 20dp × 20dp; `pin_dot_empty.xml` → stroke `?attr/colorOutline`, 20dp × 20dp
- Error text: `textAppearance="?attr/textAppearanceBodySmall"`, `android:textColor="?attr/colorError"`
- Number pad buttons: `MaterialButton` style `@style/Widget.Material3.Button.TonalButton`, `android:minHeight="56dp"`, `android:minWidth="0dp"`
- Reset button: `style="@style/Widget.Material3.Button.TextButton"`

**`PinAuthenticationActivity.kt` — additions:**
```kotlin
private fun animateDotFill(view: View) {
    val scaleX = ObjectAnimator.ofFloat(view, "scaleX", 0.8f, 1.0f)
    val scaleY = ObjectAnimator.ofFloat(view, "scaleY", 0.8f, 1.0f)
    AnimatorSet().apply {
        playTogether(scaleX, scaleY)
        duration = 150
        interpolator = AccelerateDecelerateInterpolator()
        start()
    }
}

private fun shakeDotsOnError() {
    val shake = TranslateAnimation(-12f, 12f, 0f, 0f).apply {
        duration = 50
        repeatCount = 4
        repeatMode = Animation.REVERSE
    }
    binding.pinDotsLayout.startAnimation(shake)
}
```

Call `animateDotFill(dotView)` in `updatePinDots()` when a dot transitions to filled state. Call `shakeDotsOnError()` alongside setting error text visibility. All existing PIN logic (encryption, validation, lockout countdown) remains unchanged.

---

### 2.2 — Settings Screen

**Files:** `activity_settings.xml`, `SettingsActivity.kt`, `res/values/strings.xml`

**`strings.xml` additions** (move all hardcoded XML strings here):
```xml
<string name="settings_section_gmail">Gmail Connection</string>
<string name="settings_section_security">Security</string>
<string name="settings_section_about">About</string>
<string name="settings_gmail_connect">Connect Gmail</string>
<string name="settings_gmail_reconnect">Reconnect Gmail</string>
<string name="settings_gmail_disconnect">Disconnect Gmail</string>
<string name="settings_change_pin">Change PIN</string>
<string name="settings_view_logs">View Logs</string>
<string name="settings_version">Version %s (%d)</string>
<string name="settings_gmail_status_connected">Connected</string>
<string name="settings_gmail_status_disconnected">Not Connected</string>
```

**`activity_settings.xml` — redesign:**
- Root: `NestedScrollView`; child: vertical `LinearLayout`, 16dp padding, 12dp item spacing
- Three `MaterialCardView` sections using `style="@style/Widget.Material3.CardView.Outlined"` (0dp elevation, 1dp stroke `?attr/colorOutline`, 12dp corner radius, 16dp internal padding, 12dp bottom margin):

  **Gmail card:**
  - Header: `TextView` `textAppearance="?attr/textAppearanceTitleMedium"` `textColor="?attr/colorPrimary"` `text="@string/settings_section_gmail"`
  - Status: `Chip` `style="@style/Widget.Material3.Chip.Assist"` — chip text and icon set programmatically
  - Connect button: `MaterialButton` `style="@style/Widget.Material3.Button"` `text="@string/settings_gmail_connect"`
  - Disconnect button: `MaterialButton` `style="@style/Widget.Material3.Button.OutlinedButton"` `android:textColor="?attr/colorError"` `app:strokeColor="?attr/colorError"` `text="@string/settings_gmail_disconnect"` `visibility="gone"`

  **Security card:**
  - Header: same style, `text="@string/settings_section_security"`
  - Change PIN: `MaterialButton` `style="@style/Widget.Material3.Button.OutlinedButton"` `text="@string/settings_change_pin"`

  **About card:**
  - Header: same style, `text="@string/settings_section_about"`
  - Version: `TextView` `textAppearance="?attr/textAppearanceBodyMedium"` `textColor="?attr/colorOnSurfaceVariant"`
  - View Logs: `MaterialButton` `style="@style/Widget.Material3.Button.TextButton"` `text="@string/settings_view_logs"`

**`SettingsActivity.kt` — color fix:**
Replace `getColor(android.R.color.holo_green_dark)` and `getColor(android.R.color.holo_red_dark)` with MD3 attribute-based colors:
```kotlin
// Connected state:
chip.chipIcon = ContextCompat.getDrawable(this, R.drawable.ic_check_circle)
chip.setChipIconTintResource(R.color.md_theme_primary)
chip.text = getString(R.string.settings_gmail_status_connected)

// Disconnected state:
chip.chipIcon = ContextCompat.getDrawable(this, R.drawable.ic_cancel)
chip.setChipIconTintResource(R.color.md_theme_error)
chip.text = getString(R.string.settings_gmail_status_disconnected)
```
Add `ic_check_circle` and `ic_cancel` from the Material Symbols set to `res/drawable/`.

---

### 2.3 — Debug / Logs Screen Implementation

**Files:** `activity_debug.xml`, `DebugActivity.kt`, `res/menu/debug_menu.xml`

**Note:** `FileProvider` is already declared in `AndroidManifest.xml` and `res/xml/file_paths.xml` already includes `<cache-path name="cache" path="." />` — no manifest changes needed for export.

**`activity_debug.xml`:**
```xml
<CoordinatorLayout>
  <AppBarLayout>
    <MaterialToolbar android:id="@+id/toolbar" app:title="Logs" />
  </AppBarLayout>
  <LinearLayout android:orientation="vertical" app:layout_behavior="@string/appbar_scrolling_view_behavior">
    <TabLayout android:id="@+id/tabLayout" style="@style/Widget.Material3.TabLayout">
      <!-- tabs added programmatically -->
    </TabLayout>
    <ViewSwitcher android:id="@+id/logSwitcher" android:layout_width="match_parent" android:layout_height="0dp" android:layout_weight="1">
      <ScrollView><TextView android:id="@+id/appLogsText" android:fontFamily="monospace" android:textSize="12sp" android:textIsSelectable="true" android:padding="8dp"/></ScrollView>
      <ScrollView><TextView android:id="@+id/serverLogsText" android:fontFamily="monospace" android:textSize="12sp" android:textIsSelectable="true" android:padding="8dp"/></ScrollView>
    </ViewSwitcher>
  </LinearLayout>
  <ExtendedFloatingActionButton android:id="@+id/fab" app:icon="@drawable/ic_share" android:text="Export" android:layout_gravity="bottom|end" android:layout_margin="16dp"/>
</CoordinatorLayout>
```

**`res/menu/debug_menu.xml`:**
```xml
<menu>
  <item android:id="@+id/action_refresh" android:title="Refresh" android:icon="@drawable/ic_refresh" app:showAsAction="ifRoom"/>
</menu>
```

**`DebugActivity.kt` — full implementation:**
```kotlin
class DebugActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDebugBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDebugBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Separate insets: top to AppBarLayout (extends behind status bar), bottom to root
        ViewCompat.setOnApplyWindowInsetsListener(binding.appBarLayout) { view, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(0, top, 0, 0)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.setPadding(0, 0, 0, nav)
            insets
        }

        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("App"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Server"))

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
            content.ifEmpty { "No logs yet." }
        } else {
            "No logs yet."
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
            .setChooserTitle("Export Logs")
            .startChooser()
    }
}
```

---

### 2.4 — Main Screen Chrome

**Files:** `activity_main.xml`, `MainActivity.kt`

**`activity_main.xml` changes:**
- Replace `<androidx.appcompat.widget.Toolbar>` → `<com.google.android.material.appbar.MaterialToolbar>` with `style="@style/Widget.Material3.Toolbar.Surface"`
- Add `<LinearProgressIndicator android:id="@+id/progressIndicator" style="@style/Widget.Material3.LinearProgressIndicator" android:indeterminate="true" android:visibility="gone"/>` immediately below `AppBarLayout` (inside `CoordinatorLayout`, above `SwipeRefreshLayout`)
- Remove `loadingLayout` LinearLayout (with spinner + loading text) entirely
- Wrap error views in `MaterialCardView` (`style="@style/Widget.Material3.CardView.Filled"`, `app:cardBackgroundColor="?attr/colorErrorContainer"`, 16dp corner radius, 16dp padding, 24dp margin, centered in FrameLayout)
- Remove `oauthBanner` and `networkStatusBanner` MaterialCardView overlays

**`MainActivity.kt` changes:**
```kotlin
// Replace showLoading/hideLoading:
private fun showLoading(message: String? = null) {
    binding.progressIndicator.visibility = View.VISIBLE
}
private fun hideLoading() {
    binding.progressIndicator.visibility = View.GONE
}

// Replace showOAuthBanner:
private fun showOAuthBanner(message: String) {
    Snackbar.make(binding.root, message, Snackbar.LENGTH_INDEFINITE)
        .setAction("OK") { it.dismiss() }
        .show()
}

// Replace showNetworkBanner:
private fun showNetworkBanner(message: String) {
    Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
}

// Pull-to-refresh tint:
binding.swipeRefreshLayout.setColorSchemeColors(
    MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, Color.BLUE)
)
```

---

## Success Criteria

### Phase 1 (Stability — must pass before starting Phase 2)
- [ ] `os.environ["FINTRACK_APP_DIR"]` in Python returns actual `filesDir` path (logged at server startup)
- [ ] Server log shows `Application startup complete` within 15 seconds of PIN success
- [ ] PIN pad displays all 12 buttons in 4×3 grid; empty slot has no touch interaction
- [ ] After correct PIN entry, app returns to `MainActivity` loading screen (does not exit to home)
- [ ] WebView loads FinTrack web UI at http://127.0.0.1:8000
- [ ] `python3.9 -m py_compile server.py database.py parsers.py merchants.py` — no errors
- [ ] On Android 15: status bar and nav bar areas correctly padded, no content clipped
- [ ] After server crash: `isServerRunning()` returns `false` within one health-check cycle; error screen appears

### Phase 2 (UI — verify on device after Phase 1 passes)
- [ ] All screens use `Theme.Material3.DayNight.NoActionBar` — MD3 type scale, tonal colors, rounded components
- [ ] PIN screen: logo, 20dp animated dots, tonal number buttons, shake on error
- [ ] Settings screen: three outlined cards, Gmail chip status indicator, proper button hierarchy, no hardcoded strings in XML
- [ ] Debug screen: App/Server tabs, log text loads and auto-scrolls, Export FAB shares combined file
- [ ] Main screen: `MaterialToolbar`, `LinearProgressIndicator` (no separate loading layout), `Snackbar` for banners

---

## File Change Summary

| File | Phase | Change Type |
|---|---|---|
| `ServerProcessManager.kt` | 1 | Fix Python env vars + isRunning race + add crashCallback |
| `android/app/src/main/python/main.py` | 1 | Create — Chaquopy entry point wrapping server.py |
| `server.py` | 1 | Python 3.9 type syntax compat |
| `database.py` | 1 | Python 3.9 type syntax compat |
| `parsers.py` | 1 | Python 3.9 type syntax compat |
| `merchants.py` | 1 | Python 3.9 type syntax compat |
| `MainActivity.kt` | 1, 2 | Edge-to-edge insets + MD3 chrome |
| `PinAuthenticationActivity.kt` | 1, 2 | Invisible button fix + MD3 redesign |
| `SettingsActivity.kt` | 2 | MD3 chip status + string refs |
| `DebugActivity.kt` | 2 | Full implementation |
| `activity_main.xml` | 2 | MD3 toolbar, progress indicator, error card, remove banners |
| `activity_pin_authentication.xml` | 2 | MD3 redesign (ConstraintLayout, tonal buttons) |
| `activity_settings.xml` | 2 | Card-based sections, chip, proper buttons |
| `activity_debug.xml` | 2 | Full implementation (ViewSwitcher + TabLayout + FAB) |
| `res/values/themes.xml` | 2 | MD3 parent theme |
| `res/values/colors.xml` | 2 | MD3 color tokens added |
| `res/values/strings.xml` | 2 | Hardcoded strings moved here |
| `res/drawable/ic_fintrack_logo.xml` | 2 | New logo vector drawable |
| `res/drawable/ic_check_circle.xml` | 2 | New — for Gmail connected chip |
| `res/drawable/ic_cancel.xml` | 2 | New — for Gmail disconnected chip |
| `res/drawable/ic_share.xml` | 2 | New — for Debug FAB |
| `res/drawable/ic_refresh.xml` | 2 | New — for Debug toolbar menu |
| `res/menu/debug_menu.xml` | 2 | New — refresh action |
| `res/drawable/pin_dot_filled.xml` | 2 | Enlarge to 20dp |
| `res/drawable/pin_dot_empty.xml` | 2 | Enlarge to 20dp |
| `app/build.gradle.kts` | 1, 2 | compileSdk/targetSdk → 35, Material 1.12.0 |
