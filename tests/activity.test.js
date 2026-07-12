/**
 * tests/activity.test.js
 * Functional tests for the Transaction History screen (static/js/views/activity.js)
 */

import { vi, describe, it, expect, beforeEach, afterEach } from 'vitest';

// ── Mock fixtures ─────────────────────────────────────────

const TODAY     = new Date().toISOString().slice(0, 10);
const YESTERDAY = new Date(Date.now() - 864e5).toISOString().slice(0, 10);
const OLDER     = '2026-01-15';

const MOCK_TX_PAGE = (items = [], total = 0) => ({
  items,
  total,
  page: 1,
  pages: 1,
});

const sampleTxs = [
  { id: 1, merchant: 'KFC DHA',     amount: 1_800, tx_type: 'debit',  tx_date: TODAY,     bank: 'SCB',    category: 'dining',    status: 'confirmed', confidence: 'high', source: 'gmail', raw_text: null },
  { id: 2, merchant: 'Total Parco', amount: 5_000, tx_type: 'debit',  tx_date: YESTERDAY, bank: 'Meezan', category: 'fuel',      status: 'confirmed', confidence: 'high', source: 'gmail', raw_text: null },
  { id: 3, merchant: 'Salary CR',   amount: 80_000, tx_type: 'credit', tx_date: YESTERDAY, bank: 'SCB',   category: 'transfer',  status: 'confirmed', confidence: 'high', source: 'gmail', raw_text: null },
  { id: 4, merchant: 'Old Expense', amount: 2_000, tx_type: 'debit',  tx_date: OLDER,     bank: 'Cash',   category: 'groceries', status: 'pending',   confidence: 'low',  source: 'gmail', raw_text: 'raw sms text' },
];

const pendingTxs = [
  { id: 5, merchant: 'Unreviewed',  amount: 500,  tx_type: 'debit', tx_date: TODAY, bank: 'SCB', category: 'other', status: 'pending', confidence: 'low', source: 'gmail', raw_text: null },
];

// ── Mock the API module ───────────────────────────────────

vi.mock('../static/js/api.js', () => ({
  getTransactions:      vi.fn(),
  createTransaction:    vi.fn().mockResolvedValue({}),
  createCashTransaction: vi.fn().mockResolvedValue({}),
  patchTransaction:     vi.fn().mockResolvedValue({}),
  deleteTransaction:    vi.fn().mockResolvedValue({}),
}));

// ── Helpers ───────────────────────────────────────────────

async function mount() {
  vi.resetModules();

  const { getTransactions } = await import('../static/js/api.js');
  // Default: return sample list, no pending badge
  getTransactions.mockImplementation(async (params) => {
    if (params?.status === 'pending') return MOCK_TX_PAGE([], 0);
    return MOCK_TX_PAGE(sampleTxs, sampleTxs.length);
  });

  const el = document.createElement('div');
  document.body.appendChild(el);

  const { init } = await import('../static/js/views/activity.js');
  init(el);

  return { el, getTransactions };
}

const flush = () => new Promise(r => setTimeout(r, 20));

// ── DOM structure tests ───────────────────────────────────

