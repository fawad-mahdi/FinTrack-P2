/**
 * tests/budget.test.js
 * Functional tests for the Monthly Report screen (static/js/views/budget.js)
 */

import { vi, describe, it, expect, beforeEach, afterEach } from 'vitest';

// ── Mock fixtures ──────────────────────────────────────────

const MOCK_BUDGETS = {
  month: '2026-03',
  days_in_month: 31,
  days_elapsed: 24,
  categories: [
    { category: 'dining',    budget: 25_000, actual: 18_000, remaining:  7_000 },
    { category: 'fuel',      budget: 20_000, actual: 13_500, remaining:  6_500 },
    { category: 'groceries', budget: 15_000, actual:  9_000, remaining:  6_000 },
  ],
};

const MOCK_BUDGETS_OVER = {
  month: '2026-03',
  days_in_month: 31,
  days_elapsed: 24,
  categories: [
    { category: 'dining', budget: 10_000, actual: 18_000, remaining: -8_000 },
  ],
};

const MOCK_BUDGETS_UNBUDGETED = {
  month: '2026-03',
  days_in_month: 31,
  days_elapsed: 24,
  categories: [
    { category: 'dining',    budget: 20_000, actual: 12_000, remaining: 8_000 },
    { category: 'groceries', budget: 0,      actual:  5_000, remaining: 0 },  // unbudgeted
  ],
};

const MOCK_REPORT = {
  total_transactions: 42,
  total_income:       150_000,
  total_expenses:     85_000,
  avg_per_day:        2_833,
  top_merchants: [
    { merchant: 'KFC DHA',     category: 'dining',    total: 8_500, count: 5 },
    { merchant: 'Total Parco', category: 'fuel',      total: 6_000, count: 3 },
    { merchant: 'Imtiaz',      category: 'groceries', total: 5_200, count: 4 },
  ],
  vs_last_month: {
    expenses_change: 5_000, expenses_change_pct: 6,
    income_change: 10_000,  income_change_pct: 7,
    tx_count_change: 4,
  },
  by_source: { gmail: 38, manual: 4 },
};

const MOCK_REPORT_EMPTY = { total_transactions: 0 };

const MOCK_RULES = [
  { merchant: 'kfc',    category: 'dining'    },
  { merchant: 'imtiaz', category: 'groceries' },
];

// ── Mock the API module ─────────────────────────────────────

vi.mock('../static/js/api.js', () => ({
  getBudgets:           vi.fn(),
  upsertBudget:         vi.fn().mockResolvedValue({}),
  suggestBudgets:       vi.fn().mockResolvedValue({}),
  getMonthlyReport:     vi.fn(),
  getCategoryMappings:  vi.fn(),
  addCategoryMapping:   vi.fn().mockResolvedValue({}),
  deleteCategoryMapping: vi.fn().mockResolvedValue({}),
}));

// ── Helpers ─────────────────────────────────────────────────

async function mount(tab = 'budget') {
  vi.resetModules();
  const api = await import('../static/js/api.js');
  api.getBudgets.mockResolvedValue(MOCK_BUDGETS);
  api.getMonthlyReport.mockResolvedValue(MOCK_REPORT);
  api.getCategoryMappings.mockResolvedValue(MOCK_RULES);

  const el = document.createElement('div');
  document.body.appendChild(el);
  const { init } = await import('../static/js/views/budget.js');
  init(el);

  // Switch to requested tab if not budget
  if (tab !== 'budget') {
    el.querySelector(`[data-subtab="${tab}"]`).click();
  }

  return { el, api };
}

const flush = () => new Promise(r => setTimeout(r, 20));

// ── Shell structure ─────────────────────────────────────────

