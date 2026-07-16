// static/js/views/sync.js
import { syncGmail, getSyncStatus, getProfile, getBanks, addBank, deleteBank, startGmailAuth } from '../api.js';
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

      <!-- Gmail Status Card (filled from /api/profile) -->
      <div class="sync-status-card">
        <span class="sync-status-icon material-symbols-outlined">mail</span>
        <div class="sync-status-info">
          <div class="sync-status-label" id="syncGmailLabel">Checking Gmail…</div>
          <div class="sync-status-email" id="syncGmailEmail">&nbsp;</div>
          <div class="sync-status-meta">
            <span class="sync-status-badge off" id="syncGmailBadge">…</span>
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

      <!-- My Banks -->
      <div class="sync-section-label" style="margin-top:28px">My Banks</div>
      <div class="my-banks-card">
        <div class="my-banks-list" id="myBanksList">Loading…</div>
        <form class="my-banks-form" id="myBanksForm">
          <input type="text" id="myBankName" placeholder="Bank name (e.g. HBL)" maxlength="50" autocomplete="off">
          <input type="text" id="myBankAddress" placeholder="Sender or domain (e.g. alerts@hbl.com)" autocomplete="off">
          <button type="submit" class="my-banks-add-btn">Add Bank</button>
        </form>
        <div class="my-banks-error" id="myBanksError"></div>
      </div>
    </div>`;

  _renderCalendar();
  _loadGmailStatus();
  _loadLastSync();
  _loadBanks();

  container.querySelector('#syncNowBtn').addEventListener('click', _doSync);
  container.querySelector('#syncCalendar').addEventListener('click', _onCalendarClick);
  container.querySelector('#myBanksForm').addEventListener('submit', _onAddBank);
  container.querySelector('#myBanksList').addEventListener('click', _onDeleteBank);
}

export function destroy() {}

// ── Gmail connection state ────────────────────────────────

async function _loadGmailStatus() {
  const label = container.querySelector('#syncGmailLabel');
  const email = container.querySelector('#syncGmailEmail');
  const badge = container.querySelector('#syncGmailBadge');
  try {
    const profile = await getProfile();
    if (profile.email) {
      label.textContent = 'Gmail Connected';
      email.textContent = profile.email;
      badge.textContent = 'ACTIVE';
      badge.classList.remove('off');
    } else {
      label.textContent = 'Gmail Not Connected';
      email.textContent = 'Tap Sync Now to connect your account';
      badge.textContent = 'NOT CONNECTED';
    }
  } catch {
    label.textContent = 'Gmail';
    email.textContent = 'Status unavailable';
    badge.textContent = 'UNKNOWN';
  }
}

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

// ── My Banks ──────────────────────────────────────────────

function _esc(s) {
  const d = document.createElement('div');
  d.textContent = String(s);
  return d.innerHTML;
}

async function _loadBanks() {
  const list = container.querySelector('#myBanksList');
  try {
    const data = await getBanks();
    const chips = data.builtin.map(b =>
      `<span class="bank-chip${b.verified ? '' : ' unverified'}" title="${b.verified ? 'Built-in' : 'Built-in — sender domain not yet verified'}">${_esc(b.bank)}</span>`
    ).join('');
    const custom = data.custom.map(c => `
      <div class="bank-row">
        <div class="bank-row-info"><strong>${_esc(c.bank)}</strong><span>${_esc(c.address)}</span></div>
        <button class="bank-del-btn" data-id="${c.id}" title="Remove">&times;</button>
      </div>`).join('');
    list.innerHTML = `
      <div class="bank-chips">${chips}</div>
      ${custom || '<div class="bank-empty">Bank alerts missing from sync? Add your bank’s sender below.</div>'}`;
  } catch {
    list.textContent = 'Could not load banks.';
  }
}

async function _onAddBank(e) {
  e.preventDefault();
  const nameInput = container.querySelector('#myBankName');
  const addrInput = container.querySelector('#myBankAddress');
  const errorEl   = container.querySelector('#myBanksError');
  const bank    = nameInput.value.trim();
  const address = addrInput.value.trim();

  errorEl.textContent = '';
  if (!bank || !address) {
    errorEl.textContent = 'Enter both a bank name and its sender address or domain.';
    return;
  }

  try {
    await addBank(bank, address);
    nameInput.value = '';
    addrInput.value = '';
    _loadBanks();
  } catch (err) {
    errorEl.textContent = err.detail || 'Could not add bank. Please try again.';
  }
}

async function _onDeleteBank(e) {
  const btn = e.target.closest('.bank-del-btn');
  if (!btn) return;
  try {
    await deleteBank(btn.dataset.id);
    _loadBanks();
  } catch (err) {
    container.querySelector('#myBanksError').textContent = err.detail || 'Could not remove bank.';
  }
}

// ── Sync action ───────────────────────────────────────────

async function _doSync() {
  if (!startDate) {
    container.querySelector('#syncFeedback').innerHTML =
      '<div class="sync-error"><p>Please select a date range first.</p></div>';
    return;
  }

  const btn      = container.querySelector('#syncNowBtn');
  const feedback = container.querySelector('#syncFeedback');

  btn.disabled   = true;
  feedback.innerHTML = '';

  // Elapsed-time counter so the user can see progress during long syncs
  let elapsed = 0;
  const _updateBtn = () => {
    const m = Math.floor(elapsed / 60), s = elapsed % 60;
    const t = m > 0 ? `${m}m ${s}s` : `${s}s`;
    btn.innerHTML = `<span class="btn-spinner"></span> Syncing… ${t}`;
  };
  _updateBtn();
  const timer = setInterval(() => { elapsed++; _updateBtn(); }, 1000);

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
          ${d.unparsed ? `<div class="sync-result-item"><div class="sri-label">Couldn't Parse (in Pending)</div><div class="sri-value">${d.unparsed}</div></div>` : ''}
          <div class="sync-result-item"><div class="sri-label">Already Imported</div><div class="sri-value">${d.skipped_duplicate}</div></div>
          <div class="sync-result-item"><div class="sri-label">Skipped (Not Transactions)</div><div class="sri-value">${d.skipped_parse}</div></div>
        </div>
      </div>`;
    _loadLastSync();
    _loadGmailStatus();  // first successful sync establishes the connection
  } catch (e) {
    if (e.status === 428) {
      // Gmail not connected — open the OAuth consent page and resume
      // the sync automatically once the account is linked.
      _startGmailConnect();
      return;
    }

    let msg      = e.detail || 'Sync failed. Please try again.';
    let showRetry = true;

    if (e.status === 0 && e.detail === 'timeout') {
      // Request exceeded 5 minutes — server is still processing (not crashed)
      msg = 'Sync is taking longer than expected. Your transactions are still importing in the background — check back in a minute.';
      showRetry = false;
    } else if (e.status === 0) {
      msg = 'No connection. Check your network.';
      showRetry = false;
    } else if (e.status === 500 && e.detail?.includes('Gmail auth failed')) {
      msg = 'Gmail connection failed unexpectedly. Please try again.';
    } else if (e.status === 500 && e.detail?.includes('Gmail search failed')) {
      msg = 'Gmail search failed. Tap to retry.';
    }

    feedback.innerHTML = `
      <div class="sync-error">
        <p>${msg}</p>
        ${showRetry ? '<button id="retryBtn">Retry</button>' : ''}
      </div>`;
    container.querySelector('#retryBtn')?.addEventListener('click', _doSync);
  } finally {
    clearInterval(timer);
    btn.disabled = false;
    btn.innerHTML = `<span class="material-symbols-outlined" style="font-size:20px">sync</span> Sync Now`;
  }
}

// ── Gmail connect (OAuth) ─────────────────────────────────

let authPolling = false;

async function _startGmailConnect() {
  const feedback = container.querySelector('#syncFeedback');

  // Android WebView: WebViewManager injects AndroidBridge, whose
  // requestSync() runs the native AppAuth/Custom-Tab OAuth flow and writes
  // token.json directly — no consent URL to open in-page.
  if (window.AndroidBridge && typeof window.AndroidBridge.requestSync === 'function') {
    feedback.innerHTML = `
      <div class="sync-connect">
        <p>Opening Google sign-in… Finish connecting your Gmail — syncing will start automatically.</p>
      </div>`;
    window.AndroidBridge.requestSync();
    _pollForConnection();
    return;
  }

  try {
    const { auth_url } = await startGmailAuth();
    // window.open is usually blocked here (not a direct user gesture),
    // so always render a link the user can tap as well.
    const opened = window.open(auth_url, '_blank');
    feedback.innerHTML = `
      <div class="sync-connect">
        <p>${opened
          ? 'Google sign-in opened in a new tab. Finish connecting your Gmail — syncing will start automatically.'
          : 'Connect your Gmail account to sync. Sign in with Google, then syncing will start automatically.'}</p>
        <a href="${_esc(auth_url)}" target="_blank" rel="noopener">Open Google sign-in</a>
      </div>`;
    _pollForConnection();
  } catch (err) {
    feedback.innerHTML = `
      <div class="sync-error"><p>${_esc(err.detail || 'Could not start Google sign-in. Please try again.')}</p></div>`;
  }
}

async function _pollForConnection() {
  if (authPolling) return;
  authPolling = true;
  try {
    for (let i = 0; i < 100; i++) {           // ~5 min at 3s intervals
      await new Promise(r => setTimeout(r, 3000));
      if (!container || !container.isConnected) return;  // view was swapped out
      try {
        const profile = await getProfile();
        if (profile.email) {
          container.querySelector('#syncFeedback').innerHTML = '';
          _loadGmailStatus();
          _doSync();                          // resume the sync the user asked for
          return;
        }
      } catch { /* transient — keep polling */ }
    }
  } finally {
    authPolling = false;
  }
}