describe('Transaction History — DOM structure', () => {
  let el;

  beforeEach(async () => {
    ({ el } = await mount());
  });

  afterEach(() => {
    el?.remove();
    vi.clearAllMocks();
  });

  it('renders .thv root wrapper', () => {
    expect(el.querySelector('.thv')).not.toBeNull();
  });

  it('renders sticky glass header', () => {
    expect(el.querySelector('.thv-header')).not.toBeNull();
    expect(el.querySelector('.thv-brand')).not.toBeNull();
  });

  it('renders + add button (#thvAddBtn)', () => {
    expect(el.querySelector('#thvAddBtn')).not.toBeNull();
  });

  it('renders tab row with All and Pending tabs', () => {
    const tabs = el.querySelectorAll('.thv-tab');
    expect(tabs.length).toBeGreaterThanOrEqual(2);
    const labels = Array.from(tabs).map(t => t.textContent);
    expect(labels.some(l => l.includes('All'))).toBe(true);
    expect(labels.some(l => l.includes('Pending'))).toBe(true);
  });

  it('"All Transactions" tab is active by default', () => {
    const activeTab = el.querySelector('.thv-tab.active');
    expect(activeTab).not.toBeNull();
    expect(activeTab.dataset.subtab).toBe('all');
  });

  it('renders search input (#searchInput)', () => {
    expect(el.querySelector('#searchInput')).not.toBeNull();
  });

  it('renders filter toggle button (#filterToggle)', () => {
    expect(el.querySelector('#filterToggle')).not.toBeNull();
  });

  it('renders filter panel (#filterPanel) hidden by default', () => {
    const panel = el.querySelector('#filterPanel');
    expect(panel).not.toBeNull();
    expect(panel.classList.contains('open')).toBe(false);
  });

  it('renders all filter inputs: fBank, fCategory, fAmountMin, fAmountMax, fDateFrom, fDateTo', () => {
    ['fBank', 'fCategory', 'fAmountMin', 'fAmountMax', 'fDateFrom', 'fDateTo']
      .forEach(id => expect(el.querySelector(`#${id}`), `#${id} missing`).not.toBeNull());
  });

  it('renders clear filters button (#clearFiltersBtn)', () => {
    expect(el.querySelector('#clearFiltersBtn')).not.toBeNull();
  });

  it('renders transaction count container (#txCount)', () => {
    expect(el.querySelector('#txCount')).not.toBeNull();
  });

  it('renders transaction container (#txContainer)', () => {
    expect(el.querySelector('#txContainer')).not.toBeNull();
  });

  it('renders FAB button (#fab)', () => {
    expect(el.querySelector('#fab')).not.toBeNull();
  });

  it('renders quick-add sheet (#quickAdd)', () => {
    expect(el.querySelector('#quickAdd')).not.toBeNull();
  });

  it('renders quick-add backdrop (#qaBackdrop)', () => {
    expect(el.querySelector('#qaBackdrop')).not.toBeNull();
  });

  it('renders quick-add amount input (#qaAmount)', () => {
    expect(el.querySelector('#qaAmount')).not.toBeNull();
  });

  it('renders quick-add note input (#qaNote)', () => {
    expect(el.querySelector('#qaNote')).not.toBeNull();
  });

  it('renders quick-add date input (#qaDate)', () => {
    expect(el.querySelector('#qaDate')).not.toBeNull();
  });

  it('renders quick-add yesterday button (#qaYesterdayBtn)', () => {
    expect(el.querySelector('#qaYesterdayBtn')).not.toBeNull();
  });

  it('renders quick-add save button (#qaSaveBtn)', () => {
    expect(el.querySelector('#qaSaveBtn')).not.toBeNull();
  });

  it('renders add form sheet (#addForm)', () => {
    expect(el.querySelector('#addForm')).not.toBeNull();
  });

  it('renders add form cancel button (#addFormCancelBtn)', () => {
    expect(el.querySelector('#addFormCancelBtn')).not.toBeNull();
  });

  it('renders add form save button (#addFormSaveBtn)', () => {
    expect(el.querySelector('#addFormSaveBtn')).not.toBeNull();
  });

  it('renders all add-form field inputs', () => {
    ['newMerchant', 'newAmount', 'newType', 'newBank', 'newCategory', 'newDate']
      .forEach(id => expect(el.querySelector(`#${id}`), `#${id} missing`).not.toBeNull());
  });

  it('renders pending badge (#pendingBadge)', () => {
    expect(el.querySelector('#pendingBadge')).not.toBeNull();
  });
});

// ── Data rendering tests ──────────────────────────────────

