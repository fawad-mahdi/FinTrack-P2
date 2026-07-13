/**
 * tests/quickAdd.test.js
 * Functional tests for the shared cash quick-add component
 * (static/js/components/quickAdd.js) — floating "+" button and
 * "Add Cash Entry" sheet, mounted once at the app-shell level.
 *
 * These tests were originally part of activity.test.js and moved here
 * when the sheet was extracted out of the Activity view.
 */

import { vi, describe, it, expect, beforeEach, afterEach } from 'vitest';

const YESTERDAY = new Date(Date.now() - 864e5).toISOString().slice(0, 10);

// ── Mock the API module ───────────────────────────────────

vi.mock('../static/js/api.js', () => ({
  createCashTransaction: vi.fn().mockResolvedValue({}),
}));

// ── Helpers ───────────────────────────────────────────────

async function mountComponent() {
  vi.resetModules();
  document.body.innerHTML = '';  // drop any root left by a previous test
  const qa = await import('../static/js/components/quickAdd.js');
  qa.mount();
  return qa;
}

const $  = sel => document.querySelector(sel);
const $$ = sel => document.querySelectorAll(sel);

// ── DOM structure tests ───────────────────────────────────

describe('Quick-add component — DOM structure', () => {
  beforeEach(async () => {
    await mountComponent();
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it('renders FAB button (#fab)', () => {
    expect($('#fab')).not.toBeNull();
  });

  it('renders quick-add sheet (#quickAdd)', () => {
    expect($('#quickAdd')).not.toBeNull();
  });

  it('renders quick-add backdrop (#qaBackdrop)', () => {
    expect($('#qaBackdrop')).not.toBeNull();
  });

  it('renders quick-add amount input (#qaAmount)', () => {
    expect($('#qaAmount')).not.toBeNull();
  });

  it('renders quick-add note input (#qaNote)', () => {
    expect($('#qaNote')).not.toBeNull();
  });

  it('renders quick-add date input (#qaDate)', () => {
    expect($('#qaDate')).not.toBeNull();
  });

  it('renders quick-add yesterday button (#qaYesterdayBtn)', () => {
    expect($('#qaYesterdayBtn')).not.toBeNull();
  });

  it('renders quick-add save button (#qaSaveBtn)', () => {
    expect($('#qaSaveBtn')).not.toBeNull();
  });

  it('mount() is idempotent — a second call adds no duplicate FAB', async () => {
    const qa = await import('../static/js/components/quickAdd.js');
    qa.mount();
    expect($$('#fab')).toHaveLength(1);
  });

  it('setVisible(false) hides the FAB, setVisible(true) shows it', async () => {
    const qa = await import('../static/js/components/quickAdd.js');
    qa.setVisible(false);
    expect($('#fab').style.display).toBe('none');
    qa.setVisible(true);
    expect($('#fab').style.display).toBe('flex');
  });
});

// ── Sheet behaviour tests ─────────────────────────────────

describe('Quick-add component — sheet behaviour', () => {
  beforeEach(async () => {
    await mountComponent();
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it('quick-add is closed initially', () => {
    expect($('#quickAdd').classList.contains('open')).toBe(false);
  });

  it('clicking FAB opens the quick-add sheet', () => {
    $('#fab').click();
    expect($('#quickAdd').classList.contains('open')).toBe(true);
  });

  it('clicking FAB opens the backdrop', () => {
    $('#fab').click();
    expect($('#qaBackdrop').classList.contains('open')).toBe(true);
  });

  it('clicking close button hides quick-add', () => {
    $('#fab').click();
    $('#qaCloseBtn').click();
    expect($('#quickAdd').classList.contains('open')).toBe(false);
  });

  it('clicking backdrop hides quick-add', () => {
    $('#fab').click();
    $('#qaBackdrop').click();
    expect($('#quickAdd').classList.contains('open')).toBe(false);
  });

  it('renders preset amount buttons', () => {
    expect($$('.qa-preset-btn').length).toBeGreaterThan(0);
  });

  it('clicking a preset fills #qaAmount', () => {
    $('#fab').click();
    const firstPreset = $('.qa-preset-btn');
    const expectedAmount = firstPreset.dataset.amount;
    firstPreset.click();
    expect($('#qaAmount').value).toBe(expectedAmount);
  });

  it('renders category buttons for quick-add', () => {
    expect($$('.qa-cat-btn').length).toBeGreaterThan(0);
  });

  it('"other" category is active by default', () => {
    expect($('.qa-cat-btn[data-cat="other"].active')).not.toBeNull();
  });

  it('clicking a category button switches active state', () => {
    $('.qa-cat-btn[data-cat="dining"]').click();
    expect($('.qa-cat-btn[data-cat="dining"].active')).not.toBeNull();
    expect($('.qa-cat-btn[data-cat="other"].active')).toBeNull();
  });

  it('"Yesterday" button sets qaDate to yesterday', () => {
    $('#fab').click();
    $('#qaYesterdayBtn').click();
    expect($('#qaDate').value).toBe(YESTERDAY);
  });

  it('saving a valid amount calls createCashTransaction and emits fintrack:cash-added', async () => {
    const { createCashTransaction } = await import('../static/js/api.js');
    const onAdded = vi.fn();
    window.addEventListener('fintrack:cash-added', onAdded);

    $('#fab').click();
    $('#qaAmount').value = '750';
    $('#qaSaveBtn').click();
    await new Promise(r => setTimeout(r, 10));

    expect(createCashTransaction).toHaveBeenCalledWith(
      expect.objectContaining({ amount: 750, category: 'other' })
    );
    expect(onAdded).toHaveBeenCalled();
    window.removeEventListener('fintrack:cash-added', onAdded);
  });

  it('saving with an empty amount does not call the API', async () => {
    const { createCashTransaction } = await import('../static/js/api.js');
    $('#fab').click();
    $('#qaSaveBtn').click();
    await new Promise(r => setTimeout(r, 10));
    expect(createCashTransaction).not.toHaveBeenCalled();
  });
});

// ── Add Cash Entry redesign tests ─────────────────────────

describe('Add Cash Entry — redesigned quick-add sheet', () => {
  beforeEach(async () => {
    await mountComponent();
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it('quick-add sheet has .ace-sheet class', () => {
    expect($('#quickAdd.ace-sheet')).not.toBeNull();
  });

  it('renders drag handle (.ace-drag-handle)', () => {
    expect($('.ace-drag-handle')).not.toBeNull();
  });

  it('renders header with .ace-header', () => {
    expect($('.ace-header')).not.toBeNull();
  });

  it('heading text reads "Add Cash Entry"', () => {
    const heading = $('.ace-heading');
    expect(heading).not.toBeNull();
    expect(heading.textContent.trim()).toBe('Add Cash Entry');
  });

  it('close button has .ace-close-btn class', () => {
    expect($('#qaCloseBtn.ace-close-btn')).not.toBeNull();
  });

  it('amount zone has .ace-amount-zone', () => {
    expect($('.ace-amount-zone')).not.toBeNull();
  });

  it('currency label reads "PKR"', () => {
    const label = $('.ace-currency-label');
    expect(label).not.toBeNull();
    expect(label.textContent.trim()).toBe('PKR');
  });

  it('#qaAmount has .ace-amount-input class', () => {
    expect($('#qaAmount.ace-amount-input')).not.toBeNull();
  });

  it('renders presets row (.ace-presets-row)', () => {
    expect($('.ace-presets-row')).not.toBeNull();
  });

  it('renders exactly 5 preset buttons', () => {
    expect($$('.qa-preset-btn')).toHaveLength(5);
  });

  it('first preset is ₨500', () => {
    const first = $('.qa-preset-btn');
    expect(first.dataset.amount).toBe('500');
  });

  it('last preset is ₨10000', () => {
    const presets = $$('.qa-preset-btn');
    expect(presets[presets.length - 1].dataset.amount).toBe('10000');
  });

  it('preset labels use shorthand k notation for thousands', () => {
    const labels = Array.from($$('.qa-preset-btn')).map(p => p.textContent.trim());
    expect(labels.some(l => l.includes('k'))).toBe(true);
  });

  it('renders "Category" section label (.ace-section-label)', () => {
    const label = $('.ace-section-label');
    expect(label).not.toBeNull();
    expect(label.textContent.trim()).toBe('Category');
  });

  it('renders category grid (.ace-cat-grid)', () => {
    expect($('.ace-cat-grid')).not.toBeNull();
  });

  it('each category chip has .ace-cat-chip class', () => {
    expect($$('.ace-cat-chip').length).toBeGreaterThan(0);
  });

  it('all 10 categories are rendered as .ace-cat-chip', () => {
    expect($$('.ace-cat-chip')).toHaveLength(10);
  });

  it('each ace-cat-chip has an .ace-cat-circle inside', () => {
    $$('.ace-cat-chip').forEach(chip => {
      expect(chip.querySelector('.ace-cat-circle')).not.toBeNull();
    });
  });

  it('each ace-cat-chip has an .ace-cat-label inside', () => {
    $$('.ace-cat-chip').forEach(chip => {
      expect(chip.querySelector('.ace-cat-label')).not.toBeNull();
    });
  });

  it('ace-cat-circle has inline background color style', () => {
    const circle = $('.ace-cat-circle');
    expect(circle.style.background).not.toBe('');
  });

  it('renders footer (.ace-footer)', () => {
    expect($('.ace-footer')).not.toBeNull();
  });

  it('note field has .ace-note-field', () => {
    expect($('.ace-note-field')).not.toBeNull();
  });

  it('#qaNote has .ace-note-input class', () => {
    expect($('#qaNote.ace-note-input')).not.toBeNull();
  });

  it('note field has edit_note icon', () => {
    const icon = $('.ace-note-icon');
    expect(icon).not.toBeNull();
    expect(icon.textContent.trim()).toBe('edit_note');
  });

  it('renders date strip (.ace-date-strip)', () => {
    expect($('.ace-date-strip')).not.toBeNull();
  });

  it('#qaDate has .ace-date-input class', () => {
    expect($('#qaDate.ace-date-input')).not.toBeNull();
  });

  it('"Yesterday" chip has .ace-chip-btn class', () => {
    expect($('#qaYesterdayBtn.ace-chip-btn')).not.toBeNull();
  });

  it('submit button has .ace-submit-btn class', () => {
    expect($('#qaSaveBtn.ace-submit-btn')).not.toBeNull();
  });

  it('submit button text contains "Add Entry"', () => {
    expect($('#qaSaveBtn').textContent).toContain('Add Entry');
  });

  it('submit button contains arrow_forward icon', () => {
    expect($('#qaSaveBtn').textContent).toContain('arrow_forward');
  });
});
