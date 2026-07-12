// static/js/router.js
import { init as authInit } from './auth.js';
import * as dashboard from './views/dashboard.js';
import * as activity  from './views/activity.js';
import * as budget    from './views/budget.js';
import * as syncView  from './views/sync.js';

const VIEWS = {
  dashboard: dashboard,
  activity:  activity,
  sync:      syncView,
  budget:    budget,
};

const DEFAULT_VIEW = 'dashboard';

// Track which views have been initialized (lazy init)
const initialized = {};
// Containers per view
const containers  = {};

function getViewId() {
  const hash = location.hash.slice(1);
  return VIEWS[hash] ? hash : DEFAULT_VIEW;
}

function navigate(viewId) {
  if (!VIEWS[viewId]) viewId = DEFAULT_VIEW;

  // Hide all views
  Object.keys(containers).forEach(id => {
    if (containers[id]) containers[id].style.display = 'none';
  });

  // Destroy current view if it has a destroy hook
  const currentId = document.querySelector('.bottom-nav-btn.active')?.dataset.view;
  if (currentId && VIEWS[currentId]?.destroy) {
    try { VIEWS[currentId].destroy(); } catch {}
  }

  // Update bottom nav
  document.querySelectorAll('.bottom-nav-btn').forEach(btn => {
    btn.classList.toggle('active', btn.dataset.view === viewId);
  });

  // Update hash without triggering another hashchange
  if (location.hash !== '#' + viewId) {
    history.replaceState(null, '', '#' + viewId);
  }

  // Get or create container
  if (!containers[viewId]) {
    const div = document.createElement('div');
    div.id = 'view-' + viewId;
    div.className = 'view-container';
    document.getElementById('app').appendChild(div);
    containers[viewId] = div;
  }

  containers[viewId].style.display = 'block';

  // Lazy init or re-init
  try {
    if (!initialized[viewId]) {
      initialized[viewId] = true;
      VIEWS[viewId].init(containers[viewId]);
    } else if (VIEWS[viewId].init) {
      VIEWS[viewId].init(containers[viewId]);
    }
  } catch (err) {
    console.error(`[router] ${viewId}.init() failed:`, err);
    containers[viewId].innerHTML = `<div style="color:#ffb4ab;padding:24px;font-family:monospace;white-space:pre-wrap;"><strong>Error in ${viewId} view:</strong>\n${err.message}\n\n${err.stack}</div>`;
  }
}

// ── Boot ──────────────────────────────────────────────────

authInit(() => {
  // Auth passed — show the app shell
  document.getElementById('appShell').style.display = 'flex';

  // Wire up bottom nav buttons
  document.querySelectorAll('.bottom-nav-btn').forEach(btn => {
    btn.addEventListener('click', () => navigate(btn.dataset.view));
  });

  // Handle browser back/forward
  window.addEventListener('hashchange', () => navigate(getViewId()));

  // Navigate to initial view
  navigate(getViewId());
});
