#!/usr/bin/env python3
"""
Mock OpenCam phone server — replicates the phone's HTTP wire protocol so the
desktop client can be tested without a device.

Implements the same endpoints and byte formats as the Android app:
  * GET  /video                      multipart MJPEG (boundary --dcmjpeg)
  * GET  /v2/audio                   raw framed AAC  [int64 LE pts][int32 LE len][data]
  * GET  /v1/phone/info|name|battery_info|camera_list|camera/info
  * PUT  control endpoints           (active, zoom, ev, wb_mode, wb_level, mic_toggle, …)

Run standalone:   python mock_server.py            (serves on 127.0.0.1:4748)
Used by:          python opencam_client.py --selftest
"""

import io
import json
import socket
import struct
import threading
import time

try:
    from PIL import Image
except ImportError:
    Image = None

try:
    import av
    import numpy as np
    from fractions import Fraction
    HAS_AV = True
except ImportError:
    av = np = Fraction = None
    HAS_AV = False

BOUNDARY = b"--dcmjpeg"


def _make_h264_stream(width=320, height=180, frames=12):
    """Encode a short real H.264 Annex-B stream with PyAV (self-contained mock).
    Returns a list of framed packets: [(pts, payload), ...] with the SPS/PPS
    config first, matching the phone's FramedSink wire format."""
    if not HAS_AV:
        return None
    ctx = av.CodecContext.create("libx264", "w")
    ctx.width, ctx.height = width, height
    ctx.pix_fmt = "yuv420p"
    ctx.time_base = Fraction(1, 30)
    ctx.framerate = 30
    ctx.options = {"preset": "ultrafast", "tune": "zerolatency",
                   "x264-params": "keyint=12"}
    ctx.open()
    packets = []  # (pts, bytes) in Annex-B
    for i in range(frames):
        rgb = np.zeros((height, width, 3), dtype=np.uint8)
        rgb[:, :, 0] = (i * 20) % 256
        rgb[:, :, 1] = 128
        rgb[:, :, 2] = 255
        fr = av.VideoFrame.from_ndarray(rgb, format="rgb24").reformat(format="yuv420p")
        for pkt in ctx.encode(fr):
            packets.append((pkt.pts or 0, bytes(pkt)))
    for pkt in ctx.encode(None):
        packets.append((pkt.pts or 0, bytes(pkt)))
    # split SPS/PPS (config) from the picture stream — the phone replays the
    # codec-config buffer first, then access units.
    config = b""
    pictures = []
    for pts, data in packets:
        nals = [n for n in data.split(b"\x00\x00\x00\x01") if n]
        is_config = any((n[0] & 0x1f) in (7, 8) for n in nals)
        if is_config:
            config += data
        else:
            pictures.append((pts, data))
    out = []
    if config:
        out.append((0, config))
    out.extend(pictures)
    return out


def make_jpeg(seq, width=640, height=360):
    """A JPEG frame with a moving bar so motion is visible to the eye/tests."""
    img = Image.new("RGB", (width, height), (18, 24, 30))
    px = img.load()
    bar_x = (seq * 40) % width
    for x in range(bar_x, min(bar_x + 60, width)):
        for y in range(height):
            px[x, y] = (0, 196, 255)
    for y in range(0, height, 40):
        for x in range(width):
            px[x, y] = (233, 238, 242)
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=80)
    return buf.getvalue()


