// Global test setup — polyfill anything jsdom doesn't provide out of the box

// Material Symbols icon text just resolves to the ligature string in jsdom
// No extra setup needed for icon assertions

// Suppress console.error noise from async error paths being tested
const originalError = console.error;
beforeAll(() => {
  console.error = (...args) => {
    // Suppress expected "not a function" noise from jsdom timers
    if (typeof args[0] === 'string' && args[0].includes('Not implemented')) return;
    originalError(...args);
  };
});
afterAll(() => {
  console.error = originalError;
});