describe('Transaction History — transaction rendering', () => {
  let el;

  beforeEach(async () => {
    ({ el } = await mount());
    await flush();
  });

  afterEach(() => {
    el?.remove();
    vi.clearAllMocks();
  });

  it('renders transaction rows for each sample transaction', () => {
    const rows = el.querySelectorAll('[id^="txrow-"]');
    expect(rows.length).toBe(sampleTxs.length);
  });

  it('renders merchant name in each row', () => {
    const names = Array.from(el.querySelectorAll('.tx-name')).map(n => n.textContent.trim());
    expect(names).toContain('KFC DHA');
    expect(names).toContain('Total Parco');
    expect(names).toContain('Salary CR');
  });

  it('shows transaction count in #txCount', () => {
    const count = el.querySelector('#txCount').textContent;
    expect(count).toContain('4');
  });

  it('applies .debit class to debit transaction amounts', () => {
    const amounts = el.querySelectorAll('.tx-amount.debit');
    expect(amounts.length).toBeGreaterThan(0);
  });

  it('applies .credit class to credit transaction amounts', () => {
    const amounts = el.querySelectorAll('.tx-amount.credit');
    expect(amounts.length).toBeGreaterThan(0);
  });

  it('renders bank chips with correct bank names', () => {
    const banks = Array.from(el.querySelectorAll('.tx-bank')).map(b => b.textContent.trim());
    expect(banks).toContain('SCB');
    expect(banks).toContain('Meezan');
  });

  it('applies .scb class to SCB bank chips', () => {
    expect(el.querySelector('.tx-bank.scb')).not.toBeNull();
  });

  it('applies .meezan class to Meezan bank chips', () => {
    expect(el.querySelector('.tx-bank.meezan')).not.toBeNull();
  });

  it('renders inline category dropdown for each transaction', () => {
    const selects = el.querySelectorAll('.cat-select');
    expect(selects.length).toBe(sampleTxs.length);
  });

  it('each cat-select has a data-txid attribute', () => {
    el.querySelectorAll('.cat-select').forEach(sel => {
      expect(sel.dataset.txid).toBeTruthy();
    });
  });

  it('renders expand button for each transaction', () => {
    const expanders = el.querySelectorAll('.thv-expand-btn');
    expect(expanders.length).toBe(sampleTxs.length);
  });

  it('renders pending chip for pending transactions', () => {
    expect(el.querySelector('.thv-pending-chip')).not.toBeNull();
  });
});

// ── Date grouping tests ───────────────────────────────────

describe('Transaction History — date grouping', () => {
  let el;

  beforeEach(async () => {
    ({ el } = await mount());
    await flush();
  });

  afterEach(() => {
    el?.remove();
    vi.clearAllMocks();
  });

  it('renders date group headers (.thv-date-sep)', () => {
    const headers = el.querySelectorAll('.thv-date-sep');
    expect(headers.length).toBeGreaterThan(0);
  });

  it('labels today\'s transactions as "Today"', () => {
    const headers = Array.from(el.querySelectorAll('.thv-date-sep')).map(h => h.textContent.trim());
    expect(headers).toContain('Today');
  });

  it('labels yesterday\'s transactions as "Yesterday"', () => {
    const headers = Array.from(el.querySelectorAll('.thv-date-sep')).map(h => h.textContent.trim());
    expect(headers).toContain('Yesterday');
  });

  it('groups same-date transactions under one header', () => {
    // YESTERDAY has 2 transactions — they should share one group
    const headers = Array.from(el.querySelectorAll('.thv-date-sep')).map(h => h.textContent.trim());
    const yesterdayCount = headers.filter(h => h === 'Yesterday').length;
    expect(yesterdayCount).toBe(1);
  });

  it('creates separate groups for different dates', () => {
    const headers = el.querySelectorAll('.thv-date-sep');
    // today, yesterday, OLDER → 3 distinct date groups
    expect(headers.length).toBe(3);
  });
});

// ── Filter panel tests ────────────────────────────────────

describe('Transaction History — filter panel', () => {
  let el;

  beforeEach(async () => {
    ({ el } = await mount());
    await flush();
  });

  afterEach(() => {
    el?.remove();
    vi.clearAllMocks();
  });

  it('filter panel is hidden initially', () => {
    expect(el.querySelector('#filterPanel').classList.contains('open')).toBe(false);
  });

  it('clicking filter toggle shows the filter panel', () => {
    el.querySelector('#filterToggle').click();
    expect(el.querySelector('#filterPanel').classList.contains('open')).toBe(true);
  });

  it('clicking filter toggle again hides the panel', () => {
    el.querySelector('#filterToggle').click();
    el.querySelector('#filterToggle').click();
    expect(el.querySelector('#filterPanel').classList.contains('open')).toBe(false);
  });

  it('#fBank select has "All Banks" as first option', () => {
    const opt = el.querySelector('#fBank option:first-child');
    expect(opt.value).toBe('');
  });

  it('#fCategory select has "All Categories" as first option', () => {
    const opt = el.querySelector('#fCategory option:first-child');
    expect(opt.value).toBe('');
  });

  it('#fCategory includes all 10 categories', () => {
    const opts = el.querySelectorAll('#fCategory option');
    expect(opts.length).toBe(11); // 1 "All" + 10 categories
  });
});

// ── Add form (bottom sheet) tests ─────────────────────────

