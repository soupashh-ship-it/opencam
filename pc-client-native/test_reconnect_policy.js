// Regression tests for the reconnect policy used by main.js.
//
// These guard the behaviour that keeps a dropped stream recovering quickly while
// still giving up (with actionable guidance) when the phone was never reachable.
'use strict';

const {
  decideReconnect,
  retryMessage,
  FAST_RECONNECT_DELAY_MS,
  EARLY_RETRY_DELAY_MS,
  LATE_RETRY_DELAY_MS,
  DEFAULT_MAX_ATTEMPTS,
  DEFAULT_FAST_RECONNECT_LIMIT,
} = require('./reconnect-policy');

let passed = 0;
let failed = 0;

function check(name, fn) {
  try {
    fn();
    passed++;
    console.log(`PASS ${name}`);
  } catch (err) {
    failed++;
    console.log(`FAIL ${name} — ${err.message}`);
  }
}

function assertEquals(expected, actual, label) {
  const e = JSON.stringify(expected);
  const a = JSON.stringify(actual);
  if (e !== a) throw new Error(`${label || 'value'}: expected ${e}, got ${a}`);
}

console.log('--- Reconnect Policy Tests ---');

check('A drop after live video reconnects in 1s', () => {
  const d = decideReconnect({ framesEverReceived: true, consecutiveReconnects: 1, connectAttempts: 3 });
  assertEquals('retry', d.action, 'action');
  assertEquals(FAST_RECONNECT_DELAY_MS, d.delayMs, 'delayMs');
  assertEquals('stream-dropped', d.reason, 'reason');
});

check('Fast reconnects stop after the configured limit', () => {
  const atLimit = decideReconnect({
    framesEverReceived: true,
    consecutiveReconnects: DEFAULT_FAST_RECONNECT_LIMIT,
    connectAttempts: 4,
  });
  assertEquals('stream-dropped', atLimit.reason, 'at limit');

  const pastLimit = decideReconnect({
    framesEverReceived: true,
    consecutiveReconnects: DEFAULT_FAST_RECONNECT_LIMIT + 1,
    connectAttempts: 4,
  });
  assertEquals('no-video-late', pastLimit.reason, 'past limit');
  assertEquals(LATE_RETRY_DELAY_MS, pastLimit.delayMs, 'past limit delay');
});

check('A phone that never sends a frame backs off progressively', () => {
  const first = decideReconnect({ framesEverReceived: false, consecutiveReconnects: 0, connectAttempts: 1 });
  assertEquals('no-video-early', first.reason, 'attempt 1');
  assertEquals(EARLY_RETRY_DELAY_MS, first.delayMs, 'attempt 1 delay');

  const third = decideReconnect({ framesEverReceived: false, consecutiveReconnects: 0, connectAttempts: 3 });
  assertEquals('no-video-early', third.reason, 'attempt 3');

  const fourth = decideReconnect({ framesEverReceived: false, consecutiveReconnects: 0, connectAttempts: 4 });
  assertEquals('no-video-late', fourth.reason, 'attempt 4');
  assertEquals(LATE_RETRY_DELAY_MS, fourth.delayMs, 'attempt 4 delay');

  const last = decideReconnect({ framesEverReceived: false, consecutiveReconnects: 0, connectAttempts: 8 });
  assertEquals('retry', last.action, 'attempt 8 still retries');
});

check('Attempts are bounded and end in giveup', () => {
  const d = decideReconnect({
    framesEverReceived: false,
    consecutiveReconnects: 0,
    connectAttempts: DEFAULT_MAX_ATTEMPTS,
  });
  assertEquals('giveup', d.action, 'action');
  assertEquals(0, d.delayMs, 'delayMs');
  assertEquals('exhausted', d.reason, 'reason');
});

check('No state at all does not throw and still retries once', () => {
  const d = decideReconnect();
  assertEquals('retry', d.action, 'action');
  assertEquals(EARLY_RETRY_DELAY_MS, d.delayMs, 'delayMs');
});

check('Negative or non-numeric attempts are clamped', () => {
  const d = decideReconnect({ framesEverReceived: false, connectAttempts: -5 });
  assertEquals('no-video-early', d.reason, 'clamped');
  const n = decideReconnect({ framesEverReceived: false, connectAttempts: Number.NaN });
  assertEquals('no-video-early', n.reason, 'NaN clamped');
});

check('Retry messages match the UI copy', () => {
  assertEquals('Stream dropped — reconnecting…', retryMessage({ reason: 'stream-dropped' }));
  assertEquals('Retrying (2/9)…', retryMessage({ reason: 'no-video-early', attempt: 2 }));
  assertEquals('Still no video — retrying (5/9)…', retryMessage({ reason: 'no-video-late', attempt: 5 }));
  assertEquals('', retryMessage({ reason: 'exhausted', attempt: 9 }));
});

check('Custom maxAttempts is honoured', () => {
  const d = decideReconnect({ framesEverReceived: false, connectAttempts: 3, maxAttempts: 3 });
  assertEquals('giveup', d.action, 'action');
});

console.log(`\n${passed} passed, ${failed} failed`);
if (failed > 0) process.exit(1);
