# Gmail Import & Sync — ES Module Refactor + New Screen

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor the monolithic `static/index.html` into ES modules, add a bottom navigation bar, and implement the Stitch "Gmail Import & Sync" screen as the Sync tab.

**Architecture:** ES modules with one file per concern; a `router.js` drives tab switching and lazy view initialization; all `fetch()` calls live in `api.js`; each view exports `init(container)` and optionally `destroy()`.

**Tech Stack:** Vanilla JS (ES modules, no build step), FastAPI static file serving, Android WebView (Chaquopy).

**Spec:** `docs/superpowers/specs/2026-03-23-gmail-import-sync-design.md`

---

## File Map

| File | Status | Responsibility |
|---|---|---|
| `static/index.html` | Modify | Thin shell: `<head>`, CSS link, empty `#app`, bottom `<nav>`, module script tag |
| `static/css/app.css` | Create | All styles from current `index.html` (verbatim) + new sync view styles |
| `static/js/utils/constants.js` | Create | CATEGORIES, CAT_LABELS, CAT_COLORS, CAT_EMOJI, fmt(), esc(), isoDate(), fmtDate() |
| `static/js/utils/time.js` | Create | timeAgo(), isNoSync() |
| `static/js/api.js` | Create | All fetch() calls as named exports + ApiError class |
| `static/js/auth.js` | Create | PIN screen HTML + numpad logic + submitPin() |
| `static/js/views/dashboard.js` | Create | Period selector, summary cards, category breakdown |
| `static/js/views/activity.js` | Create | Transaction list, filters, add form, quick-add modal, pending review |
| `static/js/views/budget.js` | Create | Budgets, monthly report, category rules (internal sub-tabs) |
| `static/js/views/sync.js` | Create | Gmail Import & Sync screen (Stitch design) |
| `static/js/router.js` | Create | Tab registry, view lifecycle (lazy init), hash routing, bottom nav activation |
| `android/app/src/main/python/static/` | Modify | Mirror of `static/` tree for Android Python server |

---

## Task 1: CSS Extraction

**Files:**
- Create: `static/css/app.css`
- Modify: `static/index.html`

- [ ] **Step 1: Create `static/css/`**

```bash
mkdir -p /Users/fawad/Documents/Projects/FinTracker/static/css
```

- [ ] **Step 2: Copy CSS from index.html → app.css**

Extract everything between `<style>` and `</style>` (lines 11–666 in the current file) into `static/css/app.css`. Then add `--primary` token to `:root` and replace hardcoded `#4EDEA3` values in the PIN section with `var(--primary)`:

At the top of `:root {}` add:
```css
--primary: #4EDEA3;
```

Replace all occurrences of `#4EDEA3` and `rgba(78, 222, 163, 0.4)` in the PIN section with `var(--primary)` and `rgba(78, 222, 163, 0.4)` respectively. (The rgba stays as-is since CSS variables don't work inside rgba() without `color-mix`.)

At the end of app.css, add placeholder section for new sync view styles:
```css
/* ── SYNC VIEW ── */
/* Styles added in Task 8 */
```

- [ ] **Step 3: Replace `<style>` block in index.html with a CSS link**

Replace the `<style>…</style>` block with:
```html
<link rel="stylesheet" href="/static/css/app.css">
```

Keep the `<head>` font links unchanged.

- [ ] **Step 4: Manual verify**

Run `python server.py` and open http://127.0.0.1:8000. PIN screen should look identical. Check that the green `#4EDEA3` on the lock icon and dots still renders. No JS changes yet — the app JS still works inline.

- [ ] **Step 5: Commit**

```bash
git add static/css/app.css static/index.html
git commit -m "refactor: extract CSS to static/css/app.css, add --primary token"
```

---

## Task 2: Shared Utilities — `constants.js` + `time.js`

**Files:**
- Create: `static/js/utils/constants.js`
- Create: `static/js/utils/time.js`

- [ ] **Step 1: Create `static/js/utils/constants.js`**

```js
// static/js/utils/constants.js

export const CATEGORIES = [
  'groceries','fuel','dining','shopping','utilities',
  'transfer','atm','medical','education','other'
];

export const CAT_LABELS = {
  groceries:'Groceries', fuel:'Fuel', dining:'Dining', shopping:'Shopping',
  utilities:'Utilities', transfer:'Transfer', atm:'ATM', medical:'Medical',
  education:'Education', other:'Other',
};

export const CAT_COLORS = {
  groceries:'#22c55e', fuel:'#f97316',    dining:'#a855f7',  shopping:'#3b82f6',
  utilities:'#06b6d4', transfer:'#6366f1', atm:'#84cc16',    medical:'#ef4444',
  education:'#f59e0b', other:'#64748b',
};

export const CAT_EMOJI = {
  groceries:'🛒', fuel:'⛽', dining:'🍽', shopping:'🛍',
  utilities:'🏠', transfer:'💸', atm:'🏧', medical:'💊',
  education:'📚', other:'📦',
};

/** Format number as PKR integer string */
export function fmt(n) {
  return Number(n).toLocaleString('en-PK', {minimumFractionDigits:0, maximumFractionDigits:0});
}

/** Escape HTML special chars */
export function esc(s) {
  return String(s ?? '')
    .replace(/&/g,'&amp;').replace(/</g,'&lt;')
    .replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}

/** Return YYYY-MM-DD string from a Date object */
export function isoDate(d) {
  return d.toISOString().split('T')[0];
}

/** Format ISO date string as "15 Mar 2024" */
export function fmtDate(iso) {
  if (!iso) return '';
  return new Date(iso + 'T00:00:00')
    .toLocaleDateString('en-PK', {day:'numeric', month:'short', year:'numeric'});
}

/** Build category <option> elements, with selectedVal pre-selected */
export function buildCategoryOptions(selectedVal = 'other') {
  return CATEGORIES.map(c =>
    `<option value="${c}" ${c === selectedVal ? 'selected' : ''}>${CAT_LABELS[c]}</option>`
  ).join('');
}
```

- [ ] **Step 2: Create `static/js/utils/time.js`**

```js
// static/js/utils/time.js

/**
 * Convert a SQLite CURRENT_TIMESTAMP string ("YYYY-MM-DD HH:MM:SS", local time)
 * to a human-readable relative string like "2h ago", "just now", "3 days ago".
 */
export function timeAgo(sqliteTimestamp) {
  if (!sqliteTimestamp) return 'Unknown';
  // SQLite returns "YYYY-MM-DD HH:MM:SS" with no timezone — treat as local time
  const normalized = sqliteTimestamp.replace(' ', 'T');
  const past = new Date(normalized);
  if (isNaN(past.getTime())) return 'Unknown';

  const diffMs  = Date.now() - past.getTime();
  const diffSec = Math.floor(diffMs / 1000);
  const diffMin = Math.floor(diffSec / 60);
  const diffHr  = Math.floor(diffMin / 60);
  const diffDay = Math.floor(diffHr  / 24);

  if (diffSec < 60)  return 'just now';
  if (diffMin < 60)  return `${diffMin}m ago`;
  if (diffHr  < 24)  return `${diffHr}h ago`;
  if (diffDay === 1) return 'yesterday';
  if (diffDay < 30)  return `${diffDay} days ago`;
  return past.toLocaleDateString('en-PK', {day:'numeric', month:'short'});
}

/**
 * Returns true if the GET /api/sync/status response is the no-sync sentinel
 * {"message": "No sync yet"} rather than a real sync_log row.
 */
export function isNoSync(statusResponse) {
  return statusResponse && 'message' in statusResponse;
}
```

- [ ] **Step 3: No test runner — manual verify later in Task 8 when sync view uses these**

- [ ] **Step 4: Commit**

```bash
mkdir -p /Users/fawad/Documents/Projects/FinTracker/static/js/utils
git add static/js/utils/constants.js static/js/utils/time.js
git commit -m "feat: add shared utils — constants and timeAgo"
```

---

## Task 3: `api.js` — All API Calls

**Files:**
- Create: `static/js/api.js`

- [ ] **Step 1: Create `static/js/api.js`**

```js
// static/js/api.js
// Single source of truth for all server communication.
// Every fetch() call in the app goes through here.

const BASE = '';

export class ApiError extends Error {
  constructor(status, detail) {
    super(detail);
    this.status = status;
    this.detail = detail;
  }
}

async function request(path, options = {}) {
  let res;
  try {
    res = await fetch(BASE + path, options);
  } catch (e) {
    // Network offline / server not running
    throw new ApiError(0, e.message);
  }
  if (res.ok) return res.json();
  let detail = `HTTP ${res.status}`;
  try { detail = (await res.json()).detail || detail; } catch {}
  throw new ApiError(res.status, detail);
}

function json(method, path, body) {
  return request(path, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
}

// ── Auth ──────────────────────────────────────────────────
export const authenticate     = (pin)          => json('POST', '/api/auth', { pin });

// ── Sync ──────────────────────────────────────────────────
export const syncGmail        = (dateFrom, dateTo) =>
  json('POST', '/api/sync', { date_from: dateFrom, date_to: dateTo });
export const getSyncStatus    = ()             => request('/api/sync/status');

// ── Transactions ──────────────────────────────────────────
export const getTransactions  = (params)       => request(`/api/transactions?${new URLSearchParams(params)}`);
export const createTransaction= (body)         => json('POST',   '/api/transactions',       body);
export const createCashTransaction = (body)    => json('POST',   '/api/transactions/cash',  body);
export const patchTransaction = (id, body)     => json('PATCH',  `/api/transactions/${id}`, body);
export const deleteTransaction= (id)           => request(`/api/transactions/${id}`, { method: 'DELETE' });

// ── Summary ───────────────────────────────────────────────
export const getSummary       = (params)       => request(`/api/summary?${new URLSearchParams(params)}`);
export const getCategorySummary=(params)       => request(`/api/summary/categories?${new URLSearchParams(params)}`);

// ── Categories ────────────────────────────────────────────
export const getCategoryMappings  = ()         => request('/api/categories/mappings');
export const addCategoryMapping   = (body)     => json('POST',   '/api/categories/mappings',           body);
export const deleteCategoryMapping= (merchant) => request(`/api/categories/mappings/${encodeURIComponent(merchant)}`, { method: 'DELETE' });

// ── Budgets ───────────────────────────────────────────────
export const getBudgets       = (month)        => request(`/api/budgets?month=${month}`);
export const upsertBudget     = (body)         => json('PUT',    '/api/budgets',  body);
export const suggestBudgets   = (month)        => json('POST',   '/api/budgets/suggest', { month });

// ── Report ────────────────────────────────────────────────
export const getMonthlyReport = (month)        => request(`/api/report?month=${month}`);
```

- [ ] **Step 2: Commit**

```bash
git add static/js/api.js
git commit -m "feat: add api.js — all server calls as named exports"
```

---

## Task 4: `auth.js` — PIN Screen

**Files:**
- Create: `static/js/auth.js`

- [ ] **Step 1: Create `static/js/auth.js`**

This module renders the PIN screen into the document body, handles all numpad interactions, and calls a provided `onSuccess` callback on successful authentication.

```js
// static/js/auth.js
import { authenticate } from './api.js';

const PIN_LENGTH = 4;

/**
 * Mount the PIN screen. Calls onSuccess() when authentication succeeds.
 * The PIN screen is removed from the DOM on success.
 */
export function init(onSuccess) {
  const screen = document.createElement('div');
  screen.className = 'pin-screen';
  screen.id = 'pinScreen';
  screen.innerHTML = `
    <div class="pin-card">
      <span class="pin-lock-icon material-symbols-outlined">lock</span>
      <h1 class="pin-title">FinTrack <span>PK</span></h1>
      <p class="pin-subtitle">Enter Secure PIN</p>
      <div class="pin-dots">
        <div class="pin-dot" id="dot0"></div>
        <div class="pin-dot" id="dot1"></div>
        <div class="pin-dot" id="dot2"></div>
        <div class="pin-dot" id="dot3"></div>
      </div>
      <div class="numpad">
        ${[1,2,3,4,5,6,7,8,9].map(n => `<button class="numpad-btn" data-digit="${n}">${n}</button>`).join('')}
        <button class="numpad-btn action" id="pinClearBtn">Clear</button>
        <button class="numpad-btn" data-digit="0">0</button>
        <button class="numpad-btn action" id="pinBackBtn">
          <span class="material-symbols-outlined">backspace</span>
        </button>
      </div>
      <button class="pin-fingerprint-btn" id="pinFingerprintBtn">
        <span class="material-symbols-outlined">fingerprint</span>
        Use Fingerprint
      </button>
      <div class="pin-error" id="pinError"></div>
    </div>`;

  document.body.prepend(screen);

  let pinValue = '';

  function updateDots() {
    for (let i = 0; i < PIN_LENGTH; i++) {
      screen.querySelector(`#dot${i}`).classList.toggle('filled', i < pinValue.length);
    }
  }

  function showError(msg) {
    const el = screen.querySelector('#pinError');
    el.textContent = msg;
    el.classList.add('visible');
  }

  function clearError() {
    screen.querySelector('#pinError').classList.remove('visible');
  }

  async function submit() {
    const pin = pinValue;
    try {
      await authenticate(pin);
      screen.remove();
      onSuccess();
    } catch (e) {
      if (e.status === 401) {
        showError('Wrong PIN. Try again.');
      } else {
        showError('Server not running. Start with: python server.py');
      }
      pinValue = '';
      updateDots();
    }
  }

  screen.querySelectorAll('[data-digit]').forEach(btn => {
    btn.addEventListener('click', () => {
      if (pinValue.length >= PIN_LENGTH) return;
      clearError();
      pinValue += btn.dataset.digit;
      updateDots();
      if (pinValue.length === PIN_LENGTH) submit();
    });
  });

  screen.querySelector('#pinClearBtn').addEventListener('click', () => {
    pinValue = ''; updateDots(); clearError();
  });

  screen.querySelector('#pinBackBtn').addEventListener('click', () => {
    if (!pinValue.length) return;
    pinValue = pinValue.slice(0, -1);
    updateDots(); clearError();
  });

  screen.querySelector('#pinFingerprintBtn').addEventListener('click', () => {
    showError('Biometric authentication is not supported on this platform.');
  });
}
```

- [ ] **Step 2: Commit**

```bash
git add static/js/auth.js
git commit -m "feat: add auth.js — PIN screen as isolated ES module"
```

---

## Task 5: `views/dashboard.js` — Summary + Category Breakdown

**Files:**
- Create: `static/js/views/dashboard.js`

- [ ] **Step 1: Create `static/js/views/dashboard.js`**

```js
// static/js/views/dashboard.js
import { getSummary, getCategorySummary } from '../api.js';
import { fmt, esc, CAT_COLORS, CAT_LABELS } from '../utils/constants.js';

