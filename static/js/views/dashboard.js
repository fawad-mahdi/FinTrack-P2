// static/js/views/dashboard.js
import { getSummary, getCategorySummary, getTransactions, getBudgets } from '../api.js';
import { fmt, esc, CAT_COLORS, CAT_LABELS, CAT_EMOJI } from '../utils/constants.js';

let currentPeriod = 'month';
let container;

export function init(el) {
  container = el;
  _render();
}

// Refresh dashboard stats after a cash entry is added via the shared FAB
// (mounted at the app-shell level; see components/quickAdd.js). Registered
// once at module load — `container` guards against firing before this view
// has been initialized.
window.addEventListener('fintrack:cash-added', () => {
  if (container) _loadAll();
});

export function destroy() {}

// ── Render shell ─────────────────────────────────────────

function _render() {
  container.innerHTML = `
    <div class="dv2">

      <!-- Header -->
      <div class="dv2-header">
        <div class="dv2-brand">FinTrack <span>PK</span></div>
        <span class="dv2-lock material-symbols-outlined">lock</span>
      </div>

      <!-- Period Pills -->
      <div class="dv2-periods" id="dvPeriods">
        <button class="dv2-pill ${currentPeriod==='month'?'active':''}" data-period="month">This Month</button>
        <button class="dv2-pill ${currentPeriod==='30d'?'active':''}" data-period="30d">30 Days</button>
        <button class="dv2-pill ${currentPeriod==='3m'?'active':''}" data-period="3m">3 Months</button>
        <button class="dv2-pill ${currentPeriod==='all'?'active':''}" data-period="all">All Time</button>
      </div>

      <!-- Hero Balance Card -->
      <div class="dv2-hero">
        <div class="dv2-hero-label">Net Balance</div>
        <div class="dv2-hero-amount" id="dvNet"><span class="dv2-pulse">—</span></div>
        <div class="dv2-hero-row">
          <div class="dv2-hero-stat">
            <span class="dv2-hero-stat-icon income-icon material-symbols-outlined">arrow_downward</span>
            <div>
              <div class="dv2-hero-stat-label">Income</div>
              <div class="dv2-hero-stat-val income-val" id="dvIncome">—</div>
            </div>
          </div>
          <div class="dv2-hero-divider"></div>
          <div class="dv2-hero-stat">
            <span class="dv2-hero-stat-icon expense-icon material-symbols-outlined">arrow_upward</span>
            <div>
              <div class="dv2-hero-stat-label">Expenses</div>
              <div class="dv2-hero-stat-val expense-val" id="dvExpenses">—</div>
            </div>
          </div>
        </div>
      </div>

      <!-- Metric Tiles -->
      <div class="dv2-tiles">
        <div class="dv2-tile">
          <div class="dv2-tile-icon">
            <span class="material-symbols-outlined">payments</span>
          </div>
          <div class="dv2-tile-body">
            <div class="dv2-tile-label">Total Spent</div>
            <div class="dv2-tile-val" id="dvSpent">—</div>
          </div>
        </div>
        <div class="dv2-tile">
          <div class="dv2-tile-icon budget-tile-icon">
            <span class="material-symbols-outlined">account_balance_wallet</span>
          </div>
          <div class="dv2-tile-body">
            <div class="dv2-tile-label">Budget · <span class="dv2-tile-label-sub">This Month</span></div>
            <div class="dv2-tile-val" id="dvBudget">—</div>
          </div>
          <div class="dv2-tile-bar-wrap">
            <div class="dv2-tile-bar" id="dvBudgetBar" style="width:0%"></div>
          </div>
        </div>
      </div>

      <!-- Spending by Category -->
      <div class="dv2-section">
        <div class="dv2-section-hdr">
          <span class="dv2-section-title">Spending by Category</span>
          <span class="dv2-section-sub" id="dvCatTotal"></span>
        </div>
        <div id="dvCats" class="dv2-cats">
          ${_skeletonRows(3)}
        </div>
      </div>

      <!-- Recent Transactions -->
      <div class="dv2-section">
        <div class="dv2-section-hdr">
          <span class="dv2-section-title">Recent Transactions</span>
          <button class="dv2-see-all" id="dvSeeAll">See All</button>
        </div>
        <div id="dvRecentTx" class="dv2-recent-list">
          ${_skeletonRows(3)}
        </div>
      </div>

      <!-- Savings Goals (not yet enabled) -->
      <div class="dv2-goals-card" id="dvGoalsCard">
        <div class="dv2-goals-left">
          <div class="dv2-goals-icon">
            <span class="material-symbols-outlined">savings</span>
          </div>
          <div>
            <div class="dv2-goals-title">Savings Goals</div>
            <div class="dv2-goals-sub">Track your financial targets</div>
          </div>
        </div>
        <span class="dv2-goals-badge">Coming Soon</span>
      </div>

    </div>

    <!-- "Not Yet Enabled" Modal -->
    <div class="dv2-modal-backdrop" id="dvModalBackdrop">
      <div class="dv2-modal">
        <div class="dv2-modal-icon">
          <span class="material-symbols-outlined">construction</span>
        </div>
        <h3 class="dv2-modal-title">Not Yet Enabled</h3>
        <p class="dv2-modal-body">Savings Goals tracking is coming in a future update. You'll be able to set targets, milestones, and track progress right here on the dashboard.</p>
        <button class="dv2-modal-btn" id="dvModalClose">Got It</button>
      </div>
    </div>`;

  // Period pill wiring
  container.querySelector('#dvPeriods').addEventListener('click', e => {
    const btn = e.target.closest('.dv2-pill');
    if (!btn) return;
    currentPeriod = btn.dataset.period;
    container.querySelectorAll('.dv2-pill').forEach(b => b.classList.remove('active'));
    btn.classList.add('active');
    _loadSummary();
    _loadCategories();
  });

  // See All → navigate to activity
  container.querySelector('#dvSeeAll').addEventListener('click', () => {
    document.querySelector('.bottom-nav-btn[data-view="activity"]')?.click();
  });

  // Savings Goals → modal
  container.querySelector('#dvGoalsCard').addEventListener('click', () => {
    container.querySelector('#dvModalBackdrop').classList.add('open');
  });
  container.querySelector('#dvModalClose').addEventListener('click', _closeModal);
  container.querySelector('#dvModalBackdrop').addEventListener('click', e => {
    if (e.target === e.currentTarget) _closeModal();
  });

  _loadAll();
}