describe('Monthly Report — shell structure', () => {
  let el;

  beforeEach(async () => {
    ({ el } = await mount());
  });
  afterEach(() => { el?.remove(); vi.clearAllMocks(); });

  it('renders .mr root wrapper', () => {
    expect(el.querySelector('.mr')).not.toBeNull();
  });

  it('renders glass header (.mr-header)', () => {
    expect(el.querySelector('.mr-header')).not.toBeNull();
  });

  it('header brand reads "Reports"', () => {
    expect(el.querySelector('.mr-brand').textContent.trim()).toBe('Reports');
  });

  it('renders lock icon in header', () => {
    expect(el.querySelector('.mr-lock-icon')).not.toBeNull();
  });

  it('renders 3 sub-tabs', () => {
    expect(el.querySelectorAll('.mr-tab')).toHaveLength(3);
  });

  it('"Budget" tab is active by default', () => {
    const active = el.querySelector('.mr-tab.active');
    expect(active).not.toBeNull();
    expect(active.dataset.subtab).toBe('budget');
  });

  it('renders #budgetContent container', () => {
    expect(el.querySelector('#budgetContent')).not.toBeNull();
  });

  it('clicking "Monthly" tab switches active state', () => {
    el.querySelector('[data-subtab="report"]').click();
    expect(el.querySelector('.mr-tab.active').dataset.subtab).toBe('report');
  });

  it('clicking "Rules" tab switches active state', () => {
    el.querySelector('[data-subtab="rules"]').click();
    expect(el.querySelector('.mr-tab.active').dataset.subtab).toBe('rules');
  });

  it('only one tab is active at a time', () => {
    el.querySelector('[data-subtab="report"]').click();
    el.querySelector('[data-subtab="rules"]').click();
    expect(el.querySelectorAll('.mr-tab.active')).toHaveLength(1);
  });
});

// ── Budget sub-view ─────────────────────────────────────────

describe('Monthly Report — budget sub-view DOM', () => {
  let el;

  beforeEach(async () => {
    ({ el } = await mount('budget'));
    await flush();
  });
  afterEach(() => { el?.remove(); vi.clearAllMocks(); });

  it('renders month label', () => {
    const lbl = el.querySelector('.mr-budget-month-label');
    expect(lbl).not.toBeNull();
    expect(lbl.textContent).toContain('2026');
  });

  it('renders day progress sub-label', () => {
    const sub = el.querySelector('.mr-budget-day-sub');
    expect(sub).not.toBeNull();
    expect(sub.textContent).toContain('Day 24');
    expect(sub.textContent).toContain('31');
  });

  it('renders Suggest button (#suggestBudgetBtn)', () => {
    expect(el.querySelector('#suggestBudgetBtn')).not.toBeNull();
  });

  it('renders 3 stat tiles (.mr-stat-card)', () => {
    expect(el.querySelectorAll('.mr-stat-card')).toHaveLength(3);
  });

  it('stat tiles show Budget, Spent, Remaining labels', () => {
    const labels = Array.from(el.querySelectorAll('.mr-stat-lbl')).map(l => l.textContent.trim());
    expect(labels).toContain('Budget');
    expect(labels).toContain('Spent');
    expect(labels).toContain('Remaining');
  });

  it('renders overall progress bar (.mr-overall-track)', () => {
    expect(el.querySelector('.mr-overall-track')).not.toBeNull();
    expect(el.querySelector('.mr-overall-fill')).not.toBeNull();
  });

  it('overall progress fill has non-empty width', () => {
    expect(el.querySelector('.mr-overall-fill').style.width).not.toBe('');
  });

  it('renders "Categories" section label', () => {
    const labels = Array.from(el.querySelectorAll('.mr-section-label')).map(l => l.textContent.trim());
    expect(labels).toContain('Categories');
  });

  it('renders one .mr-brow per budgeted category', () => {
    expect(el.querySelectorAll('.mr-brow')).toHaveLength(3);
  });

  it('each .mr-brow shows emoji and label', () => {
    el.querySelectorAll('.mr-brow').forEach(row => {
      expect(row.querySelector('.mr-brow-emoji')).not.toBeNull();
      expect(row.querySelector('.mr-brow-label')).not.toBeNull();
    });
  });

  it('each budgeted .mr-brow has a progress bar', () => {
    el.querySelectorAll('.mr-brow').forEach(row => {
      expect(row.querySelector('.mr-brow-track')).not.toBeNull();
    });
  });

  it('each budget amount is click-to-edit (.budget-amount-val)', () => {
    expect(el.querySelectorAll('.budget-amount-val').length).toBeGreaterThan(0);
  });

  it('budget input is hidden by default', () => {
    el.querySelectorAll('.budget-input').forEach(inp => {
      expect(inp.style.display).toBe('none');
    });
  });

  it('clicking budget-amount-val reveals the input', () => {
    const span = el.querySelector('.budget-amount-val');
    const cat  = span.dataset.editbudget;
    span.click();
    expect(el.querySelector(`#bi-${cat}`).style.display).not.toBe('none');
  });
});

// ── Budget over limit ───────────────────────────────────────