let currentPeriod = 'month';
let container;

export function init(el) {
  container = el;
  container.innerHTML = `
    <div class="period-selector">
      <button class="period-btn active" data-period="month">This Month</button>
      <button class="period-btn" data-period="30d">Last 30 Days</button>
      <button class="period-btn" data-period="3m">Last 3 Months</button>
      <button class="period-btn" data-period="all">All Time</button>
    </div>
    <div class="summary">
      <div class="card">
        <div class="card-label">Income</div>
        <div class="card-value income" id="sumIncome">—</div>
        <div class="card-sub" id="sumPeriodLabel"></div>
      </div>
      <div class="card">
        <div class="card-label">Expenses</div>
        <div class="card-value expense" id="sumExpenses">—</div>
      </div>
      <div class="card">
        <div class="card-label">Net Balance</div>
        <div class="card-value net" id="sumNet">—</div>
      </div>
    </div>
    <div class="cat-breakdown" id="catBreakdown">
      <div class="cat-breakdown-header">
        <span class="cat-breakdown-title">Spending by Category</span>
        <span class="cat-breakdown-total" id="catTotal"></span>
      </div>
      <div id="catBars"></div>
    </div>`;

  container.querySelectorAll('.period-btn').forEach(btn => {
    btn.addEventListener('click', () => setPeriod(btn.dataset.period, btn));
  });

  loadAll();
}

export function destroy() {
  // No cleanup needed
}

function setPeriod(period, btn) {
  currentPeriod = period;
  container.querySelectorAll('.period-btn').forEach(b => b.classList.remove('active'));
  btn.classList.add('active');
  loadAll();
}

function buildParams() {
  return { period: currentPeriod };
}

async function loadAll() {
  await Promise.all([loadSummary(), loadCategorySummary()]);
}

async function loadSummary() {
  try {
    const d = await getSummary(buildParams());
    container.querySelector('#sumIncome').textContent   = '₨ ' + fmt(d.income);
    container.querySelector('#sumExpenses').textContent = '₨ ' + fmt(d.expenses);
    const netEl = container.querySelector('#sumNet');
    netEl.textContent = (d.net >= 0 ? '+' : '−') + '₨ ' + fmt(Math.abs(d.net));
    netEl.className   = 'card-value net ' + (d.net >= 0 ? 'positive' : 'negative');
    const labels = { month:'This month', '30d':'Last 30 days', '3m':'Last 3 months', all:'All time' };
    container.querySelector('#sumPeriodLabel').textContent = labels[currentPeriod] || '';
  } catch {}
}

async function loadCategorySummary() {
  try {
    const data = await getCategorySummary(buildParams());
    renderCategoryBars(data);
  } catch {}
}

function renderCategoryBars(data) {
  const box = container.querySelector('#catBreakdown');
  if (!data.length) { box.classList.remove('visible'); return; }
  const grand = data.reduce((s, r) => s + r.total, 0);
  container.querySelector('#catTotal').textContent = 'Total spent ₨ ' + fmt(grand);
  container.querySelector('#catBars').innerHTML = data.map(r => {
    const color = CAT_COLORS[r.category] || CAT_COLORS.other;
    const label = CAT_LABELS[r.category] || r.category;
    return `<div class="cat-bar-row">
      <span class="cat-bar-label">${esc(label)}</span>
      <div class="cat-bar-track"><div class="cat-bar-fill" style="width:${r.percentage}%;background:${color}"></div></div>
      <div class="cat-bar-meta">
        <span class="cat-bar-amount">₨ ${fmt(r.total)}</span>
        <span class="cat-bar-pct">${r.percentage}%</span>
      </div>
    </div>`;
  }).join('');
  box.classList.add('visible');
}
```

- [ ] **Step 2: Commit**

```bash
git add static/js/views/dashboard.js
git commit -m "feat: add dashboard.js view — period selector, summary cards, category bars"
```

---

## Task 6: `views/activity.js` — Transaction List + Filters

**Files:**
- Create: `static/js/views/activity.js`

- [ ] **Step 1: Create `static/js/views/activity.js`**

This is the largest view. It contains the full transaction list, filters, add form, quick-add (FAB + bottom sheet), and pending review. All state is module-level so it survives tab switches.

```js
// static/js/views/activity.js
import {
  getTransactions, createTransaction, createCashTransaction,
  patchTransaction, deleteTransaction
} from '../api.js';
import {
  CATEGORIES, CAT_LABELS, CAT_EMOJI,
  fmt, esc, isoDate, buildCategoryOptions
} from '../utils/constants.js';

// ── Module-level state (survives tab switches) ────────────
let currentTab    = 'all';   // 'all' | 'pending'
let currentPage   = 1;
const PAGE_SIZE   = 25;
let filterSearch    = '';
let filterBank      = '';
let filterCategory  = '';
let filterAmountMin = '';
let filterAmountMax = '';
let filterDateFrom  = '';
let filterDateTo    = '';
let filterDebounce  = null;
let qaCategory      = 'other';
let container;
let initialized     = false;

// ── Lifecycle ─────────────────────────────────────────────

export function init(el) {
  container = el;
  if (initialized) { loadTransactions(); return; }
  initialized = true;
  _render();
  _bindEvents();
  loadTransactions();
}

export function destroy() {
  clearTimeout(filterDebounce);
}

