// Test suite for renderer frame sequencing, generation tracking, and stale decode rejection.
// Run: node test_renderer_sequencing.js
'use strict';

let passed = 0;
let failed = 0;

function check(name, cond, extra) {
  if (cond) {
    passed++;
    console.log(`PASS ${name}`);
  } else {
    failed++;
    console.log(`FAIL ${name}${extra ? ' — ' + extra : ''}`);
  }
}

// Harness replicating renderer.js state machine & frame consumer
function createRendererHarness() {
  let isConnected = false;
  let hasRenderedFrame = false;
  let currentUiState = 'disconnected';
  let streamGeneration = 0;
  let incomingFrameSeq = 0;
  let lastRenderedSeq = 0;
  let renderedFrames = [];
  let closedBitmaps = [];

  function resetStreamSession() {
    streamGeneration++;
    incomingFrameSeq = 0;
    lastRenderedSeq = 0;
  }

  function setUiState(state, message) {
    const prevState = currentUiState;
    currentUiState = state;

    if (state === 'connected') {
      isConnected = true;
      hasRenderedFrame = true;
    } else if (state === 'connecting') {
      isConnected = true;
      if (prevState === 'disconnected') {
        resetStreamSession();
      }
    } else if (state === 'reconnecting') {
      isConnected = true;
      if (prevState === 'connected') {
        resetStreamSession();
      }
    } else {
      // disconnected
      isConnected = false;
      if (prevState !== 'disconnected') {
        resetStreamSession();
      }
    }
  }

  async function handleVideoFrame(mockBitmapPromise) {
    const currentGen = streamGeneration;
    const seq = ++incomingFrameSeq;

    try {
      const bitmap = await mockBitmapPromise;
      if (!isConnected || currentGen !== streamGeneration || seq < lastRenderedSeq) {
        bitmap.close();
        closedBitmaps.push({ id: bitmap.id, seq, gen: currentGen, reason: 'dropped' });
        return;
      }
      lastRenderedSeq = seq;
      renderedFrames.push({ id: bitmap.id, seq, gen: currentGen });
    } catch (err) {
      // Decode failure
    }
  }

  return {
    setUiState,
    handleVideoFrame,
    getState: () => ({
      isConnected,
      hasRenderedFrame,
      currentUiState,
      streamGeneration,
      incomingFrameSeq,
      lastRenderedSeq,
      renderedCount: renderedFrames.length,
      closedCount: closedBitmaps.length,
      renderedFrames,
      closedBitmaps,
    }),
  };
}

function createMockBitmap(id) {
  let closed = false;
  return {
    id,
    close: () => { closed = true; },
    isClosed: () => closed,
  };
}

