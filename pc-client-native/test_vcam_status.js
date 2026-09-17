// Tests for the virtual camera status reader.
//
// The status used to be read with spawnSync on the Electron main process, which
// froze the Studio UI for up to 5 s. These tests pin the parsing behaviour shared
// by the sync and async readers, and check the async variant never rejects and
// never blocks.
'use strict';

const { parseStatusOutput, getVirtualCameraStatus, getVirtualCameraStatusAsync } = require('./vcam-feeder');

let passed = 0;
let failed = 0;

function assertEquals(expected, actual, label) {
  const e = JSON.stringify(expected);
  const a = JSON.stringify(actual);
  if (e !== a) throw new Error(`${label || 'value'}: expected ${e}, got ${a}`);
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

async function recordAsync(name, fn) {
  try {
    await fn();
    passed++;
    console.log(`PASS ${name}`);
  } catch (err) {
    failed++;
    console.log(`FAIL ${name} — ${err.message}`);
  }
}

async function main() {
  console.log('--- Virtual Camera Status Tests ---');

  record('parses raw JSON status output', () => {
    const status = parseStatusOutput('{"registered":true,"directShow":true,"mediaFoundation":true,"hkcu":true}');
    assertEquals(true, status.registered, 'registered');
    assertEquals(true, status.hkcu, 'hkcu');
  });

  record('extracts JSON from noisy stdout', () => {
    const noisy = 'OpenCam Virtual Camera feeder\n{"registered":true,"directShow":false}\n[exit 0]';
    const status = parseStatusOutput(noisy);
    assertEquals(true, status.registered, 'registered');
    assertEquals(false, status.directShow, 'directShow');
  });

  record('empty output reports an explicit error instead of throwing', () => {
    assertEquals(false, parseStatusOutput('').registered, 'registered');
    assertEquals('Empty response', parseStatusOutput('').error, 'error');
    assertEquals('Empty response', parseStatusOutput('   \r\n  ').error, 'blank error');
  });

  record('malformed JSON throws so callers can degrade gracefully', () => {
    let threw = false;
    try {
      parseStatusOutput('{"registered":');
    } catch (_) {
      threw = true;
    }
    if (!threw) throw new Error('expected a throw for malformed JSON');
  });

  record('sync reader returns a status object', () => {
    const status = getVirtualCameraStatus();
    if (!status || typeof status !== 'object') throw new Error('no status object');
    assertEquals('boolean', typeof status.registered, 'registered type');
  });

  await recordAsync('async reader returns a Promise and never rejects', async () => {
    if (typeof getVirtualCameraStatusAsync !== 'function') throw new Error('async reader not exported');
    const promise = getVirtualCameraStatusAsync();
    if (!promise || typeof promise.then !== 'function') throw new Error('must return a Promise');
    const status = await promise;
    if (!status || typeof status !== 'object') throw new Error('no status object');
    assertEquals('boolean', typeof status.registered, 'registered type');
  });

  await recordAsync('async reader agrees with the sync reader on shape', async () => {
    const [async_, sync] = await Promise.all([getVirtualCameraStatusAsync(), Promise.resolve(getVirtualCameraStatus())]);
    assertEquals(
      Object.keys(sync).sort().filter((k) => k !== 'error'),
      Object.keys(async_).sort().filter((k) => k !== 'error'),
      'status keys'
    );
  });

  console.log(`\n${passed} passed, ${failed} failed`);
  if (failed > 0) process.exit(1);
}

main().catch((err) => {
  console.error(`FAIL harness crashed — ${err.message}`);
  process.exit(1);
});
