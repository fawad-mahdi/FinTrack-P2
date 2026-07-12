/**
 * tests/dashboard.test.js
 * Functional tests for the Spending Dashboard screen (static/js/views/dashboard.js)
 */

import { vi, describe, it, expect, beforeEach, afterEach } from 'vitest';

// ── API mock fixtures ─────────────────────────────────────

const MOCK_SUMMARY = { income: 80_000, expenses: 45_000, net: 35_000 };
const MOCK_SUMMARY_NEGATIVE = { income: 20_000, expenses: 60_000, net: -40_000 };
const MOCK_CATEGORIES = [
  { category: 'dining',    total: 18_000, percentage: 40 },
  { category: 'fuel',      total: 13_500, percentage: 30 },
  { category: 'groceries', total:  9_000, percentage: 20 },
  { category: 'utilities', total:  4_500, percentage: 10 },
];
const MOCK_TRANSACTIONS = {
  items: [
    { id: 1, merchant: 'KFC DHA',     amount: 1_800, tx_type: 'debit',  tx_date: '2026-03-24', category: 'dining'    },
    { id: 2, merchant: 'Total Parco', amount: 5_000, tx_type: 'debit',  tx_date: '2026-03-23', category: 'fuel'      },
    { id: 3, merchant: 'Salary',      amount: 80_000, tx_type: 'credit', tx_date: '2026-03-01', category: 'transfer' },
  ],
};
const MOCK_BUDGETS = {
  categories: [
    { category: 'dining',    budget: 25_000, actual: 18_000, remaining:  7_000 },
    { category: 'fuel',      budget: 20_000, actual: 13_500, remaining:  6_500 },
    { category: 'groceries', budget: 15_000, actual:  9_000, remaining:  6_000 },
  ],
};
const MOCK_BUDGETS_OVER = {
  categories: [
    { category: 'dining', budget: 10_000, actual: 18_000, remaining: -8_000 },
  ],
};

// ── Mock the API module ───────────────────────────────────

vi.mock('../static/js/api.js', () => ({
  getSummary:          vi.fn(),
  getCategorySummary:  vi.fn(),
  getTransactions:     vi.fn(),
  getBudgets:          vi.fn(),
}));

// ── Helpers ───────────────────────────────────────────────

/** Mount a fresh dashboard into a new div and return { el, mocks }. */
async function mount(summaryData = MOCK_SUMMARY) {
  vi.resetModules();

  const { getSummary, getCategorySummary, getTransactions, getBudgets } =
    await import('../static/js/api.js');

  getSummary.mockResolvedValue(summaryData);
  getCategorySummary.mockResolvedValue(MOCK_CATEGORIES);
  getTransactions.mockResolvedValue(MOCK_TRANSACTIONS);
  getBudgets.mockResolvedValue(MOCK_BUDGETS);

  const el = document.createElement('div');
  document.body.appendChild(el);

  const { init } = await import('../static/js/views/dashboard.js');
  init(el);

  return { el, getSummary, getCategorySummary, getTransactions, getBudgets };
}

/** Flush all pending microtasks + a tick so async loaders resolve. */
const flush = () => new Promise(r => setTimeout(r, 10));

// ── Test suites ───────────────────────────────────────────

describe('Spending Dashboard — DOM structure', () => {
  let el;

  beforeEach(async () => {
    ({ el } = await mount());
  });

  afterEach(() => {
    el?.remove();
    vi.clearAllMocks();
  });

  it('renders the .dv2 root wrapper', () => {
    expect(el.querySelector('.dv2')).not.toBeNull();
  });

  it('renders the FinTrack PK brand header', () => {
    const brand = el.querySelector('.dv2-brand');
    expect(brand).not.toBeNull();
    expect(brand.textContent).toContain('FinTrack');
    expect(brand.textContent).toContain('PK');
  });

  it('renders exactly 4 period pills', () => {
    expect(el.querySelectorAll('.dv2-pill')).toHaveLength(4);
  });

  it('"This Month" pill is active by default', () => {
    const activePill = el.querySelector('.dv2-pill.active');
    expect(activePill).not.toBeNull();
    expect(activePill.dataset.period).toBe('month');
  });

  it('renders the hero balance card with net/income/expense slots', () => {
    expect(el.querySelector('#dvNet')).not.toBeNull();
    expect(el.querySelector('#dvIncome')).not.toBeNull();
    expect(el.querySelector('#dvExpenses')).not.toBeNull();
  });

  it('renders Total Spent and Budget metric tiles', () => {
    expect(el.querySelector('#dvSpent')).not.toBeNull();
    expect(el.querySelector('#dvBudget')).not.toBeNull();
    expect(el.querySelector('#dvBudgetBar')).not.toBeNull();
  });

  it('renders the category breakdown container', () => {
    expect(el.querySelector('#dvCats')).not.toBeNull();
    expect(el.querySelector('#dvCatTotal')).not.toBeNull();
  });

  it('renders the recent transactions container', () => {
    expect(el.querySelector('#dvRecentTx')).not.toBeNull();
    expect(el.querySelector('#dvSeeAll')).not.toBeNull();
  });

  it('renders the Savings Goals card', () => {
    expect(el.querySelector('#dvGoalsCard')).not.toBeNull();
    expect(el.querySelector('.dv2-goals-badge').textContent).toContain('Coming Soon');
  });

  it('renders the modal backdrop (hidden by default)', () => {
    const backdrop = el.querySelector('#dvModalBackdrop');
    expect(backdrop).not.toBeNull();
    expect(backdrop.classList.contains('open')).toBe(false);
  });
});