describe('Monthly Report — budget over limit', () => {
  let el;

  beforeEach(async () => {
    vi.resetModules();
    const api = await import('../static/js/api.js');
    api.getBudgets.mockResolvedValue(MOCK_BUDGETS_OVER);
    api.getMonthlyReport.mockResolvedValue(MOCK_REPORT);
    api.getCategoryMappings.mockResolvedValue([]);

    el = document.createElement('div');
    document.body.appendChild(el);
    const { init } = await import('../static/js/views/budget.js');
    init(el);
    await flush();
  });
  afterEach(() => { el?.remove(); vi.clearAllMocks(); });

  it('shows "Over" chip when remaining < 0', () => {
    expect(el.querySelector('.mr-over-chip')).not.toBeNull();
  });

  it('spent amount is highlighted in red-ish color', () => {
    const spent = el.querySelector('.mr-brow-spent');
    expect(spent.style.color).toBe('rgb(255, 180, 171)');
  });
});

// ── Unbudgeted categories ───────────────────────────────────

describe('Monthly Report — unbudgeted categories', () => {
  let el;

  beforeEach(async () => {
    vi.resetModules();
    const api = await import('../static/js/api.js');
    api.getBudgets.mockResolvedValue(MOCK_BUDGETS_UNBUDGETED);
    api.getMonthlyReport.mockResolvedValue(MOCK_REPORT);
    api.getCategoryMappings.mockResolvedValue([]);

    el = document.createElement('div');
    document.body.appendChild(el);
    const { init } = await import('../static/js/views/budget.js');
    init(el);
    await flush();
  });
  afterEach(() => { el?.remove(); vi.clearAllMocks(); });

  it('renders #unbudgetedHdr toggle button', () => {
    expect(el.querySelector('#unbudgetedHdr')).not.toBeNull();
  });

  it('unbudgeted list is hidden by default', () => {
    expect(el.querySelector('#unbudgetedList').style.display).toBe('none');
  });

  it('clicking #unbudgetedHdr reveals the list', () => {
    el.querySelector('#unbudgetedHdr').click();
    expect(el.querySelector('#unbudgetedList').style.display).toBe('block');
  });

  it('clicking #unbudgetedHdr again hides the list', () => {
    el.querySelector('#unbudgetedHdr').click();
    el.querySelector('#unbudgetedHdr').click();
    expect(el.querySelector('#unbudgetedList').style.display).toBe('none');
  });
});

// ── Report sub-view ─────────────────────────────────────────

