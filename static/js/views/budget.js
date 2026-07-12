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
    <div class="mr">

      <!-- Glass sticky header -->
      <div class="mr-header">
        <span class="mr-brand">Reports</span>
        <span class="material-symbols-outlined mr-lock-icon">lock</span>
      </div>

      <!-- Sub-tab pills -->
      <div class="mr-tabs-row">
        <div class="mr-tab active" data-subtab="budget">Budget</div>
        <div class="mr-tab" data-subtab="report">Monthly</div>
        <div class="mr-tab" data-subtab="rules">Rules</div>
      </div>

      <!-- Dynamic content -->
      <div id="budgetContent"></div>

    </div>`;

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
  content.innerHTML = `<div class="mr-loading"><span class="material-symbols-outlined mr-spin">autorenew</span></div>`;
  try {
    renderBudget(await getBudgets(currentBudgetMonth));
  } catch {
    content.innerHTML = `<div class="mr-error"><span class="material-symbols-outlined">error_outline</span><p>Could not load budget.</p></div>`;
  }
}

function budgetColor(pct) {
  if (pct === null) return '#7b8ba5';
  if (pct >= 100)   return '#ef4444';
  if (pct >= 70)    return '#f59e0b';
  return '#4EDEA3';
}

function renderBudget(data) {
  const { month, days_in_month, days_elapsed, categories } = data;
  const showProj = days_elapsed >= 7 && days_elapsed < days_in_month;
  let totalBudget = 0, totalActual = 0;
  categories.forEach(c => { totalBudget += c.budget; totalActual += c.actual; });
  const totalRem   = totalBudget - totalActual;
  const totalPct   = totalBudget > 0 ? Math.round(totalActual / totalBudget * 100) : null;
  const budgeted   = categories.filter(c => c.budget > 0);
  const unbudgeted = categories.filter(c => c.budget === 0 && c.actual > 0);

  function bRow(c, isUnb = false) {
    const displayPct = c.budget > 0 ? Math.round(c.actual / c.budget * 100) : null;
    const barPct     = c.budget > 0 ? Math.min(displayPct, 100) : 0;
    const color      = budgetColor(displayPct);
    const proj       = (showProj && c.actual > 0) ? Math.round(c.actual / days_elapsed * days_in_month) : null;
    const emoji      = CAT_EMOJI[c.category] || '📦';
    const label      = CAT_LABELS[c.category] || c.category;
    const isOver     = c.remaining < 0;
    const budgetEdit = isUnb
      ? `<span class="budget-amount-val" data-editbudget="${c.category}" data-val="0" style="color:#7b8ba5">Set budget</span>
         <input class="budget-input" id="bi-${c.category}" type="number" value="0" style="display:none">`
      : `<span class="mr-edit-prefix">₨</span><span class="budget-amount-val" data-editbudget="${c.category}" data-val="${c.budget}">${fmt(c.budget)}</span>
         <input class="budget-input" id="bi-${c.category}" type="number" value="${c.budget}" style="display:none">`;
    return `
      <div class="mr-brow">
        <div class="mr-brow-top">
          <div class="mr-brow-cat">
            <span class="mr-brow-emoji">${emoji}</span>
            <span class="mr-brow-label">${esc(label)}</span>
            ${isOver && !isUnb ? '<span class="mr-over-chip">Over</span>' : ''}
          </div>
          <div class="mr-brow-right">
            <div class="mr-brow-spent" style="color:${isOver ? '#ffb4ab' : '#e5e2e1'}">₨${fmt(c.actual)}</div>
            <div class="mr-brow-budget-wrap${isUnb ? ' mr-setbudget' : ''}">${budgetEdit}</div>
          </div>
        </div>
        ${!isUnb ? `
        <div class="mr-brow-bar-row">
          <div class="mr-brow-track">
            <div class="mr-brow-fill" style="width:${barPct}%;background:${color}"></div>
          </div>
          <span class="mr-brow-pct" style="color:${color}">${displayPct !== null ? displayPct + '%' : '—'}</span>
        </div>` : ''}
        ${proj !== null ? `<div class="mr-brow-proj">Projected ₨${fmt(proj)} by month end</div>` : ''}
      </div>`;
  }

  const overallPct   = totalPct !== null ? Math.min(totalPct, 100) : 0;
  const overallColor = budgetColor(totalPct);

  container.querySelector('#budgetContent').innerHTML = `
    <div class="mr-budget">

      <!-- Month header -->
      <div class="mr-budget-month-hdr">
        <div>
          <div class="mr-budget-month-label">${_fmtMonthLabel(month)}</div>
          <div class="mr-budget-day-sub">Day ${days_elapsed} of ${days_in_month}</div>
        </div>
        <button class="mr-suggest-btn" id="suggestBudgetBtn">
          <span class="material-symbols-outlined">lightbulb</span>
          Suggest
        </button>
      </div>

      <!-- Stat tiles -->
      <div class="mr-stat-grid">
        <div class="mr-stat-card">
          <div class="mr-stat-icon" style="color:#4EDEA3"><span class="material-symbols-outlined">savings</span></div>
          <div class="mr-stat-val">${totalBudget > 0 ? '₨' + fmt(totalBudget) : '—'}</div>
          <div class="mr-stat-lbl">Budget</div>
        </div>
        <div class="mr-stat-card">
          <div class="mr-stat-icon" style="color:#ffb4ab"><span class="material-symbols-outlined">payments</span></div>
          <div class="mr-stat-val" style="color:#ffb4ab">₨${fmt(totalActual)}</div>
          <div class="mr-stat-lbl">Spent</div>
        </div>
        <div class="mr-stat-card">
          <div class="mr-stat-icon" style="color:${totalRem >= 0 ? '#4EDEA3' : '#ef4444'}">
            <span class="material-symbols-outlined">account_balance_wallet</span>
          </div>
          <div class="mr-stat-val" style="color:${totalRem >= 0 ? '#4EDEA3' : '#ef4444'}">${totalRem >= 0 ? '' : '−'}₨${fmt(Math.abs(totalRem))}</div>
          <div class="mr-stat-lbl">Remaining</div>
        </div>
      </div>

      <!-- Overall progress bar -->
      <div class="mr-overall-bar-wrap">
        <div class="mr-overall-track">
          <div class="mr-overall-fill" style="width:${overallPct}%;background:${overallColor}"></div>
        </div>
        <span class="mr-overall-pct" style="color:${overallColor}">${totalPct !== null ? totalPct + '%' : '—'}</span>
      </div>

      ${budgeted.length || unbudgeted.length ? `
        <div class="mr-section-label">Categories</div>
        <div class="mr-brows">${budgeted.map(c => bRow(c)).join('')}</div>
        ${unbudgeted.length ? `
          <button class="mr-unbudgeted-toggle" id="unbudgetedHdr">
            <span class="material-symbols-outlined mr-toggle-icon">expand_more</span>
            Unbudgeted (${unbudgeted.length})
          </button>
          <div id="unbudgetedList" class="mr-brows" style="display:none">
            ${unbudgeted.map(c => bRow(c, true)).join('')}
          </div>` : ''}
      ` : '<div class="mr-empty-inline">No spending this month.</div>'}

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
    const icon = hdr.querySelector('.mr-toggle-icon');
    const open = list.style.display === 'block';
    list.style.display   = open ? 'none' : 'block';
    icon.style.transform = open ? '' : 'rotate(180deg)';
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
  content.innerHTML = `<div class="mr-loading"><span class="material-symbols-outlined mr-spin">autorenew</span></div>`;
  try {
    const d = await getMonthlyReport(currentReportMonth);
    _renderReport(d);
  } catch {
    content.innerHTML = `<div class="mr-error"><span class="material-symbols-outlined">error_outline</span><p>Could not load report.</p></div>`;
  }
}

