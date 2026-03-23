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