class MockServer(threading.Thread):
    """Runs in a thread; pick a free port by passing 0 and read .port."""

    def __init__(self, addr=("127.0.0.1", 0)):
        super().__init__(daemon=True)
        self.addr = addr
        self.port = None
        self._sock = None
        self._running = False
        self._seq = 0
        self._lock = threading.Lock()
        self.active_camera = 0
        self.zoom = 1.0
        self.ev = 0
        self.wb_mode = 5
        self.wb_level = 50
        self.muted = 0
        self.codec = "jpg"
        self.bitrate = 8000
        self._h264_cache = None

    def run(self):
        self._sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._sock.bind(self.addr)
        self._sock.listen(8)
        self.port = self._sock.getsockname()[1]
        self._running = True
        while self._running:
            try:
                conn, _ = self._sock.accept()
            except OSError:
                break
            threading.Thread(target=self._handle, args=(conn,), daemon=True).start()

    def stop(self):
        self._running = False
        if self._sock is not None:
            try:
                self._sock.close()
            except OSError:
                pass

    # ---- request handling ---------------------------------------------------
    def _handle(self, conn):
        try:
            conn.settimeout(5)
            data = b""
            while b"\r\n\r\n" not in data:
                chunk = conn.recv(4096)
                if not chunk:
                    conn.close()
                    return
                data += chunk
            head = data.split(b"\r\n\r\n", 1)[0]
            request_line = head.split(b"\r\n", 1)[0].decode("latin1", "replace")
            parts = request_line.split(" ")
            method = parts[0] if parts else ""
            path = parts[1] if len(parts) > 1 else "/"
            self._dispatch(conn, method, path)
        except Exception:
            try:
                conn.close()
            except OSError:
                pass

    def _dispatch(self, conn, method, path):
        if path == "/video":
            self._serve_video(conn)
            return
        if path.startswith("/v5/video/") or path.startswith("/v4/video/"):
            self._serve_framed(conn, path)
            return
        if path == "/v2/audio":
            self._serve_audio(conn)
            return

        body = self._control_response(method, path)
        if body is None:
            body = b"404 Not Found"
            status = "404 Not Found"
            ctype = "text/plain; charset=UTF-8"
        else:
            status = "200 OK"
            ctype = "text/plain; charset=UTF-8"
            if path.endswith("info") or "battery_info" in path:
                ctype = "text/json; charset=UTF-8"
        conn.sendall(("HTTP/1.1 %s\r\nConnection: close\r\n"
                      "Content-Type: %s\r\nContent-Length: %d\r\n\r\n"
                      % (status, ctype, len(body))).encode("ascii"))
        conn.sendall(body)
        conn.close()

    def _control_response(self, method, path):
        if path == "/v1/phone/info":
            return json.dumps({"name": "MockPhone", "port": 4748,
                               "codec": self.codec, "bitrate": self.bitrate,
                               "width": 640, "height": 360, "fps": 30,
                               "audio": 1, "audioRate": 44100, "audioChannels": 1,
                               "audioBitrate": 128, "jpegQuality": 85}).encode()
        if path == "/v1/phone/name":
            return b"MockPhone"
        if path == "/v1/phone/battery_info":
            return b'{"level":71,"state":2}'
        if path == "/v1/camera/camera_list":
            return b"Camera 0\nCamera 1\n"
        if path == "/v1/camera/info":
            return json.dumps({"active": self.active_camera, "focusMode": 0,
                               "mfValue": -1, "mfMax": 10,
                               "zmValue": self.zoom if self.zoom > 1 else -1,
                               "zmMin": 1, "zmMax": 8,
                               "evValue": int(self.ev), "evMin": -6, "evMax": 6,
                               "isoValue": -1, "wbMode": self.wb_mode,
                               "wbLock": -1, "wbValue": self.wb_level,
                               "wbMin": 0, "wbMax": 100,
                               "led_on": -1, "aeLock": 0, "ssLock": 0,
                               "isoLock": 0, "audioMute": self.muted}).encode()
        if method != "PUT":
            return None
        # control endpoints
        if path.startswith("/v1/camera/active/"):
            try:
                self.active_camera = int(path.rsplit("/", 1)[1])
            except ValueError:
                pass
            return b""
        if path.startswith("/v3/camera/zoom/"):
            try:
                self.zoom = float(path.rsplit("/", 1)[1])
            except ValueError:
                pass
            return b""
        if path.startswith("/v3/camera/ev/"):
            try:
                self.ev = float(path.rsplit("/", 1)[1])
            except ValueError:
                pass
            return b""
        if path.startswith("/v1/camera/wb_mode/"):
            try:
                self.wb_mode = int(path.rsplit("/", 1)[1])
            except ValueError:
                pass
            return b""
        if path.startswith("/v2/camera/wb_level/"):
            try:
                self.wb_level = int(path.rsplit("/", 1)[1])
            except ValueError:
                pass
            return b""
        if path.startswith("/v1/phone/codec/"):
            self.codec = path.rsplit("/", 1)[1]
            return b""
        if path.startswith("/v1/phone/bitrate/"):
            try:
                self.bitrate = int(path.rsplit("/", 1)[1])
            except ValueError:
                pass
            return b""
        if path == "/v1/camera/mic_toggle":
            self.muted ^= 1
            return b""
        if path == "/v1/camera/autofocus":
            return b""
        if path in ("/v1/stop", "/v1/restart", "/v1/camera/torch_toggle",
                    "/v1/camera/autofocus_mode/0"):
            return b""
        return None

    # ---- streams -------------------------------------------------------------
    def _serve_video(self, conn):
        if self.codec != "jpg":
            # the mock honours the phone's codec setting like the real app
            self._serve_framed(conn, "/v5/video/" + self.codec + "/640x360")
            return
        headers = (b"HTTP/1.1 200 OK\r\n"
                   b"Access-Control-Allow-Origin: *\r\n"
                   b"Content-Type: multipart/x-mixed-replace;boundary=dcmjpeg\r\n"
                   b"Connection: Keep-Alive\r\n\r\n")
        conn.sendall(headers)
        try:
            while self._running:
                with self._lock:
                    jpeg = make_jpeg(self._seq)
                    self._seq += 1
                part = (BOUNDARY + b"\r\n"
                        b"Content-Type: image/jpeg\r\n"
                        b"Content-Length: " + str(len(jpeg)).encode() + b"\r\n\r\n"
                        + jpeg + b"\r\n")
                conn.sendall(part)
                time.sleep(0.05)
        except OSError:
            pass
        try:
            conn.close()
        except OSError:
            pass

    def _serve_framed(self, conn, path):
        """Serve a real H.264/HEVC framed stream: [pts i64][len i32][Annex-B payload]."""
        if not HAS_AV:
            conn.sendall(b"HTTP/1.1 503 No PyAV\r\n\r\n")
            conn.close()
            return
        codec = path.split("/")[3].lower() if len(path.split("/")) > 3 else "avc"
        pkts = self._h264_cache
        if pkts is None:
            pkts = _make_h264_stream(320, 180, 18)
            self._h264_cache = pkts
        if pkts is None:
            conn.sendall(b"HTTP/1.1 503 No encoder\r\n\r\n")
            conn.close()
            return
        try:
            for pts, payload in pkts:
                hdr = struct.pack("<qi", pts, len(payload))
                conn.sendall(hdr + payload)
                time.sleep(0.033)
            # end-of-stream marker
            conn.sendall(struct.pack("<qi", -1, -1))
        except OSError:
            pass
        try:
            conn.close()
        except OSError:
            pass

    def _serve_audio(self, conn):
        # framed: [int64 LE pts][int32 LE len][payload]
        try:
            for i in range(3):
                payload = bytes([0x21, 0x10, 0x04, 0x60, 0x6C, 0x80, 0x00, 0x00,
                                 0x02, 0x90])  # fake AAC-ish bytes
                hdr = struct.pack("<qi", i * 23220, len(payload))
                conn.sendall(hdr + payload)
                time.sleep(0.1)
        except OSError:
            pass
        try:
            conn.close()
        except OSError:
            pass


if __name__ == "__main__":
    srv = MockServer(("127.0.0.1", 4748))
    srv.start()
    print("Mock OpenCam phone serving on http://127.0.0.1:%d/video" % srv.port)
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        srv.stop()