describe('Transaction History — add form sheet', () => {
  let el;

  beforeEach(async () => {
    ({ el } = await mount());
  });

  afterEach(() => {
    el?.remove();
    vi.clearAllMocks();
  });

  it('add form is closed initially', () => {
    expect(el.querySelector('#addForm').classList.contains('open')).toBe(false);
  });

  it('add backdrop is closed initially', () => {
    expect(el.querySelector('#addBackdrop').classList.contains('open')).toBe(false);
  });

  it('clicking #thvAddBtn opens the add form sheet', () => {
    el.querySelector('#thvAddBtn').click();
    expect(el.querySelector('#addForm').classList.contains('open')).toBe(true);
  });

  it('clicking #thvAddBtn opens the add backdrop', () => {
    el.querySelector('#thvAddBtn').click();
    expect(el.querySelector('#addBackdrop').classList.contains('open')).toBe(true);
  });

  it('clicking cancel button closes the add form', () => {
    el.querySelector('#thvAddBtn').click();
    el.querySelector('#addFormCancelBtn').click();
    expect(el.querySelector('#addForm').classList.contains('open')).toBe(false);
  });

  it('clicking cancel button closes the backdrop', () => {
    el.querySelector('#thvAddBtn').click();
    el.querySelector('#addFormCancelBtn').click();
    expect(el.querySelector('#addBackdrop').classList.contains('open')).toBe(false);
  });

  it('clicking backdrop closes the add form', () => {
    el.querySelector('#thvAddBtn').click();
    el.querySelector('#addBackdrop').click();
    expect(el.querySelector('#addForm').classList.contains('open')).toBe(false);
  });

  it('#newDate defaults to today', () => {
    expect(el.querySelector('#newDate').value).toBe(TODAY);
  });
});

// ── Quick-add sheet tests ─────────────────────────────────

describe('Transaction History — quick-add (cash) sheet', () => {
  let el;

  beforeEach(async () => {
    ({ el } = await mount());
  });

  afterEach(() => {
    el?.remove();
    vi.clearAllMocks();
  });

  it('quick-add is closed initially', () => {
    expect(el.querySelector('#quickAdd').classList.contains('open')).toBe(false);
  });

  it('clicking FAB opens the quick-add sheet', () => {
    el.querySelector('#fab').click();
    expect(el.querySelector('#quickAdd').classList.contains('open')).toBe(true);
  });

  it('clicking FAB opens the backdrop', () => {
    el.querySelector('#fab').click();
    expect(el.querySelector('#qaBackdrop').classList.contains('open')).toBe(true);
  });

  it('clicking close button hides quick-add', () => {
    el.querySelector('#fab').click();
    el.querySelector('#qaCloseBtn').click();
    expect(el.querySelector('#quickAdd').classList.contains('open')).toBe(false);
  });

  it('clicking backdrop hides quick-add', () => {
    el.querySelector('#fab').click();
    el.querySelector('#qaBackdrop').click();
    expect(el.querySelector('#quickAdd').classList.contains('open')).toBe(false);
  });

  it('renders preset amount buttons', () => {
    const presets = el.querySelectorAll('.qa-preset-btn');
    expect(presets.length).toBeGreaterThan(0);
  });

  it('clicking a preset fills #qaAmount', () => {
    el.querySelector('#fab').click();
    const firstPreset = el.querySelector('.qa-preset-btn');
    const expectedAmount = firstPreset.dataset.amount;
    firstPreset.click();
    expect(el.querySelector('#qaAmount').value).toBe(expectedAmount);
  });

  it('renders category buttons for quick-add', () => {
    expect(el.querySelectorAll('.qa-cat-btn').length).toBeGreaterThan(0);
  });

  it('"other" category is active by default', () => {
    expect(el.querySelector('.qa-cat-btn[data-cat="other"].active')).not.toBeNull();
  });

  it('clicking a category button switches active state', () => {
    el.querySelector('.qa-cat-btn[data-cat="dining"]').click();
    expect(el.querySelector('.qa-cat-btn[data-cat="dining"].active')).not.toBeNull();
    expect(el.querySelector('.qa-cat-btn[data-cat="other"].active')).toBeNull();
  });

  it('"Yesterday" button sets qaDate to yesterday', () => {
    el.querySelector('#fab').click();
    el.querySelector('#qaYesterdayBtn').click();
    expect(el.querySelector('#qaDate').value).toBe(YESTERDAY);
  });
});

// ── Pending badge tests ───────────────────────────────────