function _render() {
  container.innerHTML = `
    <!-- Add Transaction Form -->
    <div class="add-form" id="addForm">
      <h3>+ Add Transaction</h3>
      <div class="form-row">
        <div class="field"><label>Merchant / Description</label><input type="text" id="newMerchant" placeholder="e.g. Imtiaz Super Market"></div>
        <div class="field"><label>Amount (PKR)</label><input type="number" id="newAmount" placeholder="0.00" min="0" step="0.01"></div>
        <div class="field"><label>Type</label>
          <select id="newType"><option value="debit">Debit (expense)</option><option value="credit">Credit (income)</option></select>
        </div>
      </div>
      <div class="form-row">
        <div class="field"><label>Bank</label>
          <select id="newBank"><option value="SCB">SCB</option><option value="Meezan">Meezan</option><option value="HBL">HBL</option><option value="Other">Other</option></select>
        </div>
        <div class="field"><label>Category</label><select id="newCategory">${buildCategoryOptions()}</select></div>
        <div class="field"><label>Date</label><input type="date" id="newDate" value="${isoDate(new Date())}"></div>
      </div>
      <div class="form-actions">
        <button class="btn" id="addFormCancelBtn">Cancel</button>
        <button class="btn btn-primary" id="addFormSaveBtn">Save Transaction</button>
      </div>
    </div>

    <!-- Internal tab bar (All / Pending) -->
    <div class="tabs">
      <div class="tab active" data-subtab="all">All Transactions</div>
      <div class="tab" data-subtab="pending">Pending Review <span class="badge" id="pendingBadge" style="display:none">0</span></div>
    </div>

    <!-- Mobile filter toggle -->
    <button class="filter-toggle" id="filterToggle">
      <span>Filters &amp; Search</span><span class="ft-arrow">▼</span>
    </button>

    <!-- Filter panel -->
    <div class="filter-panel" id="filterPanel">
      <div class="filter-search-row">
        <div class="filter-search-wrap">
          <span class="search-icon">🔍</span>
          <input type="text" class="filter-search" id="searchInput" placeholder="Search merchants, categories, amounts…">
        </div>
        <button class="filter-clear" id="clearFiltersBtn">Clear filters</button>
      </div>
      <div class="filter-row">
        <select class="filter-select" id="fBank">
          <option value="">All Banks</option>
          <option value="SCB">SCB</option><option value="Meezan">Meezan</option>
          <option value="Cash">Cash</option><option value="HBL">HBL</option>
        </select>
        <select class="filter-select" id="fCategory">
          <option value="">All Categories</option>
          ${CATEGORIES.map(c => `<option value="${c}">${CAT_LABELS[c]}</option>`).join('')}
        </select>
        <span class="filter-label">Amount:</span>
        <input type="number" class="filter-input" id="fAmountMin" placeholder="Min ₨">
        <span class="filter-label">–</span>
        <input type="number" class="filter-input" id="fAmountMax" placeholder="Max ₨">
        <span class="filter-divider">|</span>
        <span class="filter-label">Date:</span>
        <input type="date" class="filter-input filter-date" id="fDateFrom">
        <span class="filter-label">→</span>
        <input type="date" class="filter-input filter-date" id="fDateTo">
      </div>
    </div>

    <div class="tx-count" id="txCount"></div>
    <div id="txContainer">
      <div class="empty"><h3>No transactions yet</h3><p>Sync Gmail to pull bank alerts, or use + to log a cash expense.</p></div>
    </div>

    <!-- FAB + Quick-Add -->
    <button class="fab" id="fab" title="Log cash expense">+</button>
    <div class="qa-backdrop" id="qaBackdrop"></div>
    <div class="quick-add" id="quickAdd">
      <div class="qa-header">
        <span class="qa-title">💵 Cash Expense</span>
        <button class="qa-close" id="qaCloseBtn">✕</button>
      </div>
      <div class="qa-amount-row">
        <span class="qa-prefix">₨</span>
        <input type="number" id="qaAmount" class="qa-amount-input" placeholder="0" min="0" inputmode="decimal">
      </div>
      <div class="qa-presets">
        ${[100,500,1000,5000].map(v => `<button class="qa-preset-btn" data-amount="${v}">₨ ${fmt(v)}</button>`).join('')}
      </div>
      <div class="qa-cats">
        ${CATEGORIES.map(c => `
          <button class="qa-cat-btn${c==='other'?' active':''}" data-cat="${c}">
            <span class="qa-cat-icon">${CAT_EMOJI[c]}</span>${CAT_LABELS[c]}
          </button>`).join('')}
      </div>
      <div class="qa-footer">
        <input type="text" id="qaNote" class="qa-note" placeholder="Note (e.g. chai, rickshaw)">
        <div class="qa-date-row">
          <input type="date" id="qaDate" class="qa-date-input">
          <button class="qa-yesterday" id="qaYesterdayBtn">Yesterday</button>
        </div>
        <button class="btn btn-primary" id="qaSaveBtn">Save</button>
      </div>
    </div>`;
}

function _bindEvents() {
  // Sub-tabs
  container.querySelectorAll('[data-subtab]').forEach(tab => {
    tab.addEventListener('click', () => _switchSubTab(tab.dataset.subtab, tab));
  });

  // Add form
  container.querySelector('#addFormCancelBtn').addEventListener('click', _toggleAddForm);
  container.querySelector('#addFormSaveBtn').addEventListener('click', _saveNewTransaction);

  // Expose toggleAddForm globally for header button (set in router.js)
  window._activityToggleAddForm = _toggleAddForm;

  // Mobile filter toggle
  container.querySelector('#filterToggle').addEventListener('click', () => {
    const panel = container.querySelector('#filterPanel');
    const toggle = container.querySelector('#filterToggle');
    panel.classList.toggle('mobile-open');
    toggle.classList.toggle('open');
  });

  // Filters
  container.querySelector('#searchInput').addEventListener('input', () => {
    filterSearch = container.querySelector('#searchInput').value.trim();
    clearTimeout(filterDebounce);
    filterDebounce = setTimeout(() => { currentPage = 1; loadTransactions(); }, 300);
  });
  container.querySelector('#fBank').addEventListener('change', _applyFilters);
  container.querySelector('#fCategory').addEventListener('change', _applyFilters);
  container.querySelector('#fAmountMin').addEventListener('input', _scheduleFilter);
  container.querySelector('#fAmountMax').addEventListener('input', _scheduleFilter);
  container.querySelector('#fDateFrom').addEventListener('change', _applyFilters);
  container.querySelector('#fDateTo').addEventListener('change', _applyFilters);
  container.querySelector('#clearFiltersBtn').addEventListener('click', _clearFilters);

  // FAB + Quick-add
  container.querySelector('#fab').addEventListener('click', _openQuickAdd);
  container.querySelector('#qaBackdrop').addEventListener('click', _closeQuickAdd);
  container.querySelector('#qaCloseBtn').addEventListener('click', _closeQuickAdd);
  container.querySelector('#qaYesterdayBtn').addEventListener('click', () => {
    container.querySelector('#qaDate').value = isoDate(new Date(Date.now() - 864e5));
  });
  container.querySelectorAll('.qa-preset-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      container.querySelector('#qaAmount').value = btn.dataset.amount;
      container.querySelector('#qaAmount').focus();
    });
  });
  container.querySelectorAll('.qa-cat-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      container.querySelectorAll('.qa-cat-btn').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      qaCategory = btn.dataset.cat;
    });
  });
  container.querySelector('#qaSaveBtn').addEventListener('click', _saveCash);
  container.querySelector('#qaAmount').addEventListener('keydown', e => {
    if (e.key === 'Enter') _saveCash();
  });
  document.addEventListener('keydown', e => {
    if (e.key === 'Escape') _closeQuickAdd();
  });
}

// ── Sub-tab switching ─────────────────────────────────────

function _switchSubTab(tab, el) {
  currentTab = tab; currentPage = 1;
  container.querySelectorAll('[data-subtab]').forEach(t => t.classList.remove('active'));
  el.classList.add('active');
  loadTransactions();
}

// ── Filter helpers ────────────────────────────────────────

function _applyFilters() {
  filterBank      = container.querySelector('#fBank').value;
  filterCategory  = container.querySelector('#fCategory').value;
  filterAmountMin = container.querySelector('#fAmountMin').value;
  filterAmountMax = container.querySelector('#fAmountMax').value;
  filterDateFrom  = container.querySelector('#fDateFrom').value;
  filterDateTo    = container.querySelector('#fDateTo').value;
  currentPage = 1; loadTransactions();
}

function _scheduleFilter() {
  clearTimeout(filterDebounce);
  filterDebounce = setTimeout(() => {
    filterAmountMin = container.querySelector('#fAmountMin').value;
    filterAmountMax = container.querySelector('#fAmountMax').value;
    currentPage = 1; loadTransactions();
  }, 400);
}

function _clearFilters() {
  filterSearch = filterBank = filterCategory = filterAmountMin = filterAmountMax = '';
  filterDateFrom = filterDateTo = '';
  ['searchInput','fBank','fCategory','fAmountMin','fAmountMax','fDateFrom','fDateTo']
    .forEach(id => { container.querySelector('#' + id).value = ''; });
  currentPage = 1; loadTransactions();
}

function _hasActiveFilters() {
  return !!(filterSearch || filterBank || filterCategory ||
            filterAmountMin || filterAmountMax || filterDateFrom || filterDateTo);
}

function _buildParams(extra = {}) {
  const p = { ...extra };
  if (filterDateFrom || filterDateTo) {
    if (filterDateFrom) p.date_from = filterDateFrom;
    if (filterDateTo)   p.date_to   = filterDateTo;
  } else {
    p.period = 'month'; // default period for activity view
  }
  if (filterBank)       p.bank        = filterBank;
  if (filterCategory)   p.category    = filterCategory;
  if (filterAmountMin)  p.amount_min  = filterAmountMin;
  if (filterAmountMax)  p.amount_max  = filterAmountMax;
  if (filterSearch)     p.search      = filterSearch;
  return p;
}

// ── Load & render transactions ────────────────────────────

