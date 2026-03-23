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

---

## 2. File Structure

```
static/
  index.html              ← shell: <head>, CSS link, <div id="app">, bottom <nav>
  css/
    app.css               ← all styles extracted from index.html (verbatim migration)
  js/
    api.js                ← all fetch() calls; exports named async functions
    router.js             ← tab registry, view lifecycle, hash-based routing
    auth.js               ← PIN screen logic, session state
    views/
      dashboard.js        ← summary cards, category breakdown
      activity.js         ← transaction list, filters, edit/delete/approve
      sync.js             ← Gmail Import & Sync screen (new Stitch design)
      budget.js           ← budgets + merchant rules
```

**Rules:**
- `api.js` is the only file that calls `fetch()`. Views import from it exclusively.
- `router.js` owns navigation. Views never reference each other.
- Each view exports `init(container)` and optionally `destroy()`.
- `index.html` imports only `router.js` as `type="module"`. All other modules are imported as dependencies within the tree.
- PIN auth resolves in `auth.js` before `router.js` renders any view.

---

## 3. Bottom Navigation

Rendered once in `index.html` as a sticky `<nav class="bottom-nav">`:

| Tab | Icon | View ID | Maps To |
|---|---|---|---|
| Dashboard | home | `#dashboard` | Summary cards + category chart |
| Activity | receipt | `#activity` | Transaction list + filters |
| Sync | sync | `#sync` | Gmail Import & Sync screen |
| Budget | wallet | `#budget` | Budgets + rules |

- Active tab highlighted with `primary` color (`#4EDEA3`)
- `router.navigate(viewId)` called on tap; updates URL hash
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
}
```

`router.js` calls `init(container)` on first navigation to a view. On subsequent visits, it shows the cached container. `destroy()` is called when navigating away if defined.

---

## 5. `api.js` — API Surface

All existing `fetch()` calls are collected here. Named exports:

```js
// Auth
export async function authenticate(pin) {}            // POST /api/auth

// Sync
export async function syncGmail(dateFrom, dateTo) {}  // POST /api/sync
export async function getSyncStatus() {}               // GET /api/sync/status

// Transactions
export async function getTransactions(params) {}       // GET /api/transactions
export async function createTransaction(body) {}       // POST /api/transactions
export async function createCashTransaction(body) {}   // POST /api/transactions/cash
export async function patchTransaction(id, body) {}    // PATCH /api/transactions/:id
export async function deleteTransaction(id) {}         // DELETE /api/transactions/:id

// Summary
export async function getSummary(params) {}            // GET /api/summary
export async function getCategorySummary(params) {}    // GET /api/summary/categories

// Categories
export async function getCategoryMappings() {}         // GET /api/categories/mappings
export async function addCategoryMapping(body) {}      // POST /api/categories/mappings
export async function deleteCategoryMapping(merchant) {} // DELETE /api/categories/mappings/:merchant

// Budgets
export async function getBudgets(month) {}             // GET /api/budgets
export async function upsertBudget(body) {}            // PUT /api/budgets
export async function suggestBudgets(month) {}         // POST /api/budgets/suggest

// Report
export async function getMonthlyReport(month) {}       // GET /api/report
```

All functions throw `ApiError` on non-2xx responses:
```js
class ApiError extends Error {
  constructor(status, detail) { super(detail); this.status = status; }
}
```

---

## 6. Gmail Import & Sync Screen (`sync.js`)

### Layout (top to bottom)

**Header**
- App logo/title "FinTrack PK" (left)
- Lock icon (right) — navigates to PIN re-auth (stub)

**Gmail Status Card** (UI stub — no new API endpoint)
- Mail icon + "Gmail Connected" label
- Email placeholder (static: "your.account@gmail.com")
- "Active" badge (static green pill)
- "Last synced X ago" — derived from `getSyncStatus()` response; falls back to "Never" if no sync yet

**Date Range Section**
- Section label: "Select Date Range"
- Calendar component (plain JS, no library):
  - Month navigator (prev/next chevrons)
  - Day grid (S–M–T–W–T–F–S)
  - Click to select start date, click again to select end date
  - Selected range highlighted with `primary` color
  - Max lookback enforced: 90 days from today (mirrors server `_MAX_SYNC_DAYS`)

**Sync Now Button**
- Primary gradient button (`#4EDEA3` → `#10B981`, 135°)
- On press: disabled + spinner + "Syncing…" label
- Calls `syncGmail(dateFrom, dateTo)`
- On success: renders result summary inline below button:
  - Emails found / Parsed / Pending review / Duplicates skipped
- On error: inline error message with retry

**Reliability Metric** (UI stub)
- Static text: "Syncing reliability 99.8%"
- No backend connection

### Error States

| Error | Display |
|---|---|
| `500` "Gmail auth failed" | "Gmail not connected. Please authenticate." (stub — no OAuth flow) |
| `500` "Gmail search failed" | "Search failed. Tap to retry." with retry button |
| Network offline | "No connection. Check your network." |

---

## 7. CSS Migration

- All styles moved verbatim from `index.html` into `static/css/app.css`
- No style changes during migration
- Design tokens extracted to `:root` variables at top of `app.css`
- New sync screen styles appended as a labeled block: `/* ── SYNC VIEW ── */`
- `index.html` links `app.css` via `<link rel="stylesheet" href="/static/css/app.css">`

---

## 8. Migration Strategy

Run in order to minimize regression risk:

1. **CSS extraction** — move styles to `app.css`, verify visual parity
2. **`api.js`** — collect all `fetch()` calls, verify each function signature matches existing inline calls
3. **`auth.js`** — move PIN screen logic, verify PIN flow still works
4. **`dashboard.js`, `activity.js`, `budget.js`** — migrate existing views, verify functionality
5. **`router.js` + bottom nav** — wire up tab switching, verify all views reachable
6. **`sync.js`** — build new Gmail Import & Sync screen on stable infrastructure
7. **Android asset sync** — copy `static/` contents to `android/app/src/main/assets/`

---

## 9. Out of Scope

- No new FastAPI endpoints
- No Gmail OAuth flow changes
- No changes to `server.py`, `database.py`, `parsers.py`, `auth_gmail.py`
- No automated tests (per project convention for web layer)
- No changes to Android Kotlin code

---

## 10. Verification Checklist

- [ ] PIN auth still works; session persists across tab switches
- [ ] All 4 bottom nav tabs render their respective views
- [ ] Dashboard summary cards and category breakdown load correctly
- [ ] Transaction list: filters, edit, delete, approve all function
- [ ] Budget view: set budgets, suggest budgets, rules all function
- [ ] Sync view: date range selection works, Sync Now calls `POST /api/sync` with correct params
- [ ] Sync result summary renders correctly after sync
- [ ] Error states render for network failure and Gmail errors
- [ ] Android WebView renders correctly (assets updated)
