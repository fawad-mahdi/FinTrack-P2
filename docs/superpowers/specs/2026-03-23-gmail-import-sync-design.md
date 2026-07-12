# Gmail Import & Sync Screen — Design Spec

**Date:** 2026-03-23
**Status:** Approved
**Scope:** Refactor `static/index.html` into ES module architecture; implement Gmail Import & Sync screen (Stitch design); add bottom navigation bar.

---

## 1. Overview

The current web frontend is a monolithic `static/index.html` (~1800 lines) with all CSS, HTML, and JavaScript inline. This spec covers:

1. **ES Module refactor** — split into a maintainable file-per-concern structure
2. **Bottom navigation bar** — 4 tabs mapping to existing app sections
3. **Gmail Import & Sync screen** — implement the Stitch "Digital Vault" design as the Sync tab

No existing API endpoints are modified. No new backend functionality is added. UI stubs are used where a new endpoint would be required (Gmail connection status, reliability metric).

**Intentional behavior change:** The existing `oauth_required` branch (which called a non-existent `/api/oauth/url` endpoint) is removed. When the server returns a 500 with "Gmail auth failed", the sync screen shows a static stub message ("Gmail not connected — please authenticate on desktop first"). No OAuth redirect is implemented.

---

## 2. File Structure

```
static/
  index.html                   ← shell: <head>, CSS link, empty <div id="app">, bottom <nav>
  css/
    app.css                    ← all styles extracted from index.html (verbatim migration)
  js/
    api.js                     ← all fetch() calls; exports named async functions
    router.js                  ← tab registry, view lifecycle, hash-based routing
    auth.js                    ← PIN screen logic, session state
    utils/
      time.js                  ← relative time formatting ("X ago")
    views/
      dashboard.js             ← summary cards, category breakdown
      activity.js              ← transaction list, filters, edit/delete/approve, pending review
      sync.js                  ← Gmail Import & Sync screen (new Stitch design)
      budget.js                ← budgets, monthly report, category rules
```

**Rules:**
- `api.js` is the only file that calls `fetch()`. Views import from it exclusively.
- `router.js` owns navigation. Views never reference each other.
- Each view exports `init(container)` and optionally `destroy()`.
- `index.html` imports only `router.js` as `type="module"`. All other modules are imported as dependencies within that tree.
- PIN auth resolves in `auth.js` before `router.js` renders any view.
- The `<div id="app">` in `index.html` is empty — it is the router's mount point. All existing inline HTML in `#app` is removed and moved into view modules.

---

## 3. Bottom Navigation

Rendered once in `index.html` as a sticky `<nav class="bottom-nav">`:

| Tab | Icon | View ID | Maps To |
|---|---|---|---|
| Dashboard | home | `#dashboard` | Summary cards + category breakdown chart |
| Activity | receipt | `#activity` | Transaction list (all + pending review) + filters |
| Sync | sync | `#sync` | Gmail Import & Sync screen |
| Budget | wallet | `#budget` | Budgets + monthly report + category rules |

**Existing tab → new tab mapping:**
- Current "All Transactions" tab → `#activity`
- Current "Pending Review" tab → sub-view within `#activity` (filter preset)
- Current "Budget" tab → `#budget`
- Current "Report" tab → sub-section within `#budget`
- Current "Category Rules" tab → sub-section within `#budget`
- Current inline sync controls (header area) → removed from global header; replaced by `#sync` tab

**Navigation rules:**
- `router.navigate(viewId)` called on tap; updates URL hash
- Hash routing (`#dashboard`, `#activity`, etc.) enables browser back/forward
- **Android back-button note:** Hash navigation pushes WebView history entries. The Android `WebViewClient` should intercept `canGoBack()` to prevent navigating out of the app on the first back press. This is an existing Android concern — spec flags it but does not change Kotlin code.
- Active tab highlighted with `--primary` color (`#4EDEA3`)
- On Android: `padding-bottom: env(safe-area-inset-bottom)` to clear gesture bar
- Views are lazy-initialized: `init(container)` runs on first visit; subsequent visits show/hide the cached DOM

---

## 4. View Module Contract

```js
// Each view module must export:
export function init(container) {
  // Render HTML into container
  // Bind all event listeners
  // Fetch and display initial data
}

export function destroy() {
  // Optional: clean up timers, abort controllers, etc.
  // Called on every nav-away.
  // Filter state and pagination are preserved in module-level variables —
  // destroy() does NOT reset them.
}
```

`router.js` calls `init(container)` on first navigation to a view. On subsequent visits, it re-shows the cached container without calling `init()` again — preserving all DOM state (filters, pagination, scroll position). `destroy()` is called on every nav-away if defined; it should only clean up side effects (intervals, abort controllers), not reset view state.