async function loadTransactions() {
  const extra = { page: currentPage, page_size: PAGE_SIZE };
  if (currentTab === 'pending') extra.status = 'pending';
  const params = currentTab === 'all' ? _buildParams(extra) : extra;

  try {
    const data = await getTransactions(params);
    _renderTransactions(data.items, data.total, data.page, data.pages);

    // Update pending badge
    const pData = await getTransactions({ status: 'pending', page: 1, page_size: 1 });
    const badge = container.querySelector('#pendingBadge');
    badge.style.display = pData.total > 0 ? 'inline' : 'none';
    if (pData.total > 0) badge.textContent = pData.total;
  } catch (e) { console.error(e); }
}

function _renderTransactions(txs, total, page, pages) {
  const countEl = container.querySelector('#txCount');
  const txContainer = container.querySelector('#txContainer');
  const filtered = _hasActiveFilters() && currentTab === 'all';
  countEl.innerHTML = total > 0
    ? `${fmt(total)} transaction${total !== 1 ? 's' : ''}${filtered ? ' <span class="filtered-tag">· filtered</span>' : ''}`
    : '';

  if (!txs.length) {
    const msg = currentTab === 'pending'
      ? 'No pending transactions — all parsed correctly!'
      : _hasActiveFilters()
        ? 'No transactions match your search. Try clearing filters.'
        : 'No transactions in this period.';
    txContainer.innerHTML = `<div class="empty"><h3>${currentTab === 'pending' ? '✓ All clear' : 'No results'}</h3><p>${msg}</p></div>`;
    return;
  }

  const rows = txs.map(tx => {
    const isDebit = tx.tx_type === 'debit';
    const source  = tx.source || 'gmail';
    const bank    = tx.bank || '';
    const bankCls = bank === 'SCB' ? 'scb' : bank === 'Meezan' ? 'meezan' : bank === 'Cash' ? 'cash' : 'other';
    const date    = tx.tx_date
      ? new Date(tx.tx_date).toLocaleDateString('en-PK', {day:'numeric', month:'short', year:'2-digit'}) : '';
    const isPending = tx.status === 'pending';
    const catSel  = `<select class="cat-select" data-txid="${tx.id}">${buildCategoryOptions(tx.category || 'other')}</select>`;
    return `
      <div class="tx-item" id="txrow-${tx.id}">
        <div class="tx-main">
          <div class="tx-icon ${isDebit ? 'debit':'credit'}">${isDebit ? '↗':'↙'}</div>
          <div class="tx-info">
            <div class="tx-name">${esc(tx.merchant || 'Unknown')}</div>
            <div class="tx-meta">
              <span class="tx-bank ${bankCls}">${esc(bank)}</span>
              <span class="tx-source ${source}">${source}</span>
              ${date ? date + ' ·' : ''}
              ${catSel}
              <span class="tx-conf ${tx.confidence}">${tx.confidence}</span>
            </div>
          </div>
          <div class="tx-amount ${isDebit ? 'debit':'credit'}">${isDebit ? '-' : '+'}₨&nbsp;${Number(tx.amount).toLocaleString()}</div>
          <div class="tx-actions">
            ${isPending ? `<button class="approve" data-approveid="${tx.id}">✓</button>` : ''}
            <button class="edit-btn" data-editid="${tx.id}">Edit</button>
            <button class="del-btn"  data-delid="${tx.id}">✕</button>
            ${tx.raw_text ? `<button class="raw-btn" data-rawid="${tx.id}">Raw</button>` : ''}
          </div>
        </div>
        <div class="tx-edit" id="edit-${tx.id}">
          <div class="edit-row">
            <div><label style="font-size:10px;color:var(--dim);display:block;margin-bottom:3px">Merchant</label><input id="em-${tx.id}" value="${esc(tx.merchant || '')}"></div>
            <div><label style="font-size:10px;color:var(--dim);display:block;margin-bottom:3px">Amount</label><input type="number" id="ea-${tx.id}" value="${tx.amount}"></div>
            <div><label style="font-size:10px;color:var(--dim);display:block;margin-bottom:3px">Category</label><select id="ec-${tx.id}">${buildCategoryOptions(tx.category || 'other')}</select></div>
            <div><label style="font-size:10px;color:var(--dim);display:block;margin-bottom:3px">Date</label><input type="date" id="ed-${tx.id}" value="${(tx.tx_date || '').slice(0,10)}"></div>
          </div>
          <div class="edit-actions">
            <button class="btn" data-canceleditid="${tx.id}">Cancel</button>
            <button class="btn btn-primary" data-saveeditid="${tx.id}">Save</button>
          </div>
        </div>
        <div class="tx-raw" id="raw-${tx.id}">${esc(tx.raw_text || '')}</div>
      </div>`;
  }).join('');

  txContainer.innerHTML = `<div class="tx-list">${rows}</div>
    <div class="pagination">
      <span class="pagination-info">Page ${page} of ${pages}</span>
      <div class="pagination-controls">
        <button class="btn" data-page="${page-1}" ${page<=1?'disabled':''}>← Prev</button>
        <button class="btn" data-page="${page+1}" ${page>=pages?'disabled':''}>Next →</button>
      </div>
    </div>`;

  // Event delegation for dynamic rows
  txContainer.querySelectorAll('.cat-select').forEach(sel => {
    sel.addEventListener('change', () => _changeCategory(sel.dataset.txid, sel));
  });
  txContainer.querySelectorAll('[data-approveid]').forEach(btn => {
    btn.addEventListener('click', () => _approveTx(btn.dataset.approveid));
  });
  txContainer.querySelectorAll('[data-editid]').forEach(btn => {
    btn.addEventListener('click', () => _toggleEdit(btn.dataset.editid));
  });
  txContainer.querySelectorAll('[data-delid]').forEach(btn => {
    btn.addEventListener('click', () => _deleteTx(btn.dataset.delid));
  });
  txContainer.querySelectorAll('[data-rawid]').forEach(btn => {
    btn.addEventListener('click', () => container.querySelector(`#raw-${btn.dataset.rawid}`)?.classList.toggle('visible'));
  });
  txContainer.querySelectorAll('[data-canceleditid]').forEach(btn => {
    btn.addEventListener('click', () => _toggleEdit(btn.dataset.canceleditid));
  });
  txContainer.querySelectorAll('[data-saveeditid]').forEach(btn => {
    btn.addEventListener('click', () => _saveEdit(btn.dataset.saveeditid));
  });
  txContainer.querySelectorAll('[data-page]').forEach(btn => {
    btn.addEventListener('click', () => {
      currentPage = parseInt(btn.dataset.page);
      loadTransactions();
      window.scrollTo(0,0);
    });
  });
}

// ── Transaction actions ───────────────────────────────────

async function _changeCategory(id, sel) {
  await patchTransaction(id, { category: sel.value });
  const row = container.querySelector(`#txrow-${id}`);
  row.classList.remove('cat-saved'); void row.offsetWidth; row.classList.add('cat-saved');
}

function _toggleEdit(id) {
  container.querySelector(`#edit-${id}`)?.classList.toggle('visible');
}

async function _saveEdit(id) {
  const merchant = container.querySelector(`#em-${id}`).value.trim();
  const amount   = parseFloat(container.querySelector(`#ea-${id}`).value);
  const category = container.querySelector(`#ec-${id}`).value;
  const dateVal  = container.querySelector(`#ed-${id}`).value;
  const updates  = { merchant, amount, category };
  if (dateVal) updates.tx_date = new Date(dateVal + 'T00:00:00').toISOString();
  await patchTransaction(id, updates);
  currentPage = 1; loadTransactions();
}

async function _deleteTx(id) {
  if (!confirm('Delete this transaction? This cannot be undone.')) return;
  await deleteTransaction(id);
  currentPage = 1; loadTransactions();
}

async function _approveTx(id) {
  await patchTransaction(id, { status: 'confirmed', confidence: 'high' });
  loadTransactions();
}

// ── Add transaction form ──────────────────────────────────

function _toggleAddForm() {
  container.querySelector('#addForm').classList.toggle('visible');
}

async function _saveNewTransaction() {
  const merchant = container.querySelector('#newMerchant').value.trim();
  const amount   = parseFloat(container.querySelector('#newAmount').value);
  const tx_type  = container.querySelector('#newType').value;
  const bank     = container.querySelector('#newBank').value;
  const category = container.querySelector('#newCategory').value;
  const dateVal  = container.querySelector('#newDate').value;
  if (!merchant || !amount || isNaN(amount)) { alert('Enter merchant and valid amount.'); return; }
  await createTransaction({
    amount, tx_type, merchant, bank, category,
    tx_date: dateVal ? new Date(dateVal + 'T00:00:00').toISOString() : new Date().toISOString(),
  });
  container.querySelector('#addForm').classList.remove('visible');
  container.querySelector('#newMerchant').value = '';
  container.querySelector('#newAmount').value   = '';
  container.querySelector('#newDate').value     = isoDate(new Date());
  currentPage = 1; loadTransactions();
}

// ── Quick-add (cash) ──────────────────────────────────────

function _openQuickAdd() {
  container.querySelector('#qaDate').value   = isoDate(new Date());
  container.querySelector('#qaAmount').value = '';
  container.querySelector('#qaNote').value   = '';
  qaCategory = 'other';
  container.querySelectorAll('.qa-cat-btn').forEach(b => b.classList.remove('active'));
  container.querySelector('.qa-cat-btn[data-cat="other"]').classList.add('active');
  container.querySelector('#quickAdd').classList.add('open');
  container.querySelector('#qaBackdrop').classList.add('open');
  setTimeout(() => container.querySelector('#qaAmount').focus(), 320);
}

function _closeQuickAdd() {
  container.querySelector('#quickAdd').classList.remove('open');
  container.querySelector('#qaBackdrop').classList.remove('open');
}

