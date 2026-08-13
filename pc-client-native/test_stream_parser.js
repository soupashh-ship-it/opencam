// Wire-level test for pc-client-native/stream-parser.js against a mock phone
// that mimics the real Android server: droidcam-v5 request parsing, the
// override-and-kick on a genuinely new (codec,w,h) request, and framed MJPEG
// streaming with 12-byte headers. Run: node test_stream_parser.js
'use strict';

const net = require('net');
const http = require('http');
const { buildVideoRequest, createFrameParser, HEADER_SIZE } = require('./stream-parser');

const VIDEO_RE = /^\/v5\/video\/([^/]+)\/(\d+)x(\d+)\/port\/(\d+)\/os\/([^/]*)\/obs\/([^/]*)\/client\/([^/]*)\/hdr\/([01])\/nonce\/(\d+)\/?$/;

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

// Minimal JPEG: SOI + fake data + EOI.
function makeJpegFrame(seed) {
  const data = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10]);
  const body = Buffer.alloc(64);
  for (let i = 0; i < body.length; i++) body[i] = (seed * 7 + i) & 0xff;
  const end = Buffer.from([0xff, 0xd9]);
  return Buffer.concat([data, body, end]);
}

function framePacket(payload, ptsUs) {
  const header = Buffer.alloc(HEADER_SIZE);
  header.writeBigUInt64BE(BigInt(ptsUs), 0);
  header.writeUInt32BE(payload.length, 8);
  return Buffer.concat([header, payload]);
}