describe('Transaction History — pending badge', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('hides pending badge when no pending transactions', async () => {
    vi.resetModules();
    const { getTransactions } = await import('../static/js/api.js');
    getTransactions.mockResolvedValue(MOCK_TX_PAGE([], 0));

    const el = document.createElement('div');
    document.body.appendChild(el);
    const { init } = await import('../static/js/views/activity.js');
    init(el);
    await flush();

    expect(el.querySelector('#pendingBadge').style.display).toBe('none');
    el.remove();
  });

  it('shows pending badge when there are pending transactions', async () => {
    vi.resetModules();
    const { getTransactions } = await import('../static/js/api.js');
    getTransactions.mockImplementation(async (params) => {
      if (params?.status === 'pending') return MOCK_TX_PAGE(pendingTxs, 1);
      return MOCK_TX_PAGE(sampleTxs, sampleTxs.length);
    });

    const el = document.createElement('div');
    document.body.appendChild(el);
    const { init } = await import('../static/js/views/activity.js');
    init(el);
    await flush();

    const badge = el.querySelector('#pendingBadge');
    expect(badge.style.display).not.toBe('none');
    expect(badge.textContent).toBe('1');
    el.remove();
  });
});

// ── API interaction tests ─────────────────────────────────

describe('Transaction History — API interactions', () => {
  let el, getTransactions;

  beforeEach(async () => {
    ({ el, getTransactions } = await mount());
    await flush();
  });

  afterEach(() => {
    el?.remove();
    vi.clearAllMocks();
  });

  it('calls getTransactions on init', () => {
    expect(getTransactions).toHaveBeenCalled();
  });

  it('passes period:month as default when no date filters are set', () => {
    const mainCall = getTransactions.mock.calls.find(
      ([p]) => !p.status && !p.date_from
    );
    expect(mainCall).toBeTruthy();
    expect(mainCall[0].period).toBe('month');
  });

  it('passes page:1 on initial load', () => {
    const mainCall = getTransactions.mock.calls.find(([p]) => !p.status);
    expect(mainCall[0].page).toBe(1);
  });

  it('passes page_size:25 on initial load', () => {
    const mainCall = getTransactions.mock.calls.find(([p]) => !p.status);
    expect(mainCall[0].page_size).toBe(25);
  });
});

// ── Empty state tests ─────────────────────────────────────

describe('Transaction History — empty state', () => {
  it('shows empty state when no transactions returned', async () => {
    vi.resetModules();
    const { getTransactions } = await import('../static/js/api.js');
    getTransactions.mockResolvedValue(MOCK_TX_PAGE([], 0));

    const el = document.createElement('div');
    document.body.appendChild(el);
    const { init } = await import('../static/js/views/activity.js');
    init(el);
    await flush();

    expect(el.querySelector('.thv-empty')).not.toBeNull();
    el.remove();
  });

  it('empty state shows receipt_long icon for "all" tab', async () => {
    vi.resetModules();
    const { getTransactions } = await import('../static/js/api.js');
    getTransactions.mockResolvedValue(MOCK_TX_PAGE([], 0));

    const el = document.createElement('div');
    document.body.appendChild(el);
    const { init } = await import('../static/js/views/activity.js');
    init(el);
    await flush();

    const icon = el.querySelector('.thv-empty-icon');
    expect(icon.textContent.trim()).toBe('receipt_long');
    el.remove();
  });
});

// ── Add Cash Entry redesign tests ─────────────────────────