async function _saveCash() {
  const amount = parseFloat(container.querySelector('#qaAmount').value);
  if (!amount || amount <= 0) {
    const inp = container.querySelector('#qaAmount');
    inp.style.borderColor = 'var(--red)';
    setTimeout(() => { inp.style.borderColor = ''; }, 900);
    inp.focus(); return;
  }
  await createCashTransaction({
    amount, category: qaCategory,
    note: container.querySelector('#qaNote').value.trim(),
    date: container.querySelector('#qaDate').value,
  });
  container.querySelector('#qaAmount').value = '';
  container.querySelector('#qaNote').value   = '';
  container.querySelector('#qaAmount').focus();
  loadTransactions();
}
```

- [ ] **Step 2: Commit**

```bash
git add static/js/views/activity.js
git commit -m "feat: add activity.js view — transaction list, filters, add form, quick-add"
```

---

## Task 7: `views/budget.js` — Budgets, Report, Rules

**Files:**
- Create: `static/js/views/budget.js`

- [ ] **Step 1: Create `static/js/views/budget.js`**

This view has three internal sub-tabs: Budget, Report, Category Rules. State is module-level.

```js
// static/js/views/budget.js
import {
  getBudgets, upsertBudget, suggestBudgets,
  getMonthlyReport, getCategoryMappings, addCategoryMapping, deleteCategoryMapping
} from '../api.js';
import { CATEGORIES, CAT_LABELS, CAT_EMOJI, fmt, esc, buildCategoryOptions } from '../utils/constants.js';

let currentBudgetMonth = new Date().toISOString().slice(0, 7);
let currentReportMonth = new Date().toISOString().slice(0, 7);
let _budgetSaving      = false;
let currentSubTab      = 'budget'; // 'budget' | 'report' | 'rules'
let container;
let initialized        = false;

export function init(el) {
  container = el;
  if (initialized) { _loadCurrentSubTab(); return; }
  initialized = true;
  container.innerHTML = `
    <div class="tabs">
      <div class="tab active" data-subtab="budget">Budget</div>
      <div class="tab" data-subtab="report">Report</div>
      <div class="tab" data-subtab="rules">Category Rules</div>
    </div>
    <div id="budgetContent"></div>`;

  container.querySelectorAll('[data-subtab]').forEach(tab => {
    tab.addEventListener('click', () => {
      currentSubTab = tab.dataset.subtab;
      container.querySelectorAll('[data-subtab]').forEach(t => t.classList.remove('active'));
      tab.classList.add('active');
      _loadCurrentSubTab();
    });
  });

  _loadCurrentSubTab();
}

export function destroy() {}

function _loadCurrentSubTab() {
  if (currentSubTab === 'budget') loadBudget();
  else if (currentSubTab === 'report') loadReport();
  else loadRules();
}

// ── Budget ────────────────────────────────────────────────

async function loadBudget() {
  const content = container.querySelector('#budgetContent');
  content.innerHTML = '<div class="empty"><p style="color:var(--muted)">Loading…</p></div>';
  try {
    renderBudget(await getBudgets(currentBudgetMonth));
  } catch {
    content.innerHTML = '<div class="empty"><h3>Error</h3><p>Could not load budget.</p></div>';
  }
}

function budgetColor(pct) {
  if (pct === null) return 'var(--muted)';
  if (pct >= 100)   return 'var(--red)';
  if (pct >= 70)    return 'var(--amber)';
  return 'var(--green)';
}

function renderBudget(data) {
  const { month, days_in_month, days_elapsed, categories } = data;
  const showProj = days_elapsed >= 7 && days_elapsed < days_in_month;
  let totalBudget = 0, totalActual = 0;
  categories.forEach(c => { totalBudget += c.budget; totalActual += c.actual; });
  const totalRem  = totalBudget - totalActual;
  const totalPct  = totalBudget > 0 ? Math.round(totalActual / totalBudget * 100) : null;
  const budgeted   = categories.filter(c => c.budget > 0);
  const unbudgeted = categories.filter(c => c.budget === 0 && c.actual > 0);

  function bRow(c, isUnb = false) {
    const displayPct = c.budget > 0 ? Math.round(c.actual / c.budget * 100) : null;
    const barPct     = c.budget > 0 ? Math.min(displayPct, 100) : 0;
    const color      = budgetColor(displayPct);
    const proj       = (showProj && c.actual > 0) ? Math.round(c.actual / days_elapsed * days_in_month) : null;
    const emoji      = CAT_EMOJI[c.category] || '📦';
    const label      = CAT_LABELS[c.category] || c.category;
    const budgetEdit = isUnb
      ? `<span class="budget-amount-val" data-editbudget="${c.category}" data-val="0" style="color:var(--muted);border-bottom-color:var(--border)">Set budget</span>
         <input class="budget-input" id="bi-${c.category}" type="number" value="0" style="display:none">`
      : `<span class="budget-currency">₨</span>
         <span class="budget-amount-val" data-editbudget="${c.category}" data-val="${c.budget}">${fmt(c.budget)}</span>
         <input class="budget-input" id="bi-${c.category}" type="number" value="${c.budget}" style="display:none">`;
    return `
      <div class="budget-row${isUnb ? ' unbudgeted' : ''}">
        <div class="budget-cat"><span class="budget-emoji">${emoji}</span><span class="budget-label">${esc(label)}</span></div>
        <div class="budget-nums">
          ${!isUnb ? `<div class="budget-field"><div class="bf-label">Budget</div><div class="budget-amount-wrap">${budgetEdit}</div></div>` : ''}
          <div class="budget-field"><div class="bf-label">Spent</div><div class="budget-spent">₨ ${fmt(c.actual)}</div></div>
          ${!isUnb
            ? `<div class="budget-field"><div class="bf-label">Remaining</div><div class="budget-remaining" style="color:${c.remaining>=0?'var(--green)':'var(--red)'}">${c.remaining>=0?'':'−'}₨ ${fmt(Math.abs(c.remaining))}</div></div>`
            : `<div class="budget-field"><div class="bf-label">Set Budget</div><div class="budget-amount-wrap">${budgetEdit}</div></div>`}
        </div>
        ${!isUnb ? `<div class="budget-bar-row"><div class="budget-bar-track"><div class="budget-bar-fill" style="width:${barPct}%;background:${color}"></div></div><span class="budget-bar-pct" style="color:${color}">${displayPct!==null?displayPct+'%':'—'}</span></div>` : ''}
        ${proj !== null ? `<div class="budget-projection">At this rate: <strong>₨ ${fmt(proj)}</strong> by month end</div>` : ''}
      </div>`;
  }

  const overallPct = totalPct !== null ? Math.min(totalPct, 100) : 0;
  container.querySelector('#budgetContent').innerHTML = `
    <div class="budget-section">
      <div class="budget-header">
        <div><div class="budget-title">Budget — ${_fmtMonthLabel(month)}</div><div class="budget-subtitle">Day ${days_elapsed} of ${days_in_month}</div></div>
        <button class="btn" id="suggestBudgetBtn">💡 Suggest from last month</button>
      </div>
      <div class="budget-overall">
        <div class="budget-overall-nums">
          <div><span class="on-label">Total Budget</span><strong>₨ ${fmt(totalBudget)}</strong></div>
          <div><span class="on-label">Spent</span><strong style="color:var(--red)">₨ ${fmt(totalActual)}</strong></div>
          <div><span class="on-label">Remaining</span><strong style="color:${totalRem>=0?'var(--green)':'var(--red)'}">${totalRem>=0?'':'−'}₨ ${fmt(Math.abs(totalRem))}</strong></div>
        </div>
        <div class="budget-bar-row">
          <div class="budget-bar-track" style="height:10px"><div class="budget-bar-fill" style="width:${overallPct}%;background:${budgetColor(totalPct)}"></div></div>
          <span class="budget-bar-pct" style="color:${budgetColor(totalPct)}">${totalPct!==null?totalPct+'%':'—'}</span>
        </div>
      </div>
      ${budgeted.map(c => bRow(c)).join('')}
      ${unbudgeted.length ? `<div class="budget-unbudgeted-hdr" id="unbudgetedHdr">▶ Unbudgeted categories (${unbudgeted.length})</div><div id="unbudgetedList" style="display:none">${unbudgeted.map(c => bRow(c, true)).join('')}</div>` : ''}
      ${!budgeted.length && !unbudgeted.length ? '<div class="budget-empty">No spending this month.</div>' : ''}
    </div>`;

  // Bind budget editing
  container.querySelectorAll('[data-editbudget]').forEach(span => {
    span.addEventListener('click', () => {
      const cat = span.dataset.editbudget;
      const inp = container.querySelector(`#bi-${cat}`);
      span.style.display = 'none'; inp.style.display = '';
      inp.focus(); inp.select();
    });
  });
  container.querySelectorAll('.budget-input').forEach(inp => {
    const cat = inp.id.replace('bi-', '');
    inp.addEventListener('blur', () => _saveBudgetInline(cat, inp));
    inp.addEventListener('keydown', e => {
      if (e.key === 'Enter') { _budgetSaving = false; _saveBudgetInline(cat, inp); }
      if (e.key === 'Escape') { inp.style.display = 'none'; container.querySelector(`[data-editbudget="${cat}"]`).style.display = ''; }
    });
  });
  container.querySelector('#suggestBudgetBtn')?.addEventListener('click', _suggestBudgets);
  container.querySelector('#unbudgetedHdr')?.addEventListener('click', () => {
    const list = container.querySelector('#unbudgetedList');
    const hdr  = container.querySelector('#unbudgetedHdr');
    const open = list.style.display === 'block';
    list.style.display = open ? 'none' : 'block';
    hdr.textContent    = (open ? '▶' : '▼') + hdr.textContent.slice(1);
  });
}

async function _saveBudgetInline(cat, inp) {
  if (_budgetSaving) return; _budgetSaving = true;
  inp.style.display = 'none';
  const span = container.querySelector(`[data-editbudget="${cat}"]`);
  if (span) span.style.display = '';
  try {
    await upsertBudget({ category: cat, amount: parseFloat(inp.value) || 0, month: currentBudgetMonth });
    loadBudget();
  } catch {}
  _budgetSaving = false;
}

async function _suggestBudgets() {
  try {
    await suggestBudgets(currentBudgetMonth);
    loadBudget();
  } catch {}
}

function _fmtMonthLabel(ym) {
  const [y, m] = ym.split('-');
  return new Date(parseInt(y), parseInt(m)-1, 1).toLocaleDateString('en-PK', {month:'long', year:'numeric'});
}

// ── Report ────────────────────────────────────────────────

