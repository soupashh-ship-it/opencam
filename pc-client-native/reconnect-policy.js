// Reconnect policy for the OpenCam Studio video socket (pure Node, no Electron
// deps — unit-testable).
//
// The decision is split out of main.js so it can be tested without an Electron
// runtime: the caller owns the counters and the timers, this module only says
// what to do next.
'use strict';

const FAST_RECONNECT_DELAY_MS = 1000;
const EARLY_RETRY_DELAY_MS = 1500;
const LATE_RETRY_DELAY_MS = 3000;
const SWITCH_TO_LATE_RETRY_AT = 4;
const DEFAULT_MAX_ATTEMPTS = 9;
const DEFAULT_FAST_RECONNECT_LIMIT = 30;

/**
 * Decides what to do after the video socket closed.
 *
 * States:
 *  - `stream-dropped`: frames already flowed once, so the drop is a blip and we
 *    reconnect quickly (bounded, so a phone that vanished does not loop forever).
 *  - `no-video-early`: never received a single frame, first few attempts.
 *  - `no-video-late`: still no frame after several attempts, back off harder.
 *  - `exhausted`: stop and tell the user what to check.
 *
 * @param {object} state
 * @param {boolean} state.framesEverReceived  A decodable frame arrived at least once.
 * @param {number}  state.consecutiveReconnects Drops since the last received frame.
 * @param {number}  state.connectAttempts     Failed attempts for this connection.
 * @param {number} [state.maxAttempts]
 * @param {number} [state.fastReconnectLimit]
 * @returns {{action: 'retry'|'giveup', delayMs: number, reason: string, attempt: number}}
 */
function decideReconnect({
  framesEverReceived = false,
  consecutiveReconnects = 0,
  connectAttempts = 0,
  maxAttempts = DEFAULT_MAX_ATTEMPTS,
  fastReconnectLimit = DEFAULT_FAST_RECONNECT_LIMIT,
} = {}) {
  const attempts = Number.isFinite(connectAttempts) ? Math.max(0, connectAttempts) : 0;

  // A drop after live video is a blip: reconnect fast, even past the early/late
  // attempt stages (bounded by fastReconnectLimit so it cannot loop forever).
  if (framesEverReceived && consecutiveReconnects <= fastReconnectLimit) {
    return {
      action: 'retry',
      delayMs: FAST_RECONNECT_DELAY_MS,
      reason: 'stream-dropped',
      attempt: attempts,
    };
  }

  // Exhaustion is authoritative once no video has flowed.
  if (attempts >= maxAttempts) {
    return {
      action: 'giveup',
      delayMs: 0,
      reason: 'exhausted',
      attempt: attempts,
    };
  }

  if (attempts < SWITCH_TO_LATE_RETRY_AT) {
    return {
      action: 'retry',
      delayMs: EARLY_RETRY_DELAY_MS,
      reason: 'no-video-early',
      attempt: attempts,
    };
  }

  return {
    action: 'retry',
    delayMs: LATE_RETRY_DELAY_MS,
    reason: 'no-video-late',
    attempt: attempts,
  };
}

/** Progress message for a retry decision (empty for `giveup`). */
function retryMessage(decision, maxAttempts = DEFAULT_MAX_ATTEMPTS) {
  switch (decision.reason) {
    case 'stream-dropped':
      return 'Stream dropped — reconnecting…';
    case 'no-video-early':
      return `Retrying (${decision.attempt}/${maxAttempts})…`;
    case 'no-video-late':
      return `Still no video — retrying (${decision.attempt}/${maxAttempts})…`;
    default:
      return '';
  }
}

module.exports = {
  decideReconnect,
  retryMessage,
  FAST_RECONNECT_DELAY_MS,
  EARLY_RETRY_DELAY_MS,
  LATE_RETRY_DELAY_MS,
  SWITCH_TO_LATE_RETRY_AT,
  DEFAULT_MAX_ATTEMPTS,
  DEFAULT_FAST_RECONNECT_LIMIT,
};
