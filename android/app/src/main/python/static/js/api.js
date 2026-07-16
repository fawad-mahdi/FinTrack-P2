// static/js/api.js
// Single source of truth for all server communication.
// Every fetch() call in the app goes through here.

const BASE = '';

export class ApiError extends Error {
  constructor(status, detail) {
    super(detail);
    this.status = status;
    this.detail = detail;
  }
}

// Per-launch API capability token supplied by the native layer through the
// JavaScript bridge. Every /api/* request must carry it as X-API-Token.
function apiToken() {
  try {
    if (typeof AndroidBridge !== 'undefined' && AndroidBridge.getApiToken) {
      return AndroidBridge.getApiToken() || '';
    }
  } catch { /* bridge unavailable */ }
  return '';
}

async function request(path, options = {}) {
  const headers = { 'X-API-Token': apiToken(), ...(options.headers || {}) };
  let res;
  try {
    res = await fetch(BASE + path, { ...options, headers });
  } catch (e) {
    if (e.name === 'AbortError') throw new ApiError(0, 'timeout');
    // Network offline / server not running
    throw new ApiError(0, e.message);
  }
  if (res.ok) return res.json();
  let detail = `HTTP ${res.status}`;
  try { detail = (await res.json()).detail || detail; } catch {}
  throw new ApiError(res.status, detail);
}

function json(method, path, body, extraOptions = {}) {
  return request(path, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    ...extraOptions,
  });
}

// ── Auth ──────────────────────────────────────────────────
export const authenticate     = (pin)          => json('POST', '/api/auth', { pin });
export const startGmailAuth   = ()             => json('POST', '/api/auth/gmail');

// ── Sync ──────────────────────────────────────────────────
export const syncGmail = (dateFrom, dateTo) => {
  const controller = new AbortController();
  const timeoutId  = setTimeout(() => controller.abort(), 5 * 60 * 1000); // 5 min
  return json('POST', '/api/sync', { date_from: dateFrom, date_to: dateTo }, { signal: controller.signal })
    .finally(() => clearTimeout(timeoutId));
};
export const getSyncStatus    = ()             => request('/api/sync/status');

// ── Transactions ──────────────────────────────────────────
export const getTransactions  = (params)       => request(`/api/transactions?${new URLSearchParams(params)}`);
export const createTransaction= (body)         => json('POST',   '/api/transactions',       body);
export const createCashTransaction = (body)    => json('POST',   '/api/transactions/cash',  body);
export const patchTransaction = (id, body)     => json('PATCH',  `/api/transactions/${id}`, body);
export const deleteTransaction= (id)           => request(`/api/transactions/${id}`, { method: 'DELETE' });

// ── Summary ───────────────────────────────────────────────
export const getSummary       = (params)       => request(`/api/summary?${new URLSearchParams(params)}`);
export const getCategorySummary=(params)       => request(`/api/summary/categories?${new URLSearchParams(params)}`);

// ── Categories ────────────────────────────────────────────
export const getCategoryMappings  = ()         => request('/api/categories/mappings');
export const addCategoryMapping   = (body)     => json('POST',   '/api/categories/mappings',           body);
export const deleteCategoryMapping= (merchant) => request(`/api/categories/mappings/${encodeURIComponent(merchant)}`, { method: 'DELETE' });

// ── Budgets ───────────────────────────────────────────────
export const getBudgets       = (month)        => request(`/api/budgets?month=${month}`);
export const upsertBudget     = (body)         => json('PUT',    '/api/budgets',  body);
export const suggestBudgets   = (month)        => json('POST',   '/api/budgets/suggest', { month });

// ── Report ────────────────────────────────────────────────
export const getMonthlyReport = (month)        => request(`/api/report?month=${month}`);

// ── Banks ─────────────────────────────────────────────────
export const getBanks         = ()             => request('/api/banks');
export const addBank          = (bank, address)=> json('POST', '/api/banks', { bank, address });
export const deleteBank       = (id)           => request(`/api/banks/${id}`, { method: 'DELETE' });

// ── Profile ───────────────────────────────────────────────
export const getProfile       = ()             => request('/api/profile');
export const updateProfile    = (name)         => json('PUT',  '/api/profile', { name });
export const changePin        = (currentPin, newPin) => json('POST', '/api/profile/pin', { current_pin: currentPin, new_pin: newPin });