describe('Monthly Report — report sub-view DOM', () => {
  let el;

  beforeEach(async () => {
    ({ el } = await mount('report'));
    await flush();
  });
  afterEach(() => { el?.remove(); vi.clearAllMocks(); });

  it('renders month navigation (.mr-month-nav)', () => {
    expect(el.querySelector('.mr-month-nav')).not.toBeNull();
  });

  it('renders #reportPrev and #reportNext buttons', () => {
    expect(el.querySelector('#reportPrev')).not.toBeNull();
    expect(el.querySelector('#reportNext')).not.toBeNull();
  });

  it('#reportNext is disabled for current month', () => {
    expect(el.querySelector('#reportNext').disabled).toBe(true);
  });

  it('renders month chip (.mr-month-chip)', () => {
    expect(el.querySelector('.mr-month-chip')).not.toBeNull();
  });

  it('renders 4 report cards (.mr-rcard)', () => {
    expect(el.querySelectorAll('.mr-rcard')).toHaveLength(4);
  });

  it('income card shows income amount', () => {
    const incomeCard = el.querySelector('.mr-rcard-income');
    expect(incomeCard.querySelector('.mr-rcard-val').textContent).toContain('150');
  });

  it('expenses card shows expenses amount', () => {
    const expCard = el.querySelector('.mr-rcard-expenses');
    expect(expCard.querySelector('.mr-rcard-val').textContent).toContain('85');
  });

  it('net savings card shows positive net', () => {
    const netCard = el.querySelector('.mr-rcard-net-pos');
    expect(netCard).not.toBeNull();
    expect(netCard.querySelector('.mr-rcard-val').textContent).toContain('65');
  });

  it('transactions card shows count', () => {
    const txCard = el.querySelector('.mr-rcard-txns');
    expect(txCard.querySelector('.mr-rcard-val').textContent.trim()).toBe('42');
  });

  it('renders daily average strip (.mr-avg-strip)', () => {
    expect(el.querySelector('.mr-avg-strip')).not.toBeNull();
    expect(el.querySelector('.mr-avg-val')).not.toBeNull();
  });

  it('renders "Top Merchants" section', () => {
    const labels = Array.from(el.querySelectorAll('.mr-section-label')).map(l => l.textContent.trim());
    expect(labels).toContain('Top Merchants');
  });

  it('renders one .mr-merchant-row per top merchant', () => {
    expect(el.querySelectorAll('.mr-merchant-row')).toHaveLength(3);
  });

  it('merchant rows show merchant name', () => {
    const names = Array.from(el.querySelectorAll('.mr-merchant-name')).map(n => n.textContent.trim());
    expect(names).toContain('KFC DHA');
    expect(names).toContain('Total Parco');
  });

  it('merchant rows have rank numbers', () => {
    const ranks = Array.from(el.querySelectorAll('.mr-merchant-rank')).map(r => r.textContent.trim());
    expect(ranks).toContain('1');
    expect(ranks).toContain('2');
    expect(ranks).toContain('3');
  });

  it('renders "vs Last Month" section', () => {
    const labels = Array.from(el.querySelectorAll('.mr-section-label')).map(l => l.textContent.trim());
    expect(labels).toContain('vs Last Month');
  });

  it('renders 3 .mr-vs-item cells', () => {
    expect(el.querySelectorAll('.mr-vs-item')).toHaveLength(3);
  });

  it('renders expenses vs last month with .worse class when expenses increased', () => {
    // MOCK_REPORT has expenses_change: 5000 (positive = worse)
    const expItem = el.querySelector('.mr-vs-item:first-child .mr-vs-delta');
    expect(expItem.classList.contains('worse')).toBe(true);
  });

  it('renders income vs last month with .better class when income increased', () => {
    const items = el.querySelectorAll('.mr-vs-item');
    const incomeItem = items[1].querySelector('.mr-vs-delta');
    expect(incomeItem.classList.contains('better')).toBe(true);
  });

  it('renders "By Source" section with source pills', () => {
    expect(el.querySelector('.mr-source-row')).not.toBeNull();
    expect(el.querySelectorAll('.mr-source-pill').length).toBeGreaterThan(0);
  });

  it('source pill shows "gmail" source', () => {
    const names = Array.from(el.querySelectorAll('.mr-source-name')).map(n => n.textContent.trim());
    expect(names).toContain('gmail');
  });

  it('renders Smart Insights card (.mr-insights-card)', () => {
    expect(el.querySelector('.mr-insights-card')).not.toBeNull();
  });

  it('Smart Insights badge reads "Coming Soon"', () => {
    expect(el.querySelector('.mr-insights-badge').textContent.trim()).toBe('Coming Soon');
  });

  it('Smart Insights has Download and View Analytics buttons', () => {
    const btns = Array.from(el.querySelectorAll('.mr-insights-btn')).map(b => b.textContent);
    expect(btns.some(t => t.includes('Download'))).toBe(true);
    expect(btns.some(t => t.includes('Analytics'))).toBe(true);
  });
});

// ── Report empty state ──────────────────────────────────────

describe('Monthly Report — report empty state', () => {
  let el;

  beforeEach(async () => {
    vi.resetModules();
    const api = await import('../static/js/api.js');
    api.getBudgets.mockResolvedValue(MOCK_BUDGETS);
    api.getMonthlyReport.mockResolvedValue(MOCK_REPORT_EMPTY);
    api.getCategoryMappings.mockResolvedValue([]);

    el = document.createElement('div');
    document.body.appendChild(el);
    const { init } = await import('../static/js/views/budget.js');
    init(el);
    el.querySelector('[data-subtab="report"]').click();
    await flush();
  });
  afterEach(() => { el?.remove(); vi.clearAllMocks(); });

  it('shows empty state when no transactions', () => {
    expect(el.querySelector('.mr-empty')).not.toBeNull();
  });

  it('does not render report cards in empty state', () => {
    expect(el.querySelector('.mr-report-cards')).toBeNull();
  });
});

// ── Report month navigation ─────────────────────────────────

describe('Monthly Report — month navigation', () => {
  let el, api;

  beforeEach(async () => {
    ({ el, api } = await mount('report'));
    await flush();
    vi.clearAllMocks();
  });
  afterEach(() => { el?.remove(); vi.clearAllMocks(); });

  it('clicking #reportPrev calls getMonthlyReport with prior month', async () => {
    el.querySelector('#reportPrev').click();
    await flush();
    expect(api.getMonthlyReport).toHaveBeenCalled();
    const calledWith = api.getMonthlyReport.mock.calls[0][0];
    expect(calledWith).toMatch(/^\d{4}-\d{2}$/);
  });

  it('prev month arg is earlier than current month', async () => {
    const currentMonth = new Date().toISOString().slice(0, 7);
    el.querySelector('#reportPrev').click();
    await flush();
    const calledWith = api.getMonthlyReport.mock.calls[0][0];
    expect(calledWith < currentMonth).toBe(true);
  });
});

// ── Rules sub-view ──────────────────────────────────────────

