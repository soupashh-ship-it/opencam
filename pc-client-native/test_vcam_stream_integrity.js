// Tests for virtual camera stream integrity.
//
// These cover the two feeder-side invariants that can be checked without a real
// child process:
//   1. Frame timestamps are strictly increasing, and header+payload are written
//      as one corked unit (a split pair desyncs the native reader permanently).
//   2. The feeder's stderr pipe is drained into a bounded tail instead of being
//      left unread, which used to block the child once the pipe buffer filled.
'use strict';

const { VirtualCamFeeder, appendBounded, STDERR_TAIL_LIMIT } = require('./vcam-feeder');

let passed = 0;
let failed = 0;

function assertEquals(expected, actual, label) {
  const e = JSON.stringify(expected);
  const a = JSON.stringify(actual);
  if (e !== a) throw new Error(`${label || 'value'}: expected ${e}, got ${a}`);
}

function assertPts(expected, actual, label) {
  if (typeof actual !== 'bigint') throw new Error(`${label || 'pts'}: expected a BigInt, got ${typeof actual}`);
  if (actual !== BigInt(expected)) throw new Error(`${label || 'pts'}: expected ${expected}, got ${actual}`);
}

function record(name, fn) {
  try {
    fn();
    passed++;
    console.log(`PASS ${name}`);
  } catch (err) {
    failed++;
    console.log(`FAIL ${name} — ${err.message}`);
  }
}

/** Minimal stand-in for the feeder's stdin, recording the write order. */
function makeFakeStdin() {
  const ops = [];
  const chunks = [];
  return {
    ops,
    chunks,
    writableLength: 0,
    destroyed: false,
    cork() {
      ops.push('cork');
    },
    uncork() {
      ops.push('uncork');
    },
    write(buf) {
      const copy = Buffer.from(buf);
      ops.push(chunks.length === 0 ? 'header' : 'payload');
      chunks.push(copy);
      return true;
    },
  };
}

/** Attach a fake stdin so pushFrame() works without spawning the feeder. */
function feedWith(feeder, stdin) {
  feeder.process = { stdin, killed: false, killed_signal: null };
  return feeder;
}

/** Read the 8-byte big-endian PTS from a captured 12-byte wire header. */
function ptsOf(chunk) {
  return chunk.readBigUInt64BE(0);
}

/** Read the 4-byte big-endian payload length from a captured wire header. */
function lenOf(chunk) {
  return chunk.readUInt32BE(8);
}

const JPEG = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0xff, 0xd9]);