async function loadReport() {
  const content = container.querySelector('#budgetContent');
  content.innerHTML = '<div class="empty"><p style="color:var(--muted)">Loading…</p></div>';
  try {
    const d = await getMonthlyReport(currentReportMonth);
    _renderReport(d);
  } catch {
    content.innerHTML = '<div class="empty"><h3>Error</h3><p>Could not load report.</p></div>';
  }
}

function _renderReport(d) {
  // Full report render logic migrated from original index.html
  // Month navigation, summary cards, top merchants table, vs-last-month grid
  const prev = _prevMonth(currentReportMonth);
  const next = _nextMonth(currentReportMonth);
  const isCurrentMonth = currentReportMonth === new Date().toISOString().slice(0,7);
  container.querySelector('#budgetContent').innerHTML = `
    <div class="report-wrap">
      <div class="report-month-nav">
        <button class="report-nav-btn" id="reportPrev">←</button>
        <span class="report-month-label">${_fmtMonthLabel(currentReportMonth)}</span>
        <button class="report-nav-btn" id="reportNext" ${isCurrentMonth?'disabled':''}>→</button>
      </div>
      ${!d || d.total_transactions === 0
        ? '<div class="report-empty">No data for this month.</div>'
        : `
        <div class="report-cards">
          <div class="report-card"><div class="rc-label">Transactions</div><div class="rc-value">${d.total_transactions}</div></div>
          <div class="report-card"><div class="rc-label">Total Spent</div><div class="rc-value" style="color:var(--red)">₨ ${fmt(d.total_expenses)}</div></div>
          <div class="report-card"><div class="rc-label">Income</div><div class="rc-value" style="color:var(--green)">₨ ${fmt(d.total_income)}</div></div>
          <div class="report-card"><div class="rc-label">Avg / Day</div><div class="rc-value">₨ ${fmt(d.avg_per_day)}</div></div>
        </div>
        ${d.top_merchants?.length ? `
        <div class="report-block">
          <div class="report-block-title">Top Merchants</div>
          <table class="report-table">
            <thead><tr><th>Merchant</th><th>Category</th><th style="text-align:right">Amount</th><th style="text-align:right">Txns</th></tr></thead>
            <tbody>${d.top_merchants.map(m => `<tr>
              <td class="rname">${esc(m.merchant)}</td>
              <td>${CAT_LABELS[m.category] || m.category}</td>
              <td class="ramount">₨ ${fmt(m.total)}</td>
              <td class="rcount">${m.count}</td></tr>`).join('')}
            </tbody>
          </table>
        </div>` : ''}
        ${d.vs_last_month ? `
        <div class="report-block">
          <div class="report-block-title">vs Last Month</div>
          <div class="vs-grid">
            <div class="vs-item"><div class="vs-label">Expenses</div><div class="vs-value" style="color:${d.vs_last_month.expenses_change>=0?'var(--red)':'var(--green)'}">
              ${d.vs_last_month.expenses_change>=0?'+':''}${d.vs_last_month.expenses_change_pct}%</div></div>
            <div class="vs-item"><div class="vs-label">Income</div><div class="vs-value">${d.vs_last_month.income_change>=0?'+':''}${d.vs_last_month.income_change_pct}%</div></div>
            <div class="vs-item"><div class="vs-label">Transactions</div><div class="vs-value">${d.vs_last_month.tx_count_change>=0?'+':''}${d.vs_last_month.tx_count_change}</div></div>
          </div>
        </div>` : ''}
        ${d.by_source ? `
        <div class="report-block">
          <div class="report-block-title">By Source</div>
          <div class="source-pills">${Object.entries(d.by_source).map(([src,count]) =>
            `<span style="background:var(--surface2);border-radius:6px;padding:4px 10px;font-size:12px">${esc(src)}: ${count}</span>`
          ).join('')}</div>
        </div>` : ''}`}
    </div>`;

  container.querySelector('#reportPrev')?.addEventListener('click', () => {
    currentReportMonth = prev; loadReport();
  });
  container.querySelector('#reportNext')?.addEventListener('click', () => {
    if (!isCurrentMonth) { currentReportMonth = next; loadReport(); }
  });
}

function _prevMonth(ym) {
  const [y, m] = ym.split('-').map(Number);
  const d = new Date(y, m-2, 1);
  return d.toISOString().slice(0,7);
}
function _nextMonth(ym) {
  const [y, m] = ym.split('-').map(Number);
  const d = new Date(y, m, 1);
  return d.toISOString().slice(0,7);
}

// ── Category Rules ────────────────────────────────────────

async function loadRules() {
  const content = container.querySelector('#budgetContent');
  content.innerHTML = '<div class="empty"><p style="color:var(--muted)">Loading…</p></div>';
  try {
    _renderRules(await getCategoryMappings());
  } catch {
    content.innerHTML = '<div class="empty"><h3>Error</h3><p>Could not load rules.</p></div>';
  }
}