describe('Spending Dashboard — API calls on init', () => {
  let el, getSummary, getCategorySummary, getTransactions, getBudgets;

  beforeEach(async () => {
    ({ el, getSummary, getCategorySummary, getTransactions, getBudgets } = await mount());
    await flush();
  });

  afterEach(() => {
    el?.remove();
    vi.clearAllMocks();
  });

  it('calls getSummary with period:month on init', () => {
    expect(getSummary).toHaveBeenCalledWith({ period: 'month' });
  });

  it('calls getCategorySummary with period:month on init', () => {
    expect(getCategorySummary).toHaveBeenCalledWith({ period: 'month' });
  });

  it('calls getTransactions to populate recent list', () => {
    expect(getTransactions).toHaveBeenCalled();
  });

  it('calls getBudgets for current month', () => {
    expect(getBudgets).toHaveBeenCalled();
    const [monthArg] = getBudgets.mock.calls[0];
    expect(monthArg).toMatch(/^\d{4}-\d{2}$/);
  });
});

describe('Spending Dashboard — data rendering', () => {
  let el;

  beforeEach(async () => {
    ({ el } = await mount());
    await flush();
  });

  afterEach(() => {
    el?.remove();
    vi.clearAllMocks();
  });

  it('displays positive net balance in #dvNet', () => {
    const netEl = el.querySelector('#dvNet');
    expect(netEl.textContent).toContain('35');   // ₨ 35,000
    expect(netEl.querySelector('.net-pos')).not.toBeNull();
  });

  it('displays income amount in #dvIncome', () => {
    expect(el.querySelector('#dvIncome').textContent).toContain('80');
  });

  it('displays expenses amount in #dvExpenses', () => {
    expect(el.querySelector('#dvExpenses').textContent).toContain('45');
  });

  it('populates category rows from getCategorySummary', () => {
    const rows = el.querySelectorAll('.dv2-cat-row');
    expect(rows.length).toBe(4);
  });

  it('shows total spend in #dvCatTotal', () => {
    expect(el.querySelector('#dvCatTotal').textContent).toContain('45');
  });

  it('renders category row with correct label', () => {
    const names = Array.from(el.querySelectorAll('.dv2-cat-name')).map(n => n.textContent.trim());
    expect(names).toContain('Dining');
    expect(names).toContain('Fuel');
  });

  it('renders recent transaction merchant names', () => {
    const txRows = el.querySelectorAll('.dv2-tx-row');
    expect(txRows.length).toBeGreaterThan(0);
    const names = Array.from(el.querySelectorAll('.dv2-tx-name')).map(n => n.textContent.trim());
    expect(names).toContain('KFC DHA');
  });

  it('budget tile shows "left" when under budget', () => {
    expect(el.querySelector('#dvBudget').textContent).toContain('left');
  });

  it('budget bar width is set > 0%', () => {
    const bar = el.querySelector('#dvBudgetBar');
    expect(bar.style.width).not.toBe('0%');
    expect(bar.style.width).not.toBe('');
  });
});

describe('Spending Dashboard — negative net balance', () => {
  let el;

  beforeEach(async () => {
    ({ el } = await mount(MOCK_SUMMARY_NEGATIVE));
    await flush();
  });

  afterEach(() => {
    el?.remove();
    vi.clearAllMocks();
  });

  it('uses .net-neg class when net is negative', () => {
    expect(el.querySelector('#dvNet .net-neg')).not.toBeNull();
  });

  it('shows minus sign in net display', () => {
    expect(el.querySelector('#dvNet').textContent).toContain('−');
  });
});