---

## 5. `api.js` — API Surface

All existing `fetch()` calls are collected here. Named exports:

```js
// Auth
export async function authenticate(pin) {}              // POST /api/auth

// Sync
export async function syncGmail(dateFrom, dateTo) {}    // POST /api/sync
export async function getSyncStatus() {}                 // GET /api/sync/status

// Transactions
export async function getTransactions(params) {}         // GET /api/transactions
export async function createTransaction(body) {}         // POST /api/transactions
export async function createCashTransaction(body) {}     // POST /api/transactions/cash
export async function patchTransaction(id, body) {}      // PATCH /api/transactions/:id
export async function deleteTransaction(id) {}           // DELETE /api/transactions/:id

// Summary
export async function getSummary(params) {}              // GET /api/summary
export async function getCategorySummary(params) {}      // GET /api/summary/categories

// Categories
export async function getCategoryMappings() {}           // GET /api/categories/mappings
export async function addCategoryMapping(body) {}        // POST /api/categories/mappings
export async function deleteCategoryMapping(merchant) {} // DELETE /api/categories/mappings/:merchant

// Budgets
export async function getBudgets(month) {}               // GET /api/budgets
export async function upsertBudget(body) {}              // PUT /api/budgets
export async function suggestBudgets(month) {}           // POST /api/budgets/suggest

// Report
export async function getMonthlyReport(month) {}         // GET /api/report
```

All functions throw `ApiError` on non-2xx responses:
```js
class ApiError extends Error {
  constructor(status, detail) { super(detail); this.status = status; this.detail = detail; }
}
```

**Error detection:** Use `error.detail.includes('Gmail auth failed')` and `error.detail.includes('Gmail search failed')` — the server appends dynamic exception text after a colon, so exact equality will not match.

---

## 6. `utils/time.js` — Relative Time Formatter

`GET /api/sync/status` returns one of two shapes:
- **No sync yet:** `{"message": "No sync yet"}`
- **Sync exists:** `{"id": int, "synced_at": "YYYY-MM-DD HH:MM:SS", "emails_found": int, "parsed_ok": int, "pending": int}`

Note: the `sync_log` table stores `pending` (not `pending_review`) — the column name differs from the `POST /api/sync` response field `pending_review`. The status response only reflects the 3 stored fields; `skipped_parse` and `skipped_duplicate` are not persisted and are only available in the immediate `POST /api/sync` response.

`synced_at` is in SQLite `CURRENT_TIMESTAMP` format: `"YYYY-MM-DD HH:MM:SS"` in the server's local timezone (no timezone suffix).

`time.js` exports:
```js
// Returns a human string like "2h ago", "just now", "3 days ago"
// input: SQLite CURRENT_TIMESTAMP string "YYYY-MM-DD HH:MM:SS" (treated as local time)
export function timeAgo(sqliteTimestamp) {}

// Returns true if the response is the no-sync sentinel
export function isNoSync(statusResponse) {
  return statusResponse && 'message' in statusResponse;
}
```

`sync.js` calls `getSyncStatus()`, checks `isNoSync()`, and displays either "Never synced" or `timeAgo(data.synced_at)`.

---

## 7. Gmail Import & Sync Screen (`sync.js`)

### Layout (top to bottom)

**Header**
- App logo/title "FinTrack PK" (left)
- Lock icon (right) — stub, no action

**Gmail Status Card** (partially stubbed)
- Mail icon + "Gmail Connected" label
- Email address: static placeholder `"your.account@gmail.com"` (no API)
- "Active" badge: static green pill (no API)
- "Last synced X ago": fetched from `getSyncStatus()` on view init; displayed using `timeAgo()`; shows "Never synced" if `isNoSync()` returns true

**Date Range Section**
- Section label: "Select Date Range"
- Calendar component (plain JS, no library):
  - Month navigator (prev/next chevrons)
  - Day grid (S–M–T–W–T–F–S)
  - First tap selects start date; second tap (on a later date) selects end date; third tap resets
  - Selected range highlighted with `--primary` color (`#4EDEA3`)
  - Dates earlier than `today - 90 days` are visually disabled and unselectable (matches server `_MAX_SYNC_DAYS = 90`)
  - If the user somehow submits with an out-of-range date, the server silently clamps it — no client-side error shown; the result summary reflects what actually synced

**Sync Now Button**
- Primary gradient button (`#4EDEA3` → `#10B981`, 135°)
- On press: disabled + spinner + "Syncing…" label
- Calls `syncGmail(dateFrom, dateTo)`
- On success: renders result summary inline below button:
  - `emails_found` — "Emails found"
  - `parsed_ok` — "Imported"
  - `pending_review` — "Pending review"
  - `skipped_parse` — "Skipped (unreadable)"
  - `skipped_duplicate` — "Already imported"