async function runTests() {
  console.log('--- Renderer Sequencing & Decode Poisoning Tests ---');

  // Test 1: In-order frame rendering
  {
    const h = createRendererHarness();
    h.setUiState('connecting', 'Connecting...');
    h.setUiState('connected', 'Live');

    const b1 = createMockBitmap('frame-1');
    const b2 = createMockBitmap('frame-2');

    await h.handleVideoFrame(Promise.resolve(b1));
    await h.handleVideoFrame(Promise.resolve(b2));

    const s = h.getState();
    check('In-order frames render successfully', s.renderedCount === 2 && s.lastRenderedSeq === 2);
    check('Rendered frames match sequence numbers', s.renderedFrames[0].seq === 1 && s.renderedFrames[1].seq === 2);
  }

  // Test 2: Out-of-order resolution drops older frame
  {
    const h = createRendererHarness();
    h.setUiState('connecting', 'Connecting...');
    h.setUiState('connected', 'Live');

    let resolveFrame1;
    const p1 = new Promise((resolve) => { resolveFrame1 = resolve; });
    const b1 = createMockBitmap('frame-1');
    const b2 = createMockBitmap('frame-2');

    const f1Promise = h.handleVideoFrame(p1);
    const f2Promise = h.handleVideoFrame(Promise.resolve(b2));

    await f2Promise;
    check('Frame 2 renders first when Frame 1 is slow', h.getState().lastRenderedSeq === 2);

    resolveFrame1(b1);
    await f1Promise;

    check('Slow Frame 1 is dropped without regressing lastRenderedSeq', h.getState().lastRenderedSeq === 2);
    check('Dropped Frame 1 bitmap is closed', b1.isClosed());
  }

  // Test 3: Disconnection prevents stale in-flight decodes from poisoning next stream
  {
    const h = createRendererHarness();
    h.setUiState('connecting', 'Connecting...');
    h.setUiState('connected', 'Live');

    let resolveStaleFrame;
    const pStale = new Promise((resolve) => { resolveStaleFrame = resolve; });
    const bStale = createMockBitmap('stale-frame-99');

    // Simulate 50 frames rendered
    for (let i = 1; i <= 50; i++) {
      await h.handleVideoFrame(Promise.resolve(createMockBitmap(`f-${i}`)));
    }
    check('Pre-disconnect stream reached seq 50', h.getState().lastRenderedSeq === 50);

    // Launch stale frame 51
    const stalePromise = h.handleVideoFrame(pStale);

    // Disconnect stream
    h.setUiState('disconnected', 'Disconnected');
    check('Disconnected resets lastRenderedSeq to 0', h.getState().lastRenderedSeq === 0);

    // Now stale frame 51 completes AFTER disconnect
    resolveStaleFrame(bStale);
    await stalePromise;

    check('Stale in-flight frame after disconnect is closed', bStale.isClosed());
    check('lastRenderedSeq remained 0 and was NOT poisoned to 51', h.getState().lastRenderedSeq === 0);

    // Reconnect new stream
    h.setUiState('connecting', 'Connecting...');
    h.setUiState('connected', 'Live');

    const bNew1 = createMockBitmap('new-frame-1');
    await h.handleVideoFrame(Promise.resolve(bNew1));

    check('Frame 1 on reconnected stream renders successfully', h.getState().lastRenderedSeq === 1);
    check('New frame is not closed', !bNew1.isClosed());
  }

  // Test 4: Repeated connecting status updates do NOT drop in-flight frames
  {
    const h = createRendererHarness();
    // User clicks Connect
    h.setUiState('connecting', 'Connecting to 192.168.1.10:4747...');
    const genBefore = h.getState().streamGeneration;

    let resolveEarlyFrame;
    const pEarly = new Promise((resolve) => { resolveEarlyFrame = resolve; });
    const bEarly = createMockBitmap('early-frame-1');

    // Early frame arrives right after TCP connect
    const earlyPromise = h.handleVideoFrame(pEarly);

    // Second status update arrives while early frame is still decoding
    h.setUiState('connecting', 'Connected to 192.168.1.10:4747 — waiting for video…');
    check('Connecting status update does NOT bump generation', h.getState().streamGeneration === genBefore);

    // Early frame finishes decoding
    resolveEarlyFrame(bEarly);
    await earlyPromise;

    check('Early frame renders despite status message update', h.getState().lastRenderedSeq === 1);
    check('Early frame bitmap not closed', !bEarly.isClosed());
  }

  // Test 5: Reconnection generation invalidates stale frames across reconnect
  {
    const h = createRendererHarness();
    h.setUiState('connecting', 'Connecting...');
    h.setUiState('connected', 'Live');

    let resolveOldFrame;
    const pOld = new Promise((resolve) => { resolveOldFrame = resolve; });
    const bOld = createMockBitmap('old-gen-frame');

    const oldPromise = h.handleVideoFrame(pOld);

    // Reconnection triggered
    h.setUiState('reconnecting', 'Reconnecting...');
    h.setUiState('connecting', 'Reconnecting to stream...');

    // Old frame finishes decoding after reconnection
    resolveOldFrame(bOld);
    await oldPromise;

    check('Old generation frame rejected during reconnect', bOld.isClosed());
    check('lastRenderedSeq remains 0 for fresh reconnect stream', h.getState().lastRenderedSeq === 0);
  }

  console.log(`\n${passed} passed, ${failed} failed\n`);
  if (failed > 0) process.exit(1);
}

runTests().catch((err) => {
  console.error(err);
  process.exit(1);
});
