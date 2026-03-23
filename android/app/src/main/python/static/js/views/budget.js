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
