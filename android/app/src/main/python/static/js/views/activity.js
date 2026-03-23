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