function _renderRules(rules) {
  container.querySelector('#budgetContent').innerHTML = `
    <div class="rules-section">
      <h3>Category Rules</h3>
      <p>Rules apply to every future sync — they override keyword-based auto-categorisation.</p>
      <div class="rules-add">
        <input type="text" id="rMerchant" placeholder="Merchant name (e.g. imtiaz)">
        <select id="rCategory">${buildCategoryOptions()}</select>
        <button class="btn btn-primary" id="addRuleBtn">+ Add Rule</button>
      </div>
      ${!rules.length ? '<p style="color:var(--muted);font-size:13px;text-align:center;padding:16px">No rules yet.</p>' : `
      <table class="rules-table">
        <thead><tr><th>Merchant</th><th>Category</th><th></th></tr></thead>
        <tbody>${rules.map(r => `<tr>
          <td>${esc(r.merchant)}</td>
          <td><span class="cat-chip">${CAT_LABELS[r.category] || r.category}</span></td>
          <td><button class="btn btn-danger" data-delmerchant="${esc(r.merchant)}">✕</button></td>
        </tr>`).join('')}</tbody>
      </table>`}
    </div>`;

  container.querySelector('#addRuleBtn').addEventListener('click', async () => {
    const merchant  = container.querySelector('#rMerchant').value.trim();
    const category  = container.querySelector('#rCategory').value;
    if (!merchant) return;
    await addCategoryMapping({ merchant, category });
    container.querySelector('#rMerchant').value = '';
    loadRules();
  });

  container.querySelectorAll('[data-delmerchant]').forEach(btn => {
    btn.addEventListener('click', async () => {
      await deleteCategoryMapping(btn.dataset.delmerchant);
      loadRules();
    });
  });
}
```

- [ ] **Step 2: Commit**

```bash
git add static/js/views/budget.js
git commit -m "feat: add budget.js view — budgets, monthly report, category rules"
```

---

## Task 8: `views/sync.js` — Gmail Import & Sync Screen (New Stitch Design)

**Files:**
- Create: `static/js/views/sync.js`
- Modify: `static/css/app.css`

- [ ] **Step 1: Add sync view CSS to `static/css/app.css`**

Replace the `/* ── SYNC VIEW ── */` placeholder at the bottom of `app.css` with:

```css
/* ── SYNC VIEW ── */
.sync-view { padding: 8px 0 40px; font-family: 'Manrope', sans-serif; }
.sync-view-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 28px; }
.sync-view-brand { font-size: 20px; font-weight: 700; color: #e5e2e1; letter-spacing: -0.5px; }
.sync-view-brand span { color: var(--primary); }
.sync-view-lock { font-size: 22px; color: var(--muted); }

.sync-status-card {
  background: #1C1B1B;
  border-radius: 16px;
  padding: 20px 22px;
  margin-bottom: 24px;
  display: flex;
  align-items: center;
  gap: 16px;
}
.sync-status-icon { font-size: 28px; color: var(--primary); flex-shrink: 0; }
.sync-status-info { flex: 1; min-width: 0; }
.sync-status-label { font-size: 14px; font-weight: 600; color: #e5e2e1; margin-bottom: 2px; }
.sync-status-email { font-size: 12px; color: #bbcabf; margin-bottom: 6px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sync-status-meta { display: flex; align-items: center; gap: 10px; }
.sync-status-badge { background: rgba(78,222,163,0.15); color: var(--primary); font-size: 10px; font-weight: 700; padding: 2px 8px; border-radius: 8px; letter-spacing: 0.4px; }
.sync-status-time { font-size: 11px; color: #bbcabf; }

.sync-section-label { font-size: 12px; font-weight: 600; color: #bbcabf; text-transform: uppercase; letter-spacing: 0.6px; margin-bottom: 14px; }

/* Calendar */
.sync-calendar { background: #1C1B1B; border-radius: 16px; padding: 20px; margin-bottom: 24px; }
.cal-nav { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
.cal-nav-btn { background: #2A2A2A; border: none; color: #e5e2e1; width: 32px; height: 32px; border-radius: 50%; cursor: pointer; font-size: 16px; display: flex; align-items: center; justify-content: center; transition: background 0.15s; }
.cal-nav-btn:hover { background: #353534; }
.cal-month-label { font-size: 15px; font-weight: 600; color: #e5e2e1; }
.cal-grid { display: grid; grid-template-columns: repeat(7, 1fr); gap: 4px; }
.cal-dow { font-size: 10px; font-weight: 600; color: #bbcabf; text-align: center; padding: 4px 0; }
.cal-day {
  aspect-ratio: 1; border-radius: 50%; border: none; background: transparent;
  color: #e5e2e1; font-size: 13px; font-family: 'Manrope', sans-serif;
  cursor: pointer; display: flex; align-items: center; justify-content: center;
  transition: background 0.12s, color 0.12s;
}
.cal-day:hover:not(.disabled):not(.selected):not(.in-range) { background: #2A2A2A; }
.cal-day.disabled { color: #3c4a42; cursor: not-allowed; }
.cal-day.empty { cursor: default; }
.cal-day.selected { background: var(--primary); color: #003824; font-weight: 700; }
.cal-day.in-range { background: rgba(78,222,163,0.15); border-radius: 0; }
.cal-day.range-start { border-radius: 50% 0 0 50%; background: rgba(78,222,163,0.15); }
.cal-day.range-start.selected { background: var(--primary); color: #003824; }
.cal-day.range-end { border-radius: 0 50% 50% 0; background: rgba(78,222,163,0.15); }
.cal-day.range-end.selected { background: var(--primary); color: #003824; }
.cal-selection-hint { font-size: 11px; color: #bbcabf; text-align: center; margin-top: 12px; min-height: 16px; }

/* Sync button */
.sync-now-btn {
  width: 100%;
  padding: 16px;
  border: none;
  border-radius: 14px;
  background: linear-gradient(135deg, var(--primary), #10B981);
  color: #003824;
  font-size: 16px;
  font-weight: 700;
  font-family: 'Manrope', sans-serif;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  transition: opacity 0.2s, transform 0.15s;
  margin-bottom: 12px;
}
.sync-now-btn:active { transform: scale(0.98); opacity: 0.9; }
.sync-now-btn:disabled { opacity: 0.5; cursor: not-allowed; transform: none; }
.sync-now-btn .btn-spinner {
  width: 16px; height: 16px;
  border: 2px solid rgba(0,56,36,0.3);
  border-top-color: #003824;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
  flex-shrink: 0;
}

.sync-reliability { text-align: center; font-size: 12px; color: #bbcabf; margin-bottom: 24px; }
.sync-reliability strong { color: var(--primary); }

/* Sync result */
.sync-result { background: #1C1B1B; border-radius: 14px; padding: 18px 20px; margin-top: 16px; }
.sync-result-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 12px; }
.sync-result-item { }
.sri-label { font-size: 10px; color: #bbcabf; text-transform: uppercase; letter-spacing: 0.4px; margin-bottom: 4px; }
.sri-value { font-size: 20px; font-weight: 700; color: #e5e2e1; font-family: 'Manrope', sans-serif; }
.sri-value.highlight { color: var(--primary); }

.sync-error { background: rgba(239,68,68,0.1); border: 1px solid rgba(239,68,68,0.2); border-radius: 12px; padding: 16px 18px; margin-top: 16px; }
.sync-error p { font-size: 13px; color: #ffb4ab; margin-bottom: 10px; }
.sync-error button { background: none; border: 1px solid rgba(239,68,68,0.4); border-radius: 8px; color: #ffb4ab; font-size: 12px; font-family: 'Manrope', sans-serif; padding: 6px 14px; cursor: pointer; }
```

- [ ] **Step 2: Create `static/js/views/sync.js`**

```js
// static/js/views/sync.js
import { syncGmail, getSyncStatus } from '../api.js';
import { fmt } from '../utils/constants.js';
import { timeAgo, isNoSync } from '../utils/time.js';

// ── Calendar state ────────────────────────────────────────
let calYear, calMonth;       // currently displayed month
let startDate = null;        // Date object | null
let endDate   = null;        // Date object | null
let container;

const MAX_LOOKBACK_DAYS = 90;

export function init(el) {
  container = el;
  const today = new Date();
  calYear  = today.getFullYear();
  calMonth = today.getMonth();
  // Default selection: first of this month → today
  startDate = new Date(today.getFullYear(), today.getMonth(), 1);
  endDate   = today;

  container.innerHTML = `
    <div class="sync-view">
      <!-- Header -->
      <div class="sync-view-header">
        <div class="sync-view-brand">FinTrack <span>PK</span></div>
        <span class="sync-view-lock material-symbols-outlined">lock</span>
      </div>

      <!-- Gmail Status Card -->
      <div class="sync-status-card">
        <span class="sync-status-icon material-symbols-outlined">mail</span>
        <div class="sync-status-info">
          <div class="sync-status-label">Gmail Connected</div>
          <div class="sync-status-email">your.account@gmail.com</div>
          <div class="sync-status-meta">
            <span class="sync-status-badge">ACTIVE</span>
            <span class="sync-status-time" id="syncLastTime">Loading…</span>
          </div>
        </div>
      </div>

      <!-- Date Range -->
      <div class="sync-section-label">Select Date Range</div>
      <div class="sync-calendar" id="syncCalendar"></div>

      <!-- Sync Now -->
      <button class="sync-now-btn" id="syncNowBtn">
        <span class="material-symbols-outlined" style="font-size:20px">sync</span>
        Sync Now
      </button>
      <div class="sync-reliability">Syncing reliability <strong>99.8%</strong></div>

      <!-- Result / Error (hidden initially) -->
      <div id="syncFeedback"></div>
    </div>`;

  _renderCalendar();
  _loadLastSync();

  container.querySelector('#syncNowBtn').addEventListener('click', _doSync);
  container.querySelector('#syncCalendar').addEventListener('click', _onCalendarClick);
}

export function destroy() {}

// ── Last sync time ────────────────────────────────────────

async function _loadLastSync() {
  try {
    const data = await getSyncStatus();
    container.querySelector('#syncLastTime').textContent =
      isNoSync(data) ? 'Never synced' : 'Last synced ' + timeAgo(data.synced_at);
  } catch {
    container.querySelector('#syncLastTime').textContent = 'Status unavailable';
  }
}

// ── Calendar ──────────────────────────────────────────────

function _minDate() {
  // Earliest selectable: today - 90 days
  const d = new Date();
  d.setDate(d.getDate() - MAX_LOOKBACK_DAYS);
  d.setHours(0,0,0,0);
  return d;
}

function _renderCalendar() {
  const cal = container.querySelector('#syncCalendar');
  const today = new Date(); today.setHours(0,0,0,0);
  const minDate = _minDate();

  const firstDay = new Date(calYear, calMonth, 1).getDay(); // 0=Sun
  const daysInMonth = new Date(calYear, calMonth + 1, 0).getDate();
  const monthLabel = new Date(calYear, calMonth, 1)
    .toLocaleDateString('en-PK', { month: 'long', year: 'numeric' });

  const prevDisabled = new Date(calYear, calMonth - 1, 1) < new Date(minDate.getFullYear(), minDate.getMonth(), 1);
  const nextDisabled = new Date(calYear, calMonth + 1, 1) > new Date(today.getFullYear(), today.getMonth(), 1);

  let hintText = '';
  if (!startDate) hintText = 'Tap to select start date';
  else if (!endDate) hintText = 'Tap to select end date';
  else hintText = `${_fmt(startDate)} → ${_fmt(endDate)}`;

  const dows = ['Su','Mo','Tu','We','Th','Fr','Sa'];
  const dowHtml = dows.map(d => `<div class="cal-dow">${d}</div>`).join('');

  const cells = [];
  for (let i = 0; i < firstDay; i++) cells.push('<div class="cal-day empty"></div>');
  for (let d = 1; d <= daysInMonth; d++) {
    const date = new Date(calYear, calMonth, d);
    date.setHours(0,0,0,0);
    const isDisabled = date < minDate || date > today;
    const isSelected = _isSelected(date);
    const isInRange  = _isInRange(date);
    const isStart    = startDate && _sameDay(date, startDate);
    const isEnd      = endDate   && _sameDay(date, endDate);
    let cls = 'cal-day';
    if (isDisabled) cls += ' disabled';
    if (isSelected) cls += ' selected';
    if (!isStart && !isEnd && isInRange) cls += ' in-range';
    if (isStart && endDate) cls += ' range-start';
    if (isEnd)              cls += ' range-end';
    cells.push(`<div class="${cls}" data-date="${_isoDay(date)}" ${isDisabled?'':''}>${d}</div>`);
  }

  cal.innerHTML = `
    <div class="cal-nav">
      <button class="cal-nav-btn" id="calPrev" ${prevDisabled?'disabled':''}>‹</button>
      <span class="cal-month-label">${monthLabel}</span>
      <button class="cal-nav-btn" id="calNext" ${nextDisabled?'disabled':''}>›</button>
    </div>
    <div class="cal-grid">${dowHtml}${cells.join('')}</div>
    <div class="cal-selection-hint">${hintText}</div>`;

  cal.querySelector('#calPrev')?.addEventListener('click', (e) => {
    e.stopPropagation();
    calMonth--; if (calMonth < 0) { calMonth = 11; calYear--; }
    _renderCalendar();
  });
  cal.querySelector('#calNext')?.addEventListener('click', (e) => {
    e.stopPropagation();
    calMonth++; if (calMonth > 11) { calMonth = 0; calYear++; }
    _renderCalendar();
  });
}

function _onCalendarClick(e) {
  const dayEl = e.target.closest('.cal-day');
  if (!dayEl || dayEl.classList.contains('disabled') || dayEl.classList.contains('empty')) return;
  const clicked = new Date(dayEl.dataset.date + 'T00:00:00');

  if (!startDate || (startDate && endDate)) {
    // Start fresh selection
    startDate = clicked; endDate = null;
  } else if (clicked < startDate) {
    // Clicked before start → make it the new start
    startDate = clicked; endDate = null;
  } else if (_sameDay(clicked, startDate)) {
    // Clicked start again → reset
    startDate = null; endDate = null;
  } else {
    // Valid end date
    endDate = clicked;
  }
  _renderCalendar();
}

function _isSelected(date) {
  return (startDate && _sameDay(date, startDate)) || (endDate && _sameDay(date, endDate));
}

function _isInRange(date) {
  if (!startDate || !endDate) return false;
  return date > startDate && date < endDate;
}

function _sameDay(a, b) {
  return a.getFullYear() === b.getFullYear() &&
         a.getMonth()    === b.getMonth() &&
         a.getDate()     === b.getDate();
}

function _isoDay(d) {
  return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`;
}

function _fmt(d) {
  return d.toLocaleDateString('en-PK', { day: 'numeric', month: 'short' });
}

// ── Sync action ───────────────────────────────────────────

async function _doSync() {
  if (!startDate) {
    container.querySelector('#syncFeedback').innerHTML =
      '<div class="sync-error"><p>Please select a date range first.</p></div>';
    return;
  }

  const btn = container.querySelector('#syncNowBtn');
  const feedback = container.querySelector('#syncFeedback');

  btn.disabled = true;
  btn.innerHTML = `<span class="btn-spinner"></span> Syncing…`;
  feedback.innerHTML = '';

  const dateFrom = _isoDay(startDate);
  const dateTo   = endDate ? _isoDay(endDate) : _isoDay(new Date());

  try {
    const d = await syncGmail(dateFrom, dateTo);
    feedback.innerHTML = `
      <div class="sync-result">
        <div class="sync-result-grid">
          <div class="sync-result-item"><div class="sri-label">Emails Found</div><div class="sri-value">${d.emails_found}</div></div>
          <div class="sync-result-item"><div class="sri-label">Imported</div><div class="sri-value highlight">${d.parsed_ok}</div></div>
          <div class="sync-result-item"><div class="sri-label">Pending Review</div><div class="sri-value">${d.pending_review}</div></div>
          <div class="sync-result-item"><div class="sri-label">Already Imported</div><div class="sri-value">${d.skipped_duplicate}</div></div>
          <div class="sync-result-item"><div class="sri-label">Skipped (Unreadable)</div><div class="sri-value">${d.skipped_parse}</div></div>
        </div>
      </div>`;
    // Refresh last sync time
    _loadLastSync();
  } catch (e) {
    let msg = e.detail || 'Sync failed. Please try again.';
    let showRetry = true;
    if (e.status === 500 && e.detail && e.detail.includes('Gmail auth failed')) {
      msg = 'Gmail not connected — please authenticate on desktop first.';
      showRetry = false;
    } else if (e.status === 500 && e.detail && e.detail.includes('Gmail search failed')) {
      msg = 'Gmail search failed. Tap to retry.';
    } else if (e.status === 0) {
      msg = 'No connection. Check your network.';
      showRetry = false;
    }
    feedback.innerHTML = `
      <div class="sync-error">
        <p>${msg}</p>
        ${showRetry ? '<button id="retryBtn">Retry</button>' : ''}
      </div>`;
    container.querySelector('#retryBtn')?.addEventListener('click', _doSync);
  }

  btn.disabled = false;
  btn.innerHTML = `<span class="material-symbols-outlined" style="font-size:20px">sync</span> Sync Now`;
}
```

- [ ] **Step 3: Commit**

```bash
git add static/js/views/sync.js static/css/app.css
git commit -m "feat: add sync.js view — Stitch Gmail Import & Sync screen with calendar"
```

---

## Task 9: `router.js` + Shell `index.html`

**Files:**
- Create: `static/js/router.js`
- Modify: `static/index.html`

- [ ] **Step 1: Create `static/js/router.js`**

```js
// static/js/router.js
import { init as authInit } from './auth.js';
import * as dashboard from './views/dashboard.js';
import * as activity  from './views/activity.js';
import * as budget    from './views/budget.js';
import * as syncView  from './views/sync.js';

const VIEWS = {
  dashboard: dashboard,
  activity:  activity,
  sync:      syncView,
  budget:    budget,
};

const DEFAULT_VIEW = 'dashboard';

// Track which views have been initialized (lazy init)
const initialized = {};
// Containers per view
const containers  = {};

function getViewId() {
  const hash = location.hash.slice(1);
  return VIEWS[hash] ? hash : DEFAULT_VIEW;
}

function navigate(viewId) {
  if (!VIEWS[viewId]) viewId = DEFAULT_VIEW;

  // Hide all views
  Object.keys(containers).forEach(id => {
    if (containers[id]) containers[id].style.display = 'none';
  });

  // Destroy current view if it has a destroy hook
  const currentId = document.querySelector('.bottom-nav-btn.active')?.dataset.view;
  if (currentId && VIEWS[currentId]?.destroy) {
    try { VIEWS[currentId].destroy(); } catch {}
  }

  // Update bottom nav
  document.querySelectorAll('.bottom-nav-btn').forEach(btn => {
    btn.classList.toggle('active', btn.dataset.view === viewId);
  });

  // Update hash without triggering another hashchange
  if (location.hash !== '#' + viewId) {
    history.replaceState(null, '', '#' + viewId);
  }

  // Get or create container
  if (!containers[viewId]) {
    const div = document.createElement('div');
    div.id = 'view-' + viewId;
    div.className = 'view-container';
    document.getElementById('app').appendChild(div);
    containers[viewId] = div;
  }

  containers[viewId].style.display = 'block';

  // Lazy init or re-init
  if (!initialized[viewId]) {
    initialized[viewId] = true;
    VIEWS[viewId].init(containers[viewId]);
  } else if (VIEWS[viewId].init) {
    // Re-call init to refresh data; init() handles the initialized check internally
    VIEWS[viewId].init(containers[viewId]);
  }
}

// ── Boot ──────────────────────────────────────────────────

authInit(() => {
  // Auth passed — show the app shell
  document.getElementById('appShell').style.display = 'flex';

  // Wire up bottom nav buttons
  document.querySelectorAll('.bottom-nav-btn').forEach(btn => {
    btn.addEventListener('click', () => navigate(btn.dataset.view));
  });

  // Handle browser back/forward
  window.addEventListener('hashchange', () => navigate(getViewId()));

  // Navigate to initial view
  navigate(getViewId());
});
```

- [ ] **Step 2: Rewrite `static/index.html` as shell**

```html
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover">
<title>FinTrack PK</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Manrope:wght@400;500;600;700&display=swap" rel="stylesheet">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Material+Symbols+Outlined:opsz,wght,FILL,GRAD@20..48,100..700,0..1,-50..200">
<link rel="stylesheet" href="/static/css/app.css">
</head>
<body>

<!-- App shell (hidden until auth passes) -->
<div id="appShell" style="display:none; flex-direction:column; min-height:100vh;">

  <!-- View mount point -->
  <div class="app" id="app"></div>

  <!-- Bottom Navigation -->
  <nav class="bottom-nav">
    <button class="bottom-nav-btn active" data-view="dashboard">
      <span class="material-symbols-outlined">home</span>
      <span>Dashboard</span>
    </button>
    <button class="bottom-nav-btn" data-view="activity">
      <span class="material-symbols-outlined">receipt_long</span>
      <span>Activity</span>
    </button>
    <button class="bottom-nav-btn" data-view="sync">
      <span class="material-symbols-outlined">sync</span>
      <span>Sync</span>
    </button>
    <button class="bottom-nav-btn" data-view="budget">
      <span class="material-symbols-outlined">wallet</span>
      <span>Budget</span>
    </button>
  </nav>

</div>

<script type="module" src="/static/js/router.js"></script>
</body>
</html>
```

- [ ] **Step 3: Add bottom nav CSS to `app.css`**

Add at the end of `static/css/app.css`:
```css
/* ── BOTTOM NAVIGATION ── */
.bottom-nav {
  position: fixed;
  bottom: 0;
  left: 0; right: 0;
  background: rgba(20, 26, 39, 0.92);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border-top: 1px solid var(--border);
  display: flex;
  z-index: 200;
  padding-bottom: env(safe-area-inset-bottom);
}
.bottom-nav-btn {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 3px;
  padding: 10px 8px;
  border: none;
  background: transparent;
  color: var(--muted);
  font-size: 10px;
  font-family: inherit;
  font-weight: 500;
  cursor: pointer;
  transition: color 0.15s;
  -webkit-tap-highlight-color: transparent;
}
.bottom-nav-btn .material-symbols-outlined { font-size: 22px; }
.bottom-nav-btn.active { color: var(--primary); }
.bottom-nav-btn:active { opacity: 0.7; }

/* Adjust app shell padding for bottom nav */
.app { padding-bottom: calc(80px + env(safe-area-inset-bottom)); }

/* View containers */
.view-container { display: none; }
```

- [ ] **Step 4: Manual verify all tabs**

Start server and verify:
- PIN screen appears and auth works
- All 4 bottom nav tabs switch correctly
- Dashboard shows summary cards and period selector
- Activity shows transaction list, filters, add form, quick-add FAB
- Budget shows budget/report/rules sub-tabs
- Sync shows the new Gmail Import & Sync screen

- [ ] **Step 5: Commit**

```bash
git add static/js/router.js static/index.html static/css/app.css
git commit -m "feat: add router.js, shell index.html, bottom navigation bar"
```

---

## Task 10: Android Asset Sync

**Files:**
- Modify: `android/app/src/main/python/static/` (entire tree)

- [ ] **Step 1: Check current Android static structure**

```bash
ls android/app/src/main/python/static/
```

- [ ] **Step 2: Create subdirectories and sync files**

```bash
mkdir -p android/app/src/main/python/static/css
mkdir -p android/app/src/main/python/static/js/utils
mkdir -p android/app/src/main/python/static/js/views

cp static/index.html          android/app/src/main/python/static/
cp static/css/app.css         android/app/src/main/python/static/css/
cp static/js/api.js           android/app/src/main/python/static/js/
cp static/js/router.js        android/app/src/main/python/static/js/
cp static/js/auth.js          android/app/src/main/python/static/js/
cp static/js/utils/constants.js android/app/src/main/python/static/js/utils/
cp static/js/utils/time.js    android/app/src/main/python/static/js/utils/
cp static/js/views/dashboard.js android/app/src/main/python/static/js/views/
cp static/js/views/activity.js  android/app/src/main/python/static/js/views/
cp static/js/views/budget.js    android/app/src/main/python/static/js/views/
cp static/js/views/sync.js      android/app/src/main/python/static/js/views/
```

- [ ] **Step 3: Verify Android build compiles**

```bash
cd android && ./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/python/static/
git commit -m "chore: sync ES module static files to Android Python assets"
```

---

## Verification Checklist

Before marking done, manually verify against the spec checklist:

- [ ] PIN auth still works; session persists across tab switches
- [ ] All 4 bottom nav tabs render their respective views
- [ ] Dashboard summary cards and category breakdown load correctly
- [ ] Activity view: transaction list, filters, pending review sub-view all function
- [ ] Budget view: set budgets, suggest budgets, monthly report, category rules all function
- [ ] Sync view: calendar date selection works; past 90 days are disabled
- [ ] Sync Now calls `POST /api/sync` with correct `date_from`/`date_to` params
- [ ] Sync result summary shows all 5 fields
- [ ] "Last synced X ago" displays correctly; shows "Never synced" on first install
- [ ] Gmail auth error shows stub message (not OAuth redirect)
- [ ] Network offline state renders correctly
- [ ] Android build succeeds (`./gradlew assembleDebug`)
- [ ] `--primary` token used consistently; `--green` unchanged