function main() {
  console.log('--- Virtual Camera Stream Integrity Tests ---');

  record('defaults to 60 fps rather than a 30 fps cap', () => {
    const feeder = new VirtualCamFeeder();
    assertEquals(60, feeder.currentFps, 'feeder default fps');
  });

  record('accepts strictly increasing timestamps unchanged', () => {
    const stdin = makeFakeStdin();
    const feeder = feedWith(new VirtualCamFeeder(), stdin);
    if (!feeder.pushFrame(JPEG, 1000)) throw new Error('first frame rejected');
    if (!feeder.pushFrame(JPEG, 2000)) throw new Error('second frame rejected');
    assertPts(1000, ptsOf(stdin.chunks[0]), 'first pts');
    assertPts(2000, ptsOf(stdin.chunks[2]), 'second pts');
  });

  record('rejects a backwards timestamp instead of forwarding it', () => {
    const stdin = makeFakeStdin();
    const feeder = feedWith(new VirtualCamFeeder(), stdin);
    feeder.pushFrame(JPEG, 1500000);
    feeder.pushFrame(JPEG, 1500000 - 500);
    const first = ptsOf(stdin.chunks[0]);
    const second = ptsOf(stdin.chunks[2]);
    if (!(second > first)) {
      throw new Error(`timestamp went backwards: ${first} then ${second}`);
    }
  });

  record('never forwards a repeated timestamp', () => {
    const stdin = makeFakeStdin();
    const feeder = feedWith(new VirtualCamFeeder(), stdin);
    feeder.pushFrame(JPEG, 700);
    feeder.pushFrame(JPEG, 700);
    const first = ptsOf(stdin.chunks[0]);
    const second = ptsOf(stdin.chunks[2]);
    if (!(second > first)) {
      throw new Error(`duplicate timestamp forwarded: ${first} then ${second}`);
    }
  });

  record('substitutes a monotonic value for invalid timestamps', () => {
    const stdin = makeFakeStdin();
    const feeder = feedWith(new VirtualCamFeeder(), stdin);
    const inputs = [undefined, null, NaN, Infinity, -5, 0, 'abc'];
    for (const value of inputs) {
      if (!feeder.pushFrame(JPEG, value)) throw new Error(`frame rejected for ${String(value)}`);
    }
    let previous = -1n;
    for (let i = 0; i < stdin.chunks.length; i += 2) {
      const pts = ptsOf(stdin.chunks[i]);
      if (!(pts > previous)) throw new Error(`not increasing at frame ${i / 2}: ${pts} after ${previous}`);
      previous = pts;
    }
    assertEquals(inputs.length, stdin.chunks.length / 2, 'frames written');
  });

  record('recovers monotonicity after a phone reconnect resets its clock', () => {
    const stdin = makeFakeStdin();
    const feeder = feedWith(new VirtualCamFeeder(), stdin);
    feeder.pushFrame(JPEG, 900000);
    feeder.pushFrame(JPEG, 1200000); // connection drops, stream restarts at a low pts
    feeder.pushFrame(JPEG, 40);
    feeder.pushFrame(JPEG, 80);
    const ptsList = stdin.chunks.filter((_, i) => i % 2 === 0).map(ptsOf);
    for (let i = 1; i < ptsList.length; i++) {
      if (!(ptsList[i] > ptsList[i - 1])) {
        throw new Error(`frame ${i} went backwards: ${ptsList[i]} after ${ptsList[i - 1]}`);
      }
    }
  });

  record('writes the header and payload as one corked unit', () => {
    const stdin = makeFakeStdin();
    const feeder = feedWith(new VirtualCamFeeder(), stdin);
    feeder.pushFrame(JPEG, 12345);
    assertEquals(['cork', 'header', 'payload', 'uncork'], stdin.ops, 'write order');
    assertEquals(12, stdin.chunks[0].length, 'header length');
    assertEquals(JPEG.length, lenOf(stdin.chunks[0]), 'declared payload length');
    assertEquals(JPEG.length, stdin.chunks[1].length, 'payload length');
    if (!stdin.chunks[1].equals(JPEG)) throw new Error('payload bytes changed');
    assertPts(12345, ptsOf(stdin.chunks[0]), 'pts');
    assertEquals(1, feeder.framesPushed, 'framesPushed');
  });

  record('appends stderr within the limit', () => {
    assertEquals('ab', appendBounded('a', 'b'), 'concat');
    assertEquals('b', appendBounded('', 'b'), 'empty previous');
    assertEquals('b', appendBounded(null, 'b'), 'null previous');
    assertEquals('', appendBounded(null, null), 'null chunk');
    assertEquals('buffer', appendBounded('', Buffer.from('buffer')), 'buffer chunk');
  });

  record('keeps only the tail of a chatty stderr', () => {
    const noisy = 'x'.repeat(STDERR_TAIL_LIMIT * 3);
    const tail = appendBounded('', noisy);
    assertEquals(STDERR_TAIL_LIMIT, tail.length, 'bounded length');
    assertEquals('y'.repeat(10), appendBounded(noisy, 'y'.repeat(10)).slice(-10), 'newest data kept');
    const grown = appendBounded('a'.repeat(10), 'b'.repeat(10), 12);
    assertEquals('aabbbbbbbbbb', grown, 'trims from the front');
  });

  console.log(`\n${passed} passed, ${failed} failed`);
  if (failed > 0) process.exit(1);
}

main();