function _renderReport(d) {
  const prev = _prevMonth(currentReportMonth);
  const next = _nextMonth(currentReportMonth);
  const isCurrentMonth = currentReportMonth === new Date().toISOString().slice(0,7);
  const net = d ? (d.total_income - d.total_expenses) : 0;

  container.querySelector('#budgetContent').innerHTML = `
    <div class="mr-report">

      <!-- Month navigation -->
      <div class="mr-month-nav">
        <button class="mr-nav-btn" id="reportPrev">
          <span class="material-symbols-outlined">chevron_left</span>
        </button>
        <span class="mr-month-chip">${_fmtMonthLabel(currentReportMonth)}</span>
        <button class="mr-nav-btn" id="reportNext" ${isCurrentMonth ? 'disabled' : ''}>
          <span class="material-symbols-outlined">chevron_right</span>
        </button>
      </div>

      ${!d || d.total_transactions === 0 ? `
        <div class="mr-empty">
          <span class="material-symbols-outlined mr-empty-icon">bar_chart</span>
          <p>No data for this month.</p>
        </div>
      ` : `
        <!-- 4 summary stat cards -->
        <div class="mr-report-cards">
          <div class="mr-rcard mr-rcard-income">
            <span class="material-symbols-outlined mr-rcard-icon">trending_up</span>
            <div class="mr-rcard-val">₨${fmt(d.total_income)}</div>
            <div class="mr-rcard-lbl">Income</div>
          </div>
          <div class="mr-rcard mr-rcard-expenses">
            <span class="material-symbols-outlined mr-rcard-icon">payments</span>
            <div class="mr-rcard-val">₨${fmt(d.total_expenses)}</div>
            <div class="mr-rcard-lbl">Expenses</div>
          </div>
          <div class="mr-rcard ${net >= 0 ? 'mr-rcard-net-pos' : 'mr-rcard-net-neg'}">
            <span class="material-symbols-outlined mr-rcard-icon">account_balance_wallet</span>
            <div class="mr-rcard-val">${net >= 0 ? '' : '−'}₨${fmt(Math.abs(net))}</div>
            <div class="mr-rcard-lbl">Net Savings</div>
          </div>
          <div class="mr-rcard mr-rcard-txns">
            <span class="material-symbols-outlined mr-rcard-icon">receipt_long</span>
            <div class="mr-rcard-val">${d.total_transactions}</div>
            <div class="mr-rcard-lbl">Transactions</div>
          </div>
        </div>

        <!-- Daily average strip -->
        <div class="mr-avg-strip">
          <span class="material-symbols-outlined mr-avg-icon">calendar_today</span>
          <span class="mr-avg-label">Daily avg spend</span>
          <span class="mr-avg-val">₨${fmt(d.avg_per_day)}</span>
        </div>

        ${d.top_merchants?.length ? `
          <div class="mr-section-label">Top Merchants</div>
          <div class="mr-merchants">
            ${d.top_merchants.map((m, i) => `
              <div class="mr-merchant-row">
                <span class="mr-merchant-rank">${i + 1}</span>
                <div class="mr-merchant-info">
                  <div class="mr-merchant-name">${esc(m.merchant)}</div>
                  <div class="mr-merchant-cat">${CAT_LABELS[m.category] || m.category}</div>
                </div>
                <div class="mr-merchant-right">
                  <div class="mr-merchant-amount">₨${fmt(m.total)}</div>
                  <div class="mr-merchant-txns">${m.count} txn${m.count !== 1 ? 's' : ''}</div>
                </div>
              </div>`).join('')}
          </div>` : ''}

        ${d.vs_last_month ? `
          <div class="mr-section-label">vs Last Month</div>
          <div class="mr-vs-grid">
            <div class="mr-vs-item">
              <div class="mr-vs-label">Expenses</div>
              <div class="mr-vs-delta ${d.vs_last_month.expenses_change >= 0 ? 'worse' : 'better'}">
                <span class="material-symbols-outlined">${d.vs_last_month.expenses_change >= 0 ? 'trending_up' : 'trending_down'}</span>
                ${d.vs_last_month.expenses_change >= 0 ? '+' : ''}${d.vs_last_month.expenses_change_pct}%
              </div>
            </div>
            <div class="mr-vs-item">
              <div class="mr-vs-label">Income</div>
              <div class="mr-vs-delta ${d.vs_last_month.income_change >= 0 ? 'better' : 'worse'}">
                <span class="material-symbols-outlined">${d.vs_last_month.income_change >= 0 ? 'trending_up' : 'trending_down'}</span>
                ${d.vs_last_month.income_change >= 0 ? '+' : ''}${d.vs_last_month.income_change_pct}%
              </div>
            </div>
            <div class="mr-vs-item">
              <div class="mr-vs-label">Transactions</div>
              <div class="mr-vs-delta neutral">
                ${d.vs_last_month.tx_count_change >= 0 ? '+' : ''}${d.vs_last_month.tx_count_change}
              </div>
            </div>
          </div>` : ''}

        ${d.by_source ? `
          <div class="mr-section-label">By Source</div>
          <div class="mr-source-row">
            ${Object.entries(d.by_source).map(([src, count]) =>
              `<div class="mr-source-pill"><span class="mr-source-name">${esc(src)}</span><span class="mr-source-count">${count}</span></div>`
            ).join('')}
          </div>` : ''}

        <!-- Smart Insights placeholder (UI only) -->
        <div class="mr-insights-card">
          <div class="mr-insights-hdr">
            <div class="mr-insights-title">
              <span class="material-symbols-outlined">auto_awesome</span>
              Smart Insights
            </div>
            <span class="mr-insights-badge">Coming Soon</span>
          </div>
          <p class="mr-insights-body">AI-powered spending analysis, anomaly detection, and personalised savings recommendations will appear here once enabled.</p>
          <div class="mr-insights-actions">
            <button class="mr-insights-btn" onclick="alert('Coming soon — not yet enabled.')">
              <span class="material-symbols-outlined">download</span>
              Download Report
            </button>
            <button class="mr-insights-btn" onclick="alert('Coming soon — not yet enabled.')">
              <span class="material-symbols-outlined">bar_chart</span>
              View Analytics
            </button>
          </div>
        </div>
      `}
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
  content.innerHTML = `<div class="mr-loading"><span class="material-symbols-outlined mr-spin">autorenew</span></div>`;
  try {
    _renderRules(await getCategoryMappings());
  } catch {
    content.innerHTML = `<div class="mr-error"><span class="material-symbols-outlined">error_outline</span><p>Could not load rules.</p></div>`;
  }
}

function _renderRules(rules) {
  container.querySelector('#budgetContent').innerHTML = `
    <div class="mr-rules">
      <p class="mr-rules-desc">Rules auto-categorise merchants on every future sync, overriding keyword detection.</p>

      <div class="mr-rules-form">
        <input type="text" id="rMerchant" class="mr-rules-input" placeholder="Merchant name (e.g. imtiaz)">
        <select id="rCategory" class="mr-rules-select">${buildCategoryOptions()}</select>
        <button class="mr-rules-add-btn" id="addRuleBtn">
          <span class="material-symbols-outlined">add</span>
        </button>
      </div>

      ${!rules.length ? `
        <div class="mr-empty">
          <span class="material-symbols-outlined mr-empty-icon">rule</span>
          <p>No rules yet.</p>
          <p class="mr-empty-sub">Add a rule above to auto-apply categories on every sync.</p>
        </div>
      ` : `
        <div class="mr-section-label">Active Rules</div>
        <div class="mr-rules-list">
          ${rules.map(r => `
            <div class="mr-rule-row">
              <div class="mr-rule-merchant">${esc(r.merchant)}</div>
              <span class="material-symbols-outlined mr-rule-arrow">arrow_forward</span>
              <span class="mr-rule-cat-chip">${CAT_LABELS[r.category] || r.category}</span>
              <button class="mr-rule-del" data-delmerchant="${esc(r.merchant)}">
                <span class="material-symbols-outlined">close</span>
              </button>
            </div>`).join('')}
        </div>
      `}
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