function _closeModal() {
  container.querySelector('#dvModalBackdrop').classList.remove('open');
}

function _skeletonRows(n) {
  return Array.from({ length: n }, () => '<div class="dv2-skel-row"></div>').join('');
}

// ── Data loaders ─────────────────────────────────────────

function _loadAll() {
  const today = new Date();
  const month = `${today.getFullYear()}-${String(today.getMonth()+1).padStart(2,'0')}`;
  _loadSummary();
  _loadCategories();
  _loadRecent();
  _loadBudget(month);
}

async function _loadSummary() {
  try {
    const d = await getSummary({ period: currentPeriod });
    const net = d.net ?? 0;
    container.querySelector('#dvNet').innerHTML =
      `<span class="${net >= 0 ? 'net-pos' : 'net-neg'}">${net >= 0 ? '+' : '−'}₨ ${fmt(Math.abs(net))}</span>`;
    container.querySelector('#dvIncome').textContent   = '₨ ' + fmt(d.income ?? 0);
    container.querySelector('#dvExpenses').textContent = '₨ ' + fmt(d.expenses ?? 0);
    container.querySelector('#dvSpent').textContent    = '₨ ' + fmt(d.expenses ?? 0);
  } catch {
    container.querySelector('#dvNet').textContent = '—';
  }
}

