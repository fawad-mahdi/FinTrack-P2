// static/js/utils/constants.js

export const CATEGORIES = [
  'groceries','fuel','dining','shopping','utilities',
  'transfer','atm','medical','education','other'
];

export const CAT_LABELS = {
  groceries:'Groceries', fuel:'Fuel', dining:'Dining', shopping:'Shopping',
  utilities:'Utilities', transfer:'Transfer', atm:'ATM', medical:'Medical',
  education:'Education', other:'Other',
};

export const CAT_COLORS = {
  groceries:'#22c55e', fuel:'#f97316',    dining:'#a855f7',  shopping:'#3b82f6',
  utilities:'#06b6d4', transfer:'#6366f1', atm:'#84cc16',    medical:'#ef4444',
  education:'#f59e0b', other:'#64748b',
};

export const CAT_EMOJI = {
  groceries:'🛒', fuel:'⛽', dining:'🍽', shopping:'🛍',
  utilities:'🏠', transfer:'💸', atm:'🏧', medical:'💊',
  education:'📚', other:'📦',
};

/** Format number as PKR integer string */
export function fmt(n) {
  return Number(n).toLocaleString('en-PK', {minimumFractionDigits:0, maximumFractionDigits:0});
}

/** Escape HTML special chars */
export function esc(s) {
  return String(s ?? '')
    .replace(/&/g,'&amp;').replace(/</g,'&lt;')
    .replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}

/** Return YYYY-MM-DD string from a Date object */
export function isoDate(d) {
  return d.toISOString().split('T')[0];
}

/** Format ISO date string as "15 Mar 2024" */
export function fmtDate(iso) {
  if (!iso) return '';
  return new Date(iso + 'T00:00:00')
    .toLocaleDateString('en-PK', {day:'numeric', month:'short', year:'numeric'});
}

/** Build category <option> elements, with selectedVal pre-selected */
export function buildCategoryOptions(selectedVal = 'other') {
  return CATEGORIES.map(c =>
    `<option value="${c}" ${c === selectedVal ? 'selected' : ''}>${esc(CAT_LABELS[c])}</option>`
  ).join('');
}
