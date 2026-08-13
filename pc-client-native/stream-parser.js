// OpenCam wire protocol helpers (pure Node, no Electron deps — unit-testable).
//
// The phone speaks the droidcam-obs-plugin v5 protocol:
//   1. The client sends one HTTP-ish GET request line.
//   2. The server replies with a raw byte stream of framed packets:
//      8-byte big-endian PTS + 4-byte big-endian length, then the payload.
//      No HTTP response preamble is sent for video connections.
'use strict';

const HEADER_SIZE = 12;
const DEFAULT_PORT = 4747;
const DEFAULT_MAX_PACKET = 20 * 1024 * 1024; // matches the server's limit

/** Builds the video handshake request line for a (codec, width, height). */
function buildVideoRequest(codec, width, height, port) {
  const c = (codec || 'jpg').toLowerCase();
  const w = width || 1920;
  const h = height || 1080;
  const p = port || DEFAULT_PORT;
  return (
    `GET /v5/video/${c}/${w}x${h}/port/${p}/os/windows/obs/1.1.0/` +
    `client/opencam-studio/hdr/0/nonce/100/ HTTP/1.1\r\n\r\n`
  );
}

/**
 * Incremental parser for the framed byte stream.
 *
 * Usage:
 *   const parser = createFrameParser({ onFrame: (payload, ptsUs) => {...} });
 *   socket.on('data', (chunk) => parser.push(chunk));
 *   parser.reset();
 *
 * A frame is only reported when a full payload has been buffered. Callbacks
 * never throw: a corrupt length reports an error and resets the parser; the
 * owning socket should reconnect to establish a clean framing boundary.
 */
function createFrameParser({ onFrame, onError, maxPacketSize = DEFAULT_MAX_PACKET }) {
  let headerBuffer = Buffer.alloc(HEADER_SIZE);
  let headerLength = 0;
  let payloadBuffer = null;
  let expectedLength = 0;
  let payloadLength = 0;
  let payloadOffset = 0;
  let readingHeader = true;
  let lastPts = -1;

  function push(chunk) {
    if (!Buffer.isBuffer(chunk)) chunk = Buffer.from(chunk);
    let offset = 0;
    while (offset < chunk.length) {
      if (readingHeader) {
        const take = Math.min(HEADER_SIZE - headerLength, chunk.length - offset);
        chunk.copy(headerBuffer, headerLength, offset, offset + take);
        headerLength += take;
        offset += take;

        if (headerLength !== HEADER_SIZE) continue;

        lastPts = Number(headerBuffer.readBigUInt64BE(0));
        const rawLength = headerBuffer.readUInt32BE(8);
        if (rawLength === 0xffffffff || rawLength <= 0 || rawLength > maxPacketSize) {
          if (onError) {
            try { onError(`bad packet length ${rawLength}`); } catch (_) {}
          }
          reset();
          return;
        }

        expectedLength = rawLength;
        payloadLength = rawLength;
        payloadOffset = 0;
        payloadBuffer = Buffer.allocUnsafe(rawLength);
        headerLength = 0;
        readingHeader = false;
      } else {
        const take = Math.min(payloadLength - payloadOffset, chunk.length - offset);
        chunk.copy(payloadBuffer, payloadOffset, offset, offset + take);
        payloadOffset += take;
        offset += take;

        if (payloadOffset === payloadLength) {
          const complete = payloadBuffer;
          if (onFrame) {
            try { onFrame(complete, lastPts); } catch (_) {}
          }
          payloadBuffer = null;
          payloadLength = 0;
          payloadOffset = 0;
          expectedLength = 0;
          headerLength = 0;
          readingHeader = true;
        }
      }
    }
  }

  function reset() {
    headerLength = 0;
    payloadBuffer = null;
    payloadLength = 0;
    payloadOffset = 0;
    expectedLength = 0;
    readingHeader = true;
  }

  return { push, reset };
}

module.exports = { buildVideoRequest, createFrameParser, HEADER_SIZE, DEFAULT_PORT };