describe('Spending Dashboard — budget over limit', () => {
  let el;

  beforeEach(async () => {
    vi.resetModules();
    const { getSummary, getCategorySummary, getTransactions, getBudgets } =
      await import('../static/js/api.js');
    getSummary.mockResolvedValue(MOCK_SUMMARY);
    getCategorySummary.mockResolvedValue(MOCK_CATEGORIES);
    getTransactions.mockResolvedValue(MOCK_TRANSACTIONS);
    getBudgets.mockResolvedValue(MOCK_BUDGETS_OVER);

    el = document.createElement('div');
    document.body.appendChild(el);
    const { init } = await import('../static/js/views/dashboard.js');
    init(el);
    await flush();
  });

  afterEach(() => {
    el?.remove();
    vi.clearAllMocks();
  });

  it('shows "over" when actual spend exceeds budget', () => {
    expect(el.querySelector('#dvBudget').textContent).toContain('over');
  });

  it('budget bar turns red when over 90%', () => {
    const bar = el.querySelector('#dvBudgetBar');
    // jsdom normalises hex colours to rgb() when reading .style
    expect(bar.style.background).toMatch(/rgb\(239,\s*68,\s*68\)|#ef4444/);
  });
});

describe('Spending Dashboard — period switching', () => {
  let el, getSummary, getCategorySummary;

  beforeEach(async () => {
    ({ el, getSummary, getCategorySummary } = await mount());
    await flush();
    vi.clearAllMocks(); // clear init calls, only track subsequent calls
  });

  afterEach(() => {
    el?.remove();
    vi.clearAllMocks();
  });

  it('clicking "30 Days" pill marks it active', () => {
    const pill = el.querySelector('.dv2-pill[data-period="30d"]');
    pill.click();
    expect(pill.classList.contains('active')).toBe(true);
  });

  it('clicking "30 Days" deactivates "This Month"', () => {
    el.querySelector('.dv2-pill[data-period="30d"]').click();
    expect(el.querySelector('.dv2-pill[data-period="month"]').classList.contains('active')).toBe(false);
  });

  it('clicking "30 Days" re-calls getSummary with period:30d', () => {
    el.querySelector('.dv2-pill[data-period="30d"]').click();
    expect(getSummary).toHaveBeenCalledWith({ period: '30d' });
  });

  it('clicking "3 Months" re-calls getCategorySummary with period:3m', () => {
    el.querySelector('.dv2-pill[data-period="3m"]').click();
    expect(getCategorySummary).toHaveBeenCalledWith({ period: '3m' });
  });

  it('clicking "All Time" marks only that pill active', () => {
    el.querySelector('.dv2-pill[data-period="all"]').click();
    const activePills = el.querySelectorAll('.dv2-pill.active');
    expect(activePills).toHaveLength(1);
    expect(activePills[0].dataset.period).toBe('all');
  });

  it('only one pill is active at a time after multiple clicks', () => {
    el.querySelector('.dv2-pill[data-period="30d"]').click();
    el.querySelector('.dv2-pill[data-period="3m"]').click();
    el.querySelector('.dv2-pill[data-period="all"]').click();
    expect(el.querySelectorAll('.dv2-pill.active')).toHaveLength(1);
    expect(el.querySelector('.dv2-pill.active').dataset.period).toBe('all');
  });
});

describe('Spending Dashboard — Savings Goals modal', () => {
  let el;

  beforeEach(async () => {
    ({ el } = await mount());
  });

  afterEach(() => {
    el?.remove();
    vi.clearAllMocks();
  });

  it('modal is closed initially', () => {
    expect(el.querySelector('#dvModalBackdrop').classList.contains('open')).toBe(false);
  });

  it('clicking goals card opens modal', () => {
    el.querySelector('#dvGoalsCard').click();
    expect(el.querySelector('#dvModalBackdrop').classList.contains('open')).toBe(true);
  });

  it('"Got It" button closes modal', () => {
    el.querySelector('#dvGoalsCard').click();
    el.querySelector('#dvModalClose').click();
    expect(el.querySelector('#dvModalBackdrop').classList.contains('open')).toBe(false);
  });

  it('clicking backdrop closes modal', () => {
    el.querySelector('#dvGoalsCard').click();
    el.querySelector('#dvModalBackdrop').click();
    expect(el.querySelector('#dvModalBackdrop').classList.contains('open')).toBe(false);
  });

  it('modal contains "Not Yet Enabled" title', () => {
    expect(el.querySelector('.dv2-modal-title').textContent).toContain('Not Yet Enabled');
  });
});

describe('Spending Dashboard — API failure graceful handling', () => {
  let el;

  beforeEach(async () => {
    vi.resetModules();
    const { getSummary, getCategorySummary, getTransactions, getBudgets } =
      await import('../static/js/api.js');
    getSummary.mockRejectedValue(new Error('Network error'));
    getCategorySummary.mockRejectedValue(new Error('Network error'));
    getTransactions.mockRejectedValue(new Error('Network error'));
    getBudgets.mockRejectedValue(new Error('Network error'));

    el = document.createElement('div');
    document.body.appendChild(el);
    const { init } = await import('../static/js/views/dashboard.js');
    init(el);
    await flush();
  });

  afterEach(() => {
    el?.remove();
    vi.clearAllMocks();
  });

  it('does not throw — page still renders on API failure', () => {
    expect(el.querySelector('.dv2')).not.toBeNull();
  });

  it('net slot still exists even after failed API call', () => {
    expect(el.querySelector('#dvNet')).not.toBeNull();
  });

  it('shows empty state for category section on failure', () => {
    expect(el.querySelector('#dvCats')).not.toBeNull();
  });
});