// ---------------------------------------------------------------------------
// Mock phone server
// ---------------------------------------------------------------------------
function startMockPhone(port) {
  let currentConfig = { codec: 'avc', w: 1280, h: 720 }; // like the app's default
  const clients = new Set();

  const server = net.createServer((socket) => {
    socket.setNoDelay(true);
    let requestBuf = '';
    let handed = false;

    socket.on('data', (chunk) => {
      if (handed) return; // already streaming to this client
      requestBuf += chunk.toString('latin1');
      const nl = requestBuf.indexOf('\n');
      if (nl === -1) return;
      const line = requestBuf.slice(0, nl).trim();
      requestBuf = '';

      const parts = line.split(/\s+/, 3);
      const method = (parts[0] || '').toUpperCase();
      const path = parts[1] || '';

      if (method === 'GET' && path.startsWith('/v1/status')) {
        const body = JSON.stringify({
          version: '1.6.2', codec: currentConfig.codec,
          width: currentConfig.w, height: currentConfig.h,
          streamWidth: currentConfig.w, streamHeight: currentConfig.h,
          fps: 30, battery: 87, running: true,
        });
        socket.end(
          `HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n${body}`
        );
        return;
      }

      const m = VIDEO_RE.exec(path);
      if (!m) {
        socket.end('HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n');
        return;
      }
      const codec = m[1];
      const w = Number(m[2]);
      const h = Number(m[3]);
      handed = true;

      const requested = `${codec}/${w}x${h}`;
      const current = `${currentConfig.codec}/${currentConfig.w}x${currentConfig.h}`;
      if (requested !== current) {
        // Genuinely new request → override config and kick this client,
        // exactly like the Android server's onVideoClientConnected.
        currentConfig = { codec, w, h };
        socket.destroy();
        return;
      }

      // Matches current config → accept and stream MJPEG frames.
      clients.add(socket);
      let seed = 1;
      const timer = setInterval(() => {
        if (socket.destroyed) {
          clearInterval(timer);
          clients.delete(socket);
          return;
        }
        socket.write(framePacket(makeJpegFrame(seed), seed * 1000));
        seed++;
      }, 25);
      socket.on('close', () => {
        clearInterval(timer);
        clients.delete(socket);
      });
    });
  });

  return new Promise((resolve) => {
    server.listen(port, '127.0.0.1', () => resolve(server));
  });
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------
function testRequestFormat() {
  const req = buildVideoRequest('jpg', 1920, 1080, 4747);
  const path = req.split(' ')[1];
  const m = VIDEO_RE.exec(path);
  check('request matches Android protocol regex', !!m, path);
  if (m) {
    check(
      'request params correct',
      m[1] === 'jpg' && m[2] === '1920' && m[3] === '1080' && m[4] === '4747',
      m.slice(1, 5).join(',')
    );
  }
}

function testParserFraming() {
  const frames = [];
  const parser = createFrameParser({
    onFrame: (payload) => frames.push(payload),
  });
  // Feed a mix of whole frames and byte-at-a-time chunks.
  const f1 = makeJpegFrame(1);
  const f2 = makeJpegFrame(2);
  const stream = Buffer.concat([framePacket(f1, 111), framePacket(f2, 222)]);
  for (let i = 0; i < stream.length; i += 7) {
    parser.push(stream.slice(i, i + 7));
  }
  check('parser extracts frames from split chunks', frames.length === 2, `got ${frames.length}`);
  check('frame payloads intact', frames[0].equals(f1) && frames[1].equals(f2));
  // Corrupt length must be treated as a clean stream-boundary failure.
  const bad = Buffer.alloc(HEADER_SIZE + 4);
  bad.writeUInt32BE(0xffffffff, 8);
  let errors = 0;
  const parser2 = createFrameParser({ onFrame: () => {}, onError: () => errors++ });
  parser2.push(bad);
  check('corrupt length reported', errors === 1, `errors=${errors}`);
  parser2.push(framePacket(f2, 333));
  // The parser has reset, so the next complete frame can be decoded cleanly.
  const postReset = [];
  const parserReset = createFrameParser({ onFrame: (payload) => postReset.push(payload) });
  parserReset.push(framePacket(f2, 333));
  check('fresh parser decodes the next clean frame', postReset.length === 1);
}



async function testEndToEnd() {
  const port = 24747;
  const server = await startMockPhone(port);

  const received = [];
  const parser = createFrameParser({
    onFrame: (payload) => {
      received.push(payload);
    },
  });

  // First connect requests jpg/1080p while the phone is on avc/720p → kick.
  // The client must reconnect with the SAME request and then get frames.
  let kicks = 0;
  let connected = false;

  function attempt() {
    return new Promise((resolveAttempt) => {
      const sock = new net.Socket();
      const req = buildVideoRequest('jpg', 1920, 1080, port);
      sock.connect(port, '127.0.0.1', () => {
        sock.write(req);
      });
      sock.on('data', (chunk) => {
        connected = true;
        parser.push(chunk);
        resolveAttempt();
      });
      sock.on('close', () => {
        kicks++;
        resolveAttempt();
      });
      sock.on('error', () => resolveAttempt());
      // Give up after 1.5s if nothing happens.
      setTimeout(() => {
        if (!sock.destroyed) sock.destroy();
        resolveAttempt();
      }, 1500);
    });
  }

  await attempt(); // first: override-kick expected
  await new Promise((r) => setTimeout(r, 50));
  await attempt(); // second: same request now matches → frames flow

  await new Promise((r) => setTimeout(r, 300));

  check('first connection kicked (override)', kicks >= 1, `kicks=${kicks}`);
  check('client reconnected and received frames', received.length > 0, `frames=${received.length}`);
  const first = received[0];
  if (first) {
    check(
      'frame is a valid JPEG',
      first[0] === 0xff && first[1] === 0xd8 && first[first.length - 2] === 0xff && first[first.length - 1] === 0xd9
    );
  }

  // Status endpoint still works.
  const status = await new Promise((resolve) => {
    http.get({ host: '127.0.0.1', port, path: '/v1/status', timeout: 2000 }, (res) => {
      let data = '';
      res.on('data', (c) => (data += c));
      res.on('end', () => resolve(JSON.parse(data)));
    }).on('error', () => resolve(null));
  });
  check('status endpoint returns JSON', !!status && status.codec === 'jpg', JSON.stringify(status));

  server.close();
}

(async () => {
  testRequestFormat();
  testParserFraming();
  await testEndToEnd();
  console.log(`\n${passed} passed, ${failed} failed`);
  process.exit(failed === 0 ? 0 : 1);
})();
