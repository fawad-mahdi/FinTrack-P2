// static/js/components/quickAdd.js
// Floating "+" button and cash-entry sheet, mounted once at the app-shell
// level so Dashboard and Activity share one instance instead of duplicating
// the sheet's markup and save logic per view.
import { createCashTransaction } from '../api.js';
import { CATEGORIES, CAT_LABELS, CAT_EMOJI, CAT_COLORS, isoDate } from '../utils/constants.js';

let qaCategory = 'other';
let root;

export function mount() {
  if (root) return;
  root = document.createElement('div');
  root.innerHTML = `
    <button class="fab" id="fab" title="Log cash expense">+</button>

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

    </div>`;
  document.body.appendChild(root);
  _bindEvents();
}

// Show/hide the FAB depending on which view is active (the sheet itself
// stays in the DOM either way — only the trigger button toggles).
export function setVisible(visible) {
  const fab = root?.querySelector('#fab');
  if (fab) fab.style.display = visible ? 'flex' : 'none';
}

function _bindEvents() {
  root.querySelector('#fab').addEventListener('click', _open);
  root.querySelector('#qaBackdrop').addEventListener('click', _close);
  root.querySelector('#qaCloseBtn').addEventListener('click', _close);
  root.querySelector('#qaYesterdayBtn').addEventListener('click', () => {
    root.querySelector('#qaDate').value = isoDate(new Date(Date.now() - 864e5));
  });
  root.querySelectorAll('.qa-preset-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      root.querySelector('#qaAmount').value = btn.dataset.amount;
      root.querySelector('#qaAmount').focus();
    });
  });
  root.querySelectorAll('.qa-cat-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      root.querySelectorAll('.qa-cat-btn').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      qaCategory = btn.dataset.cat;
    });
  });
  root.querySelector('#qaSaveBtn').addEventListener('click', _save);
  root.querySelector('#qaAmount').addEventListener('keydown', e => {
    if (e.key === 'Enter') _save();
  });
  document.addEventListener('keydown', e => {
    if (e.key === 'Escape') _close();
  });
}

function _open() {
  root.querySelector('#qaDate').value   = isoDate(new Date());
  root.querySelector('#qaAmount').value = '';
  root.querySelector('#qaNote').value   = '';
  qaCategory = 'other';
  root.querySelectorAll('.qa-cat-btn').forEach(b => b.classList.remove('active'));
  root.querySelector('.qa-cat-btn[data-cat="other"]').classList.add('active');
  root.querySelector('#quickAdd').classList.add('open');
  root.querySelector('#qaBackdrop').classList.add('open');
  setTimeout(() => root.querySelector('#qaAmount').focus(), 320);
}

function _close() {
  root.querySelector('#quickAdd').classList.remove('open');
  root.querySelector('#qaBackdrop').classList.remove('open');
}

async function _save() {
  const amount = parseFloat(root.querySelector('#qaAmount').value);
  if (!amount || amount <= 0) {
    const inp = root.querySelector('#qaAmount');
    inp.style.borderColor = 'var(--red)';
    setTimeout(() => { inp.style.borderColor = ''; }, 900);
    inp.focus(); return;
  }
  await createCashTransaction({
    amount, category: qaCategory,
    note: root.querySelector('#qaNote').value.trim(),
    date: root.querySelector('#qaDate').value,
  });
  root.querySelector('#qaAmount').value = '';
  root.querySelector('#qaNote').value   = '';
  root.querySelector('#qaAmount').focus();
  window.dispatchEvent(new CustomEvent('fintrack:cash-added'));
}
