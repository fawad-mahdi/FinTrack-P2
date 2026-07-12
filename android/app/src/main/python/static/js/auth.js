// static/js/auth.js
import { authenticate } from './api.js';

const PIN_LENGTH = 4;

/**
 * Mount the PIN screen. Calls onSuccess() when authentication succeeds.
 * The PIN screen is removed from the DOM on success.
 */
export function init(onSuccess) {
  const screen = document.createElement('div');
  screen.className = 'pin-screen';
  screen.id = 'pinScreen';
  screen.innerHTML = `
    <div class="pin-card">
      <span class="pin-lock-icon material-symbols-outlined">lock</span>
      <h1 class="pin-title">FinTrack <span>PK</span></h1>
      <p class="pin-subtitle">Enter Secure PIN</p>
      <div class="pin-dots">
        <div class="pin-dot" id="dot0"></div>
        <div class="pin-dot" id="dot1"></div>
        <div class="pin-dot" id="dot2"></div>
        <div class="pin-dot" id="dot3"></div>
      </div>
      <div class="numpad">
        ${[1,2,3,4,5,6,7,8,9].map(n => `<button class="numpad-btn" data-digit="${n}">${n}</button>`).join('')}
        <button class="numpad-btn action" id="pinClearBtn">Clear</button>
        <button class="numpad-btn" data-digit="0">0</button>
        <button class="numpad-btn action" id="pinBackBtn">
          <span class="material-symbols-outlined">backspace</span>
        </button>
      </div>
      <button class="pin-fingerprint-btn" id="pinFingerprintBtn">
        <span class="material-symbols-outlined">fingerprint</span>
        Use Fingerprint
      </button>
      <div class="pin-error" id="pinError"></div>
    </div>`;

  document.body.prepend(screen);

  let pinValue = '';

  function updateDots() {
    for (let i = 0; i < PIN_LENGTH; i++) {
      screen.querySelector(`#dot${i}`).classList.toggle('filled', i < pinValue.length);
    }
  }

  function showError(msg) {
    const el = screen.querySelector('#pinError');
    el.textContent = msg;
    el.classList.add('visible');
  }

  function clearError() {
    screen.querySelector('#pinError').classList.remove('visible');
  }

  async function submit() {
    const pin = pinValue;
    try {
      await authenticate(pin);
      screen.remove();
      onSuccess();
    } catch (e) {
      if (e.status === 401) {
        showError('Wrong PIN. Try again.');
      } else {
        showError('Server not running. Start with: python server.py');
      }
      pinValue = '';
      updateDots();
    }
  }

  screen.querySelectorAll('[data-digit]').forEach(btn => {
    btn.addEventListener('click', () => {
      if (pinValue.length >= PIN_LENGTH) return;
      clearError();
      pinValue += btn.dataset.digit;
      updateDots();
      if (pinValue.length === PIN_LENGTH) submit();
    });
  });

  screen.querySelector('#pinClearBtn').addEventListener('click', () => {
    pinValue = ''; updateDots(); clearError();
  });

  screen.querySelector('#pinBackBtn').addEventListener('click', () => {
    if (!pinValue.length) return;
    pinValue = pinValue.slice(0, -1);
    updateDots(); clearError();
  });

  screen.querySelector('#pinFingerprintBtn').addEventListener('click', () => {
    showError('Biometric authentication is not supported on this platform.');
  });
}
