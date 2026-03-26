// static/js/views/activity.js
import {
  getTransactions, createTransaction, createCashTransaction,
  patchTransaction, deleteTransaction
} from '../api.js';
import {
  CATEGORIES, CAT_LABELS, CAT_EMOJI, CAT_COLORS,
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
    <div class="thv">

      <!-- Glass sticky header -->
      <div class="thv-header">
        <span class="thv-brand">Activity</span>
        <div class="thv-header-right">
          <button class="thv-add-btn" id="thvAddBtn">+</button>
          <span class="material-symbols-outlined thv-lock-icon">lock</span>
        </div>
      </div>

      <!-- Sub-tabs -->
      <div class="thv-tabs-row">
        <div class="thv-tab active" data-subtab="all">All Transactions</div>
        <div class="thv-tab" data-subtab="pending">Pending <span class="thv-badge" id="pendingBadge" style="display:none">0</span></div>
      </div>

      <!-- Search + Filter toggle row -->
      <div class="thv-search-row">
        <div class="thv-search-wrap">
          <span class="material-symbols-outlined thv-search-icon">search</span>
          <input type="text" class="thv-search-input" id="searchInput" placeholder="Search transactions…">
        </div>
        <button class="thv-filter-pill" id="filterToggle">
          <span class="material-symbols-outlined">tune</span>
          <span>Filters</span>
        </button>
      </div>

      <!-- Filter panel (hidden by default) -->
      <div class="thv-filter-panel" id="filterPanel">
        <div class="thv-filter-grid">
          <select id="fBank">
            <option value="">All Banks</option>
            <option value="SCB">SCB</option>
            <option value="Meezan">Meezan</option>
            <option value="Cash">Cash</option>
            <option value="HBL">HBL</option>
          </select>
          <select id="fCategory">
            <option value="">All Categories</option>
            ${CATEGORIES.map(c => `<option value="${c}">${CAT_LABELS[c]}</option>`).join('')}
          </select>
          <input type="number" id="fAmountMin" placeholder="Min ₨">
          <input type="number" id="fAmountMax" placeholder="Max ₨">
          <input type="date" id="fDateFrom">
          <input type="date" id="fDateTo">
        </div>
        <button class="thv-clear-btn" id="clearFiltersBtn">Clear Filters</button>
      </div>

      <!-- Count label -->
      <div class="thv-count" id="txCount"></div>

      <!-- Transaction list container -->
      <div id="txContainer">
        <div class="thv-empty">
          <span class="material-symbols-outlined thv-empty-icon">receipt_long</span>
          <p>No transactions yet</p>
          <p class="thv-empty-sub">Sync Gmail to pull bank alerts, or tap + to log a cash expense.</p>
        </div>
      </div>

    </div><!-- /.thv -->

    <!-- FAB -->
    <button class="fab" id="fab" title="Log cash expense">+</button>

    <!-- Quick-add backdrop + sheet -->
    <div class="qa-backdrop" id="qaBackdrop"></div>
    <div class="quick-add ace-sheet" id="quickAdd">

      <!-- Drag handle -->
      <div class="ace-drag-handle"></div>

      <!-- Header -->
      <div class="ace-header">
        <button class="ace-close-btn" id="qaCloseBtn">
          <span class="material-symbols-outlined">close</span>
        </button>
        <span class="ace-heading">Add Cash Entry</span>
        <div class="ace-header-spacer"></div>
      </div>

      <!-- Amount zone -->
      <div class="ace-amount-zone">
        <span class="ace-currency-label">PKR</span>
        <input type="number" id="qaAmount" class="ace-amount-input" placeholder="0" min="0" inputmode="decimal">
      </div>

      <!-- Quick presets -->
      <div class="ace-presets-row">
        ${[500,1000,2500,5000,10000].map(v => `<button class="qa-preset-btn ace-preset" data-amount="${v}">₨${v>=1000?(v/1000)+'k':v}</button>`).join('')}
      </div>

      <!-- Category -->
      <div class="ace-section-label">Category</div>
      <div class="ace-cat-grid">
        ${CATEGORIES.map(c => `
          <button class="qa-cat-btn ace-cat-chip${c==='other'?' active':''}" data-cat="${c}">
            <div class="ace-cat-circle" style="background:${CAT_COLORS[c]}22">
              <span class="qa-cat-icon ace-cat-emoji">${CAT_EMOJI[c]}</span>
            </div>
            <span class="ace-cat-label">${CAT_LABELS[c]}</span>
          </button>`).join('')}
      </div>

      <!-- Footer: note + date + save -->
      <div class="ace-footer">
        <div class="ace-note-field">
          <span class="material-symbols-outlined ace-note-icon">edit_note</span>
          <input type="text" id="qaNote" class="ace-note-input" placeholder="Note (e.g. chai, auto-rickshaw)">
        </div>
        <div class="ace-date-strip">
          <input type="date" id="qaDate" class="ace-date-input">
          <button class="ace-chip-btn" id="qaYesterdayBtn">Yesterday</button>
        </div>
        <button class="ace-submit-btn" id="qaSaveBtn">
          <span>Add Entry</span>
          <span class="material-symbols-outlined">arrow_forward</span>
        </button>
      </div>

    </div>

    <!-- Add Transaction sheet backdrop -->
    <div class="thv-sheet-backdrop" id="addBackdrop"></div>

    <!-- Add Transaction bottom sheet -->
    <div id="addForm" class="thv-add-sheet">
      <div class="thv-sheet-header">
        <span class="thv-sheet-title">Add Transaction</span>
        <button id="addFormCancelBtn" class="thv-sheet-close">✕</button>
      </div>
      <div class="thv-sheet-fields">
        <div class="thv-field">
          <label class="thv-label">Merchant / Description</label>
          <input type="text" id="newMerchant" class="thv-input" placeholder="e.g. Imtiaz Super Market">
        </div>
        <div class="thv-field">
          <label class="thv-label">Amount (PKR)</label>
          <input type="number" id="newAmount" class="thv-input" placeholder="0.00" min="0" step="0.01">
        </div>
        <div class="thv-field">
          <label class="thv-label">Type</label>
          <select id="newType" class="thv-input">
            <option value="debit">Debit (expense)</option>
            <option value="credit">Credit (income)</option>
          </select>
        </div>
        <div class="thv-field">
          <label class="thv-label">Bank</label>
          <select id="newBank" class="thv-input">
            <option value="SCB">SCB</option>
            <option value="Meezan">Meezan</option>
            <option value="HBL">HBL</option>
            <option value="Cash">Cash</option>
            <option value="Other">Other</option>
          </select>
        </div>
        <div class="thv-field">
          <label class="thv-label">Category</label>
          <select id="newCategory" class="thv-input">${buildCategoryOptions()}</select>
        </div>
        <div class="thv-field">
          <label class="thv-label">Date</label>
          <input type="date" id="newDate" class="thv-input" value="${isoDate(new Date())}">
        </div>
      </div>
      <button class="thv-save-btn" id="addFormSaveBtn">Save Transaction</button>
    </div>
  `;
}

function _bindEvents() {
  // Sub-tabs
  container.querySelectorAll('[data-subtab]').forEach(tab => {
    tab.addEventListener('click', () => _switchSubTab(tab.dataset.subtab, tab));
  });

  // Add form — header + button
  container.querySelector('#thvAddBtn').addEventListener('click', _toggleAddForm);
  container.querySelector('#addFormCancelBtn').addEventListener('click', _toggleAddForm);
  container.querySelector('#addFormSaveBtn').addEventListener('click', _saveNewTransaction);
  container.querySelector('#addBackdrop').addEventListener('click', _toggleAddForm);

  // Expose toggleAddForm globally for router.js header button
  window._activityToggleAddForm = _toggleAddForm;

  // Filter toggle
  container.querySelector('#filterToggle').addEventListener('click', () => {
    const panel  = container.querySelector('#filterPanel');
    const toggle = container.querySelector('#filterToggle');
    panel.classList.toggle('open');
    toggle.classList.toggle('active');
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

  // Expand button delegation on txContainer
  container.querySelector('#txContainer').addEventListener('click', e => {
    const expandBtn = e.target.closest('.thv-expand-btn');
    if (!expandBtn) return;
    const id = expandBtn.dataset.expandid;
    const panel = container.querySelector(`#actions-panel-${id}`);
    if (!panel) return;
    const isOpen = panel.classList.toggle('open');
    expandBtn.classList.toggle('open', isOpen);
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

// ── Date grouping helpers ─────────────────────────────────

function _dateLabel(isoDate) {
  if (!isoDate) return 'Unknown';
  const d    = new Date(isoDate + 'T00:00:00');
  const now  = new Date();
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const yesterday = new Date(today - 864e5);
  const dDay  = new Date(d.getFullYear(), d.getMonth(), d.getDate());
  if (dDay.getTime() === today.getTime())     return 'Today';
  if (dDay.getTime() === yesterday.getTime()) return 'Yesterday';
  return d.toLocaleDateString('en-PK', { weekday: 'short', day: 'numeric', month: 'short' });
}

function _shortDate(isoStr) {
  if (!isoStr) return '';
  const d = new Date(isoStr);
  return d.toLocaleDateString('en-PK', { day: 'numeric', month: 'short' });
}

function _renderTransactions(txs, total, page, pages) {
  const countEl    = container.querySelector('#txCount');
  const txContainer = container.querySelector('#txContainer');
  const filtered   = _hasActiveFilters() && currentTab === 'all';

  countEl.innerHTML = total > 0
    ? `${fmt(total)} transaction${total !== 1 ? 's' : ''}${filtered ? ' <span class="thv-filtered-tag">· filtered</span>' : ''}`
    : '';

  if (!txs.length) {
    const msg = currentTab === 'pending'
      ? 'No pending transactions — all parsed correctly!'
      : _hasActiveFilters()
        ? 'No transactions match your search. Try clearing filters.'
        : 'No transactions in this period.';
    txContainer.innerHTML = `
      <div class="thv-empty">
        <span class="material-symbols-outlined thv-empty-icon">${currentTab === 'pending' ? 'check_circle' : 'receipt_long'}</span>
        <p>${currentTab === 'pending' ? 'All clear' : 'No results'}</p>
        <p class="thv-empty-sub">${msg}</p>
      </div>`;
    return;
  }

  // Group transactions by date
  const groups = [];
  const groupMap = {};
  txs.forEach(tx => {
    const dateKey = (tx.tx_date || '').slice(0, 10);
    if (!groupMap[dateKey]) {
      groupMap[dateKey] = [];
      groups.push(dateKey);
    }
    groupMap[dateKey].push(tx);
  });

  const groupHtml = groups.map(dateKey => {
    const label = _dateLabel(dateKey);
    const rows  = groupMap[dateKey].map(tx => {
      const isDebit   = tx.tx_type === 'debit';
      const bank      = tx.bank || '';
      const bankCls   = bank === 'SCB' ? 'scb' : bank === 'Meezan' ? 'meezan' : bank === 'Cash' ? 'cash' : 'other';
      const isPending = tx.status === 'pending';
      const cat       = tx.category || 'other';
      const catColor  = CAT_COLORS[cat] || '#64748b';
      const catEmoji  = CAT_EMOJI[cat]  || '📦';
      const catSel    = `<select class="cat-select" data-txid="${tx.id}">${buildCategoryOptions(cat)}</select>`;
      const shortD    = _shortDate(tx.tx_date);

      return `
        <div class="tx-item thv-card" id="txrow-${tx.id}">
          <div class="tx-main thv-card-main">
            <div class="thv-cat-dot ${isDebit ? 'debit' : 'credit'}" style="background:${catColor}1a">
              ${catEmoji}
            </div>
            <div class="tx-info">
              <div class="tx-name">${esc(tx.merchant || 'Unknown')}</div>
              <div class="tx-meta">
                <span class="tx-bank ${bankCls}">${esc(bank)}</span>
                ${isPending ? '<span class="thv-pending-chip">Review</span>' : ''}
                ${catSel}
              </div>
            </div>
            <div class="thv-right">
              <div class="tx-amount ${isDebit ? 'debit' : 'credit'}">${isDebit ? '-' : '+'}₨&nbsp;${Number(tx.amount).toLocaleString()}</div>
              <div class="thv-date-tiny">${shortD}</div>
            </div>
            <button class="thv-expand-btn" data-expandid="${tx.id}" aria-label="Actions">
              <span class="material-symbols-outlined">expand_more</span>
            </button>
          </div>

          <!-- Expandable actions panel -->
          <div class="thv-actions-panel" id="actions-panel-${tx.id}">
            <div class="thv-action-row">
              ${isPending ? `<button class="thv-action-btn approve" data-approveid="${tx.id}">✓ Approve</button>` : ''}
              <button class="thv-action-btn edit-btn" data-editid="${tx.id}">Edit</button>
              <button class="thv-action-btn del-btn" data-delid="${tx.id}">Delete</button>
              ${tx.raw_text ? `<button class="thv-action-btn raw-btn" data-rawid="${tx.id}">Raw</button>` : ''}
            </div>
          </div>

          <!-- Inline edit form -->
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

    return `
      <div class="thv-group">
        <div class="thv-date-sep">${label}</div>
        <div class="thv-list">${rows}</div>
      </div>`;
  }).join('');

  txContainer.innerHTML = `
    ${groupHtml}
    <div class="thv-pagination">
      <span class="thv-pagination-info">Page ${page} of ${pages}</span>
      <div class="thv-pagination-controls">
        <button class="thv-page-btn" data-page="${page-1}" ${page<=1?'disabled':''}>← Prev</button>
        <button class="thv-page-btn" data-page="${page+1}" ${page>=pages?'disabled':''}>Next →</button>
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
  container.querySelector('#addForm').classList.toggle('open');
  container.querySelector('#addBackdrop').classList.toggle('open');
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
  container.querySelector('#addForm').classList.remove('open');
  container.querySelector('#addBackdrop').classList.remove('open');
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
