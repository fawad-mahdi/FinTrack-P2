// static/js/views/profile.js
import { getProfile, updateProfile, changePin } from '../api.js';
import { esc } from '../utils/constants.js';

let container;

export function init(el) {
  container = el;
  load();
}

export function destroy() {}

async function load() {
  container.innerHTML = `<div class="mr"><div class="mr-loading"><span class="material-symbols-outlined mr-spin">autorenew</span></div></div>`;
  let profile = { name: 'User', email: null };
  try {
    profile = await getProfile();
  } catch {
    // Render with defaults; the card shows "Not connected" for email.
  }
  render(profile);
}

function render(profile) {
  const initial = (profile.name || 'U').trim().charAt(0).toUpperCase();
  container.innerHTML = `
    <div class="mr">

      <!-- Glass sticky header -->
      <div class="mr-header">
        <span class="mr-brand">Profile</span>
        <span class="material-symbols-outlined mr-lock-icon">person</span>
      </div>

      <!-- Identity card -->
      <div class="pf-card">
        <div class="pf-avatar">${esc(initial)}</div>
        <div class="pf-identity">
          <div class="pf-name-row">
            <span class="pf-name" id="pfNameText">${esc(profile.name)}</span>
            <button class="pf-icon-btn" id="pfEditBtn" title="Edit name">
              <span class="material-symbols-outlined">edit</span>
            </button>
          </div>
          <div class="pf-email">
            <span class="material-symbols-outlined">mail</span>
            ${profile.email ? esc(profile.email) : '<span class="pf-email-off">Gmail not connected</span>'}
          </div>
        </div>
      </div>

      <!-- Name edit form (hidden until Edit tapped) -->
      <div class="pf-section" id="pfNameForm" hidden>
        <div class="pf-section-title">Display Name</div>
        <input class="pf-input" id="pfNameInput" maxlength="50" value="${esc(profile.name)}">
        <div class="pf-msg" id="pfNameMsg"></div>
        <div class="form-actions">
          <button class="btn" id="pfNameCancel">Cancel</button>
          <button class="btn btn-primary" id="pfNameSave">Save</button>
        </div>
      </div>

      <!-- Change PIN -->
      <div class="pf-section">
        <div class="pf-section-title">Security</div>
        <label class="pf-label" for="pfPinCurrent">Current PIN</label>
        <input class="pf-input" id="pfPinCurrent" type="password" inputmode="numeric" maxlength="4" autocomplete="off">
        <label class="pf-label" for="pfPinNew">New PIN</label>
        <input class="pf-input" id="pfPinNew" type="password" inputmode="numeric" maxlength="4" autocomplete="off">
        <label class="pf-label" for="pfPinConfirm">Confirm New PIN</label>
        <input class="pf-input" id="pfPinConfirm" type="password" inputmode="numeric" maxlength="4" autocomplete="off">
        <div class="pf-msg" id="pfPinMsg"></div>
        <div class="form-actions">
          <button class="btn btn-primary" id="pfPinSave">Change PIN</button>
        </div>
        <p class="pf-hint">Changes the 4-digit PIN used on this app's lock screen.</p>
      </div>

    </div>`;

  wireNameEdit(profile);
  wirePinChange();
}

function showMsg(id, text, ok) {
  const el = container.querySelector(id);
  el.textContent = text;
  el.classList.toggle('ok', !!ok);
  el.classList.toggle('visible', !!text);
}

function wireNameEdit(profile) {
  const form  = container.querySelector('#pfNameForm');
  const input = container.querySelector('#pfNameInput');

  container.querySelector('#pfEditBtn').addEventListener('click', () => {
    form.hidden = !form.hidden;
    if (!form.hidden) input.focus();
  });

  container.querySelector('#pfNameCancel').addEventListener('click', () => {
    form.hidden = true;
    input.value = profile.name;
    showMsg('#pfNameMsg', '', false);
  });

  container.querySelector('#pfNameSave').addEventListener('click', async () => {
    const name = input.value.trim();
    if (!name) { showMsg('#pfNameMsg', 'Name cannot be empty.', false); return; }
    try {
      await updateProfile(name);
      render({ ...profile, name });
    } catch (e) {
      showMsg('#pfNameMsg', e.detail || 'Could not save name.', false);
    }
  });
}

function wirePinChange() {
  const current = container.querySelector('#pfPinCurrent');
  const nw      = container.querySelector('#pfPinNew');
  const confirm = container.querySelector('#pfPinConfirm');
  const saveBtn = container.querySelector('#pfPinSave');

  saveBtn.addEventListener('click', async () => {
    showMsg('#pfPinMsg', '', false);
    if (!/^\d{4}$/.test(nw.value)) {
      showMsg('#pfPinMsg', 'New PIN must be exactly 4 digits.', false);
      return;
    }
    if (nw.value !== confirm.value) {
      showMsg('#pfPinMsg', 'PINs do not match.', false);
      return;
    }
    saveBtn.disabled = true;
    try {
      await changePin(current.value, nw.value);
      current.value = nw.value = confirm.value = '';
      showMsg('#pfPinMsg', 'PIN updated.', true);
    } catch (e) {
      showMsg('#pfPinMsg', e.detail || 'Could not change PIN.', false);
    } finally {
      saveBtn.disabled = false;
    }
  });
}