- On error: inline error message with retry button

**Reliability Metric** (UI stub)
- Static text: "Syncing reliability 99.8%"
- No backend connection

### Error States

| Condition | Detection | Display |
|---|---|---|
| Gmail not authenticated | `error.status === 500 && error.detail.includes('Gmail auth failed')` | "Gmail not connected — please authenticate on desktop first." |
| Gmail search failed | `error.status === 500 && error.detail.includes('Gmail search failed')` | "Search failed. Tap to retry." + retry button |
| Network offline | `fetch()` throws `TypeError` | "No connection. Check your network." |
| Other server error | Any other `ApiError` | `error.detail` displayed inline |

---

## 8. CSS Migration

- All styles moved verbatim from `index.html` into `static/css/app.css`
- No style changes during migration
- **New CSS token added:** `--primary: #4EDEA3` added to `:root` in `app.css`. Existing PIN screen hardcoded `#4EDEA3` values are replaced with `var(--primary)`. Existing `--green: #22c55e` is preserved (used for transaction amounts) — these are distinct colors.
- New sync screen styles appended as a labeled block: `/* ── SYNC VIEW ── */`
- `index.html` links `app.css` via `<link rel="stylesheet" href="/static/css/app.css">`

---

## 9. Android Static File Serving

The Python FastAPI server serves static files via `app.mount("/static", StaticFiles(directory=...))` in `server.py`. On Android, `ServerProcessManager` extracts `server.py` and its sibling files into the app's `filesDir`. The static files must live alongside `server.py`.

**Source of truth for Android static files:**
```
android/app/src/main/python/static/   ← Python server's static dir on Android
```

The `android/app/src/main/assets/` copies are loaded by the WebView before the server starts (as a loading screen fallback). After the refactor, the Android Python static dir must also contain:
```
static/
  index.html
  css/app.css
  js/api.js
  js/router.js
  js/auth.js
  js/utils/time.js
  js/views/dashboard.js
  js/views/activity.js
  js/views/sync.js
  js/views/budget.js
```

The migration step copies from `static/` (project root) → `android/app/src/main/python/static/`.

---

## 10. Migration Strategy

Run in order to minimize regression risk:

1. **CSS extraction** — move styles to `static/css/app.css`; add `--primary` token; verify visual parity
2. **`api.js` + `utils/time.js`** — collect all `fetch()` calls into named exports; implement `timeAgo()`
3. **`auth.js`** — move PIN screen logic; verify PIN flow works
4. **`dashboard.js`, `activity.js`, `budget.js`** — migrate existing views; clear `#app` of inline HTML
5. **`router.js` + bottom nav** — wire tab switching; hash routing; verify all views reachable
6. **`sync.js`** — build new Gmail Import & Sync screen on stable infrastructure
7. **Android sync** — copy `static/` tree to `android/app/src/main/python/static/`

---

## 11. Out of Scope

- No new FastAPI endpoints
- No Gmail OAuth flow (intentionally removed — was calling a non-existent endpoint)
- No changes to `server.py`, `database.py`, `parsers.py`, `auth_gmail.py`
- No changes to Android Kotlin code (back-button hash routing behavior is flagged but not fixed here)
- No automated tests (per project convention for web layer)
- No font bundling for offline Android use (pre-existing issue, not addressed here)

---

## 12. Verification Checklist

- [ ] PIN auth still works; session persists across tab switches
- [ ] All 4 bottom nav tabs render their respective views
- [ ] Dashboard summary cards and category breakdown load correctly
- [ ] Activity view: transaction list, filters, pending review sub-view all function
- [ ] Budget view: set budgets, suggest budgets, monthly report, category rules all function
- [ ] Sync view: calendar date selection works; past 90 days are disabled
- [ ] Sync Now calls `POST /api/sync` with correct `date_from`/`date_to` params
- [ ] Sync result summary shows all 5 fields from `POST /api/sync` response (emails_found, parsed_ok, pending_review, skipped_parse, skipped_duplicate) — note: skipped_parse and skipped_duplicate are not persisted; they only appear in the immediate sync response
- [ ] "Last synced X ago" displays correctly from `GET /api/sync/status`; shows "Never synced" on first install
- [ ] Gmail auth error shows stub message (not OAuth redirect)
- [ ] Network offline state renders correctly
- [ ] Android WebView renders correctly (Python static dir updated)
- [ ] `--primary` token used consistently; `--green` unchanged
