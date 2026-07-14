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
          <div class="sync-result-item"><div class="sri-label">Already Imported</div><div class="sri-value">${d.skipped_duplicate}</div></div>
          <div class="sync-result-item"><div class="sri-label">Skipped (Unreadable)</div><div class="sri-value">${d.skipped_parse}</div></div>
        </div>
      </div>`;
    _loadLastSync();
  } catch (e) {
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
      msg = 'Gmail not connected — connect your account from Settings.';
      showRetry = false;
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