describe('Monthly Report — rules sub-view', () => {
  let el;

  beforeEach(async () => {
    ({ el } = await mount('rules'));
    await flush();
  });
  afterEach(() => { el?.remove(); vi.clearAllMocks(); });

  it('renders rules description (.mr-rules-desc)', () => {
    expect(el.querySelector('.mr-rules-desc')).not.toBeNull();
  });

  it('renders add rule form (.mr-rules-form)', () => {
    expect(el.querySelector('.mr-rules-form')).not.toBeNull();
  });

  it('renders merchant input (#rMerchant)', () => {
    expect(el.querySelector('#rMerchant')).not.toBeNull();
  });

  it('renders category select (#rCategory)', () => {
    expect(el.querySelector('#rCategory')).not.toBeNull();
  });

  it('#rCategory has options for all categories', () => {
    const opts = el.querySelectorAll('#rCategory option');
    expect(opts.length).toBe(10);
  });

  it('renders add rule button (#addRuleBtn)', () => {
    expect(el.querySelector('#addRuleBtn')).not.toBeNull();
  });

  it('renders rules list (.mr-rules-list)', () => {
    expect(el.querySelector('.mr-rules-list')).not.toBeNull();
  });

  it('renders one .mr-rule-row per rule', () => {
    expect(el.querySelectorAll('.mr-rule-row')).toHaveLength(2);
  });

  it('each rule row shows merchant name', () => {
    const merchants = Array.from(el.querySelectorAll('.mr-rule-merchant')).map(m => m.textContent.trim());
    expect(merchants).toContain('kfc');
    expect(merchants).toContain('imtiaz');
  });

  it('each rule row shows category chip', () => {
    expect(el.querySelectorAll('.mr-rule-cat-chip').length).toBe(2);
  });

  it('each rule row has a delete button', () => {
    expect(el.querySelectorAll('.mr-rule-del').length).toBe(2);
  });

  it('arrow icon is present between merchant and category', () => {
    expect(el.querySelectorAll('.mr-rule-arrow').length).toBe(2);
  });
});

// ── Rules empty state ───────────────────────────────────────

describe('Monthly Report — rules empty state', () => {
  let el;

  beforeEach(async () => {
    vi.resetModules();
    const api = await import('../static/js/api.js');
    api.getBudgets.mockResolvedValue(MOCK_BUDGETS);
    api.getMonthlyReport.mockResolvedValue(MOCK_REPORT);
    api.getCategoryMappings.mockResolvedValue([]);

    el = document.createElement('div');
    document.body.appendChild(el);
    const { init } = await import('../static/js/views/budget.js');
    init(el);
    el.querySelector('[data-subtab="rules"]').click();
    await flush();
  });
  afterEach(() => { el?.remove(); vi.clearAllMocks(); });

  it('shows empty state when no rules', () => {
    expect(el.querySelector('.mr-empty')).not.toBeNull();
  });

  it('does not render .mr-rules-list in empty state', () => {
    expect(el.querySelector('.mr-rules-list')).toBeNull();
  });
});

// ── API calls ───────────────────────────────────────────────

describe('Monthly Report — API call routing', () => {
  let el, api;

  afterEach(() => { el?.remove(); vi.clearAllMocks(); });

  it('calls getBudgets on init (budget tab default)', async () => {
    ({ el, api } = await mount('budget'));
    await flush();
    expect(api.getBudgets).toHaveBeenCalled();
  });

  it('calls getMonthlyReport when report tab clicked', async () => {
    ({ el, api } = await mount('budget'));
    await flush();
    vi.clearAllMocks();
    el.querySelector('[data-subtab="report"]').click();
    await flush();
    expect(api.getMonthlyReport).toHaveBeenCalled();
  });

  it('calls getCategoryMappings when rules tab clicked', async () => {
    ({ el, api } = await mount('budget'));
    await flush();
    vi.clearAllMocks();
    el.querySelector('[data-subtab="rules"]').click();
    await flush();
    expect(api.getCategoryMappings).toHaveBeenCalled();
  });

  it('getBudgets is called with YYYY-MM formatted month', async () => {
    ({ el, api } = await mount('budget'));
    await flush();
    expect(api.getBudgets.mock.calls[0][0]).toMatch(/^\d{4}-\d{2}$/);
  });

  it('getMonthlyReport is called with YYYY-MM formatted month', async () => {
    ({ el, api } = await mount('report'));
    await flush();
    expect(api.getMonthlyReport.mock.calls[0][0]).toMatch(/^\d{4}-\d{2}$/);
  });
});