describe('Add Cash Entry — redesigned quick-add sheet', () => {
  let el;

  beforeEach(async () => {
    ({ el } = await mount());
  });

  afterEach(() => {
    el?.remove();
    vi.clearAllMocks();
  });

  it('quick-add sheet has .ace-sheet class', () => {
    expect(el.querySelector('#quickAdd.ace-sheet')).not.toBeNull();
  });

  it('renders drag handle (.ace-drag-handle)', () => {
    expect(el.querySelector('.ace-drag-handle')).not.toBeNull();
  });

  it('renders header with .ace-header', () => {
    expect(el.querySelector('.ace-header')).not.toBeNull();
  });

  it('heading text reads "Add Cash Entry"', () => {
    const heading = el.querySelector('.ace-heading');
    expect(heading).not.toBeNull();
    expect(heading.textContent.trim()).toBe('Add Cash Entry');
  });

  it('close button has .ace-close-btn class', () => {
    expect(el.querySelector('#qaCloseBtn.ace-close-btn')).not.toBeNull();
  });

  it('amount zone has .ace-amount-zone', () => {
    expect(el.querySelector('.ace-amount-zone')).not.toBeNull();
  });

  it('currency label reads "PKR"', () => {
    const label = el.querySelector('.ace-currency-label');
    expect(label).not.toBeNull();
    expect(label.textContent.trim()).toBe('PKR');
  });

  it('#qaAmount has .ace-amount-input class', () => {
    expect(el.querySelector('#qaAmount.ace-amount-input')).not.toBeNull();
  });

  it('renders presets row (.ace-presets-row)', () => {
    expect(el.querySelector('.ace-presets-row')).not.toBeNull();
  });

  it('renders exactly 5 preset buttons', () => {
    expect(el.querySelectorAll('.qa-preset-btn')).toHaveLength(5);
  });

  it('first preset is ₨500', () => {
    const first = el.querySelector('.qa-preset-btn');
    expect(first.dataset.amount).toBe('500');
  });

  it('last preset is ₨10000', () => {
    const presets = el.querySelectorAll('.qa-preset-btn');
    expect(presets[presets.length - 1].dataset.amount).toBe('10000');
  });

  it('preset labels use shorthand k notation for thousands', () => {
    const presets = Array.from(el.querySelectorAll('.qa-preset-btn'));
    const labels = presets.map(p => p.textContent.trim());
    expect(labels.some(l => l.includes('k'))).toBe(true);
  });

  it('renders "Category" section label (.ace-section-label)', () => {
    const label = el.querySelector('.ace-section-label');
    expect(label).not.toBeNull();
    expect(label.textContent.trim()).toBe('Category');
  });

  it('renders category grid (.ace-cat-grid)', () => {
    expect(el.querySelector('.ace-cat-grid')).not.toBeNull();
  });

  it('each category chip has .ace-cat-chip class', () => {
    const chips = el.querySelectorAll('.ace-cat-chip');
    expect(chips.length).toBeGreaterThan(0);
  });

  it('all 10 categories are rendered as .ace-cat-chip', () => {
    expect(el.querySelectorAll('.ace-cat-chip')).toHaveLength(10);
  });

  it('each ace-cat-chip has an .ace-cat-circle inside', () => {
    el.querySelectorAll('.ace-cat-chip').forEach(chip => {
      expect(chip.querySelector('.ace-cat-circle')).not.toBeNull();
    });
  });

  it('each ace-cat-chip has an .ace-cat-label inside', () => {
    el.querySelectorAll('.ace-cat-chip').forEach(chip => {
      expect(chip.querySelector('.ace-cat-label')).not.toBeNull();
    });
  });

  it('ace-cat-circle has inline background color style', () => {
    const circle = el.querySelector('.ace-cat-circle');
    expect(circle.style.background).not.toBe('');
  });

  it('renders footer (.ace-footer)', () => {
    expect(el.querySelector('.ace-footer')).not.toBeNull();
  });

  it('note field has .ace-note-field', () => {
    expect(el.querySelector('.ace-note-field')).not.toBeNull();
  });

  it('#qaNote has .ace-note-input class', () => {
    expect(el.querySelector('#qaNote.ace-note-input')).not.toBeNull();
  });

  it('note field has edit_note icon', () => {
    const icon = el.querySelector('.ace-note-icon');
    expect(icon).not.toBeNull();
    expect(icon.textContent.trim()).toBe('edit_note');
  });

  it('renders date strip (.ace-date-strip)', () => {
    expect(el.querySelector('.ace-date-strip')).not.toBeNull();
  });

  it('#qaDate has .ace-date-input class', () => {
    expect(el.querySelector('#qaDate.ace-date-input')).not.toBeNull();
  });

  it('"Yesterday" chip has .ace-chip-btn class', () => {
    expect(el.querySelector('#qaYesterdayBtn.ace-chip-btn')).not.toBeNull();
  });

  it('submit button has .ace-submit-btn class', () => {
    expect(el.querySelector('#qaSaveBtn.ace-submit-btn')).not.toBeNull();
  });

  it('submit button text contains "Add Entry"', () => {
    const btn = el.querySelector('#qaSaveBtn');
    expect(btn.textContent).toContain('Add Entry');
  });

  it('submit button contains arrow_forward icon', () => {
    const btn = el.querySelector('#qaSaveBtn');
    expect(btn.textContent).toContain('arrow_forward');
  });
});