async function _loadCategories() {
  const catsEl = container.querySelector('#dvCats');
  try {
    const data = await getCategorySummary({ period: currentPeriod });
    if (!data.length) {
      catsEl.innerHTML = '<div class="dv2-empty">No spending data for this period</div>';
      container.querySelector('#dvCatTotal').textContent = '';
      return;
    }
    const grand = data.reduce((s, r) => s + r.total, 0);
    container.querySelector('#dvCatTotal').textContent = '₨ ' + fmt(grand);
    catsEl.innerHTML = data.slice(0, 6).map(r => {
      const color = CAT_COLORS[r.category] || CAT_COLORS.other;
      const label = CAT_LABELS[r.category] || r.category;
      const emoji = CAT_EMOJI[r.category]  || '📦';
      return `
        <div class="dv2-cat-row">
          <div class="dv2-cat-icon" style="background:${color}1a">${emoji}</div>
          <div class="dv2-cat-info">
            <div class="dv2-cat-top">
              <span class="dv2-cat-name">${esc(label)}</span>
              <span class="dv2-cat-amount">₨ ${fmt(r.total)}</span>
            </div>
            <div class="dv2-cat-track">
              <div class="dv2-cat-fill" style="width:${r.percentage}%;background:${color}"></div>
            </div>
          </div>
          <span class="dv2-cat-pct">${r.percentage}%</span>
        </div>`;
    }).join('');
  } catch {
    catsEl.innerHTML = '<div class="dv2-empty">Could not load categories</div>';
  }
}

async function _loadRecent() {
  const listEl = container.querySelector('#dvRecentTx');
  try {
    const data = await getTransactions({ page_size: 5, page: 1, status: 'confirmed' });
    const txs = Array.isArray(data) ? data : (data.items || []);
    if (!txs.length) {
      listEl.innerHTML = '<div class="dv2-empty" style="padding:20px 16px">No transactions yet</div>';
      return;
    }
    listEl.innerHTML = txs.slice(0, 5).map(tx => {
      const isDebit = tx.tx_type === 'debit';
      const amount  = Math.abs(tx.amount ?? 0);
      const emoji   = CAT_EMOJI[tx.category] || '📦';
      const name    = tx.merchant || 'Transaction';
      const dateStr = tx.tx_date
        ? new Date(tx.tx_date.slice(0, 10) + 'T00:00:00').toLocaleDateString('en-PK', { day:'numeric', month:'short' })
        : '';
      return `
        <div class="dv2-tx-row">
          <div class="dv2-tx-icon ${isDebit ? 'debit' : 'credit'}">${emoji}</div>
          <div class="dv2-tx-info">
            <div class="dv2-tx-name">${esc(name)}</div>
            <div class="dv2-tx-date">${dateStr}</div>
          </div>
          <div class="dv2-tx-amount ${isDebit ? 'debit' : 'credit'}">${isDebit ? '−' : '+'}₨ ${fmt(amount)}</div>
        </div>`;
    }).join('');
  } catch {
    listEl.innerHTML = '<div class="dv2-empty" style="padding:20px 16px">Could not load transactions</div>';
  }
}

async function _loadBudget(month) {
  try {
    const data     = await getBudgets(month);
    const cats     = data.categories || [];
    const total    = cats.reduce((s, c) => s + (c.budget  || 0), 0);
    const spent    = cats.reduce((s, c) => s + (c.actual  || 0), 0);
    if (!total) {
      container.querySelector('#dvBudget').textContent = 'Not set';
      return;
    }
    const remaining = total - spent;
    const pct       = Math.min(100, Math.round((spent / total) * 100));
    container.querySelector('#dvBudget').textContent = remaining >= 0
      ? '₨ ' + fmt(remaining) + ' left'
      : '₨ ' + fmt(Math.abs(remaining)) + ' over';
    const bar = container.querySelector('#dvBudgetBar');
    if (bar) {
      bar.style.width      = pct + '%';
      bar.style.background = pct >= 90 ? '#ef4444' : pct >= 70 ? '#f59e0b' : '#4EDEA3';
    }
  } catch {
    container.querySelector('#dvBudget').textContent = 'Not set';
  }
}
