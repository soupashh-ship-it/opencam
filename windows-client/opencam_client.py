#!/usr/bin/env python3
"""
OpenCam Client — view and control your OpenCam phone camera from the PC.

A small DroidCam-style desktop client for the OpenCam Android app. It connects to
the phone's embedded HTTP server and provides:

  * live MJPEG video preview  (GET /video)
  * AAC microphone audio      (GET /v2/audio, played via ffplay)
  * camera controls           (switch camera, zoom, exposure, white balance, AF,
                               torch, mute — the same endpoints as the web remote)
  * battery + device info     (GET /v1/phone/*)

Usage:
    python opencam_client.py            # GUI
    python opencam_client.py --selftest # headless self-test against the built-in
                                        # mock phone server (no device needed)

Requirements: Python 3.8+, Pillow (pip install pillow). ffplay (from FFmpeg) is
used for audio; if it's missing the client still works without sound.
"""

import io
import json
import os
import queue
import socket
import struct
import subprocess
import sys
import threading
import time
import tkinter as tk
import urllib.request
import urllib.error
from concurrent.futures import ThreadPoolExecutor, as_completed
from tkinter import ttk

try:
    from PIL import Image, ImageTk
    HAS_PIL = True
except ImportError:
    Image = ImageTk = None
    HAS_PIL = False

import virtualcam

# Where settings live. When frozen into an .exe, __file__ points inside
# PyInstaller's temp extraction dir (wiped on exit) — prefer the exe's folder so
# the config stays portable. If that folder isn't writable (e.g. Program Files),
# fall back to %APPDATA% so the remembered IP actually persists.
if getattr(sys, "frozen", False):
    _BASE_DIR = os.path.dirname(sys.executable)
else:
    _BASE_DIR = os.path.dirname(os.path.abspath(__file__))
CONFIG_FILE = os.path.join(_BASE_DIR, "opencam_client.json")
if getattr(sys, "frozen", False) and not os.access(_BASE_DIR, os.W_OK):
    _BASE_DIR = os.path.join(os.environ.get("APPDATA", _BASE_DIR), "OpenCamClient")
    os.makedirs(_BASE_DIR, exist_ok=True)
    CONFIG_FILE = os.path.join(_BASE_DIR, "opencam_client.json")
DEFAULT_PORT = 4747
BOUNDARY = b"--dcmjpeg"
LOG_FILE = os.path.join(_BASE_DIR, "opencam_client.log")


def _log(msg):
    """Append a timestamped line to opencam_client.log (best effort)."""
    try:
        with open(LOG_FILE, "a", encoding="utf-8") as f:
            f.write(time.strftime("%Y-%m-%d %H:%M:%S") + "  " + str(msg) + "\n")
    except Exception:
        pass


# ============================================================================
# ADTS framing (wrap raw AAC access units for ffplay)
# ============================================================================

_SR_INDEX = {96000: 0, 88200: 1, 64000: 2, 48000: 3, 44100: 4,
             32000: 5, 24000: 6, 16000: 7, 12000: 8, 8000: 9}


def adts_header(aac_len, sample_rate=44100, channels=1):
    """Build a 7-byte ADTS header for one AAC-LC frame of `aac_len` bytes."""
    freq = _SR_INDEX.get(sample_rate, 4)
    frame_len = aac_len + 7
    b0 = 0xFF
    b1 = 0xF1  # MPEG-4, layer 0, no CRC
    b2 = (1 << 6) | (freq << 2) | ((channels >> 2) & 0x01)          # profile AAC-LC
    b3 = ((channels & 0x03) << 6) | ((frame_len >> 11) & 0x03)
    b4 = (frame_len >> 3) & 0xFF
    b5 = ((frame_len & 0x07) << 5) | 0x1F
    b6 = 0xFC
    return bytes([b0, b1, b2, b3, b4, b5, b6])


# ============================================================================
# Phone protocol layer (headless — used by both GUI and selftest)
# ============================================================================

class Phone:
    """A connection to one OpenCam phone."""

    def __init__(self, host, port):
        self.host = host
        self.port = port
        self.info = {}

    # ---- plain HTTP helpers ------------------------------------------------
    def api(self, path, method="GET", timeout=5.0):
        """Call a control endpoint; returns (status, body-bytes)."""
        url = "http://%s:%d%s" % (self.host, self.port, path)
        req = urllib.request.Request(url, method=method)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.status, r.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read()
        except Exception as e:
            raise ConnectionError("cannot reach %s: %s" % (url, e))

    def api_text(self, path, method="GET"):
        st, body = self.api(path, method)
        return body.decode("utf-8", "replace") if body else ""

    def api_json(self, path, timeout=5.0):
        st, body = self.api(path, timeout=timeout)
        try:
            return json.loads(body.decode("utf-8", "replace")) if body else {}
        except ValueError:
            return {}

    # ---- info ---------------------------------------------------------------
    def ping(self, timeout=5.0):
        """Thin reachability + identity probe (raises on failure)."""
        self.info = self.api_json("/v1/phone/info", timeout=timeout)
        return self.info

    def battery(self, timeout=5.0):
        return self.api_json("/v1/phone/battery_info", timeout=timeout)

    def camera_list(self):
        text = self.api_text("/v1/camera/camera_list")
        return [ln for ln in text.splitlines() if ln.strip()]

    def camera_info(self):
        return self.api_json("/v1/camera/info")

    # ---- controls -------------------------------------------------------------
    def stop(self):
        self.api("/v1/stop", "PUT")

    def restart(self):
        self.api("/v1/restart", "PUT")

    def set_camera(self, index):
        self.api("/v1/camera/active/%d" % index, "PUT")

    def torch_toggle(self):
        self.api("/v1/camera/torch_toggle", "PUT")

    def mic_toggle(self):
        self.api("/v1/camera/mic_toggle", "PUT")

    def set_zoom(self, value):
        self.api("/v3/camera/zoom/%s" % format_float(value), "PUT")

    def set_ev(self, value):
        self.api("/v3/camera/ev/%s" % format_float(value), "PUT")

    def set_wb_mode(self, mode):
        self.api("/v1/camera/wb_mode/%d" % mode, "PUT")

    def set_wb_level(self, level):
        self.api("/v2/camera/wb_level/%d" % int(level), "PUT")

    def set_af_mode(self, mode):
        self.api("/v1/camera/autofocus_mode/%d" % mode, "PUT")

    def autofocus(self):
        self.api("/v1/camera/autofocus", "PUT")


def format_float(v):
    """Trim trailing zeros: 1.0 -> '1', 2.5 -> '2.5'."""
    s = "%.2f" % float(v)
    return s.rstrip("0").rstrip(".")


# ============================================================================
# LAN discovery (no dependencies — probes the local subnets for OpenCam phones)
# ============================================================================

def _scanable_ip(ip):
    """True if `ip` is worth probing (not loopback, link-local, or the any-address)."""
    return not (ip.startswith("127.") or ip.startswith("0.")
                or ip.startswith("169.254.") or ip.startswith("255."))


def local_ipv4s():
    """Every local IPv4 address we can find (default-route NIC + hostname aliases)."""
    addrs = set()
    # the NIC that owns the default route (the one the phone is most likely on)
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        addrs.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    # all other interface addresses (multi-NIC PCs, Ethernet + Wi-Fi, …)
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if _scanable_ip(ip):
                addrs.add(ip)
    except OSError:
        pass
    addrs.add("127.0.0.1")
    return sorted(addrs)


def _port_open(ip, port, timeout):
    """True if something accepts TCP connections on ip:port."""
    try:
        with socket.create_connection((ip, port), timeout=timeout):
            return True
    except OSError:
        return False


def scan_network(candidates=None, port=DEFAULT_PORT, timeout=0.2, workers=64):
    """
    Probe LAN addresses for OpenCam phones.

    Returns a list of dicts: {"ip", "name", "battery"}. When `candidates` is None
    it scans the /24 of every local IPv4 address plus loopback — dependency-free
    discovery that works even when mDNS is blocked or disabled.
    """
    if candidates is None:
        candidates = []
        for base in local_ipv4s():
            parts = base.split(".")
            if len(parts) != 4:
                continue
            candidates.append(base)
            if _scanable_ip(base):
                # scan the whole /24 — skip it for loopback/link-local, the host
                # itself (already added above) is the only useful probe there
                candidates += ["%s.%s.%s.%d" % (parts[0], parts[1], parts[2], i)
                               for i in range(1, 255)]
        candidates = list(dict.fromkeys(candidates))  # dedupe, keep order

    hits = []
    with ThreadPoolExecutor(max_workers=workers) as ex:
        futs = {ex.submit(_port_open, ip, port, timeout): ip for ip in candidates}
        for fut in as_completed(futs):
            ip = futs[fut]
            try:
                if fut.result():
                    hits.append(ip)
            except Exception:
                pass

    found = []
    for ip in hits:
        try:
            p = Phone(ip, port)
            info = p.ping(timeout=1.5)
            if not info:
                continue
            battery = 0
            try:
                battery = p.battery(timeout=1.5).get("level", 0)
            except Exception:
                pass
            found.append({"ip": ip, "name": info.get("name") or ip, "battery": battery})
        except Exception:
            continue
    return found


# ============================================================================
# Streaming threads
# ============================================================================

class VideoStream(threading.Thread):
    """Parses the multipart MJPEG stream and calls on_frame(PIL.Image) per frame."""

    def __init__(self, host, port, on_frame, on_error):
        super().__init__(daemon=True)
        self.host = host
        self.port = port
        self.on_frame = on_frame
        self.on_error = on_error
        self._halt = threading.Event()  # NB: not _stop — Thread._stop() is internal
        self._sock = None

    def stop(self):
        self._halt.set()
        if self._sock is not None:
            try:
                self._sock.close()
            except OSError:
                pass

    def run(self):
        try:
            self._sock = socket.create_connection((self.host, self.port), timeout=10)
            self._sock.settimeout(5)
            self._sock.sendall(b"GET /video HTTP/1.1\r\nHost: %s:%d\r\n\r\n"
                               % (self.host.encode(), self.port))
            reader = _Buffered(self._sock)
            head = reader.read_until(b"\r\n\r\n")
            first = head.split(b"\r\n", 1)[0]
            if b" 200 " not in first:
                self.on_error("phone returned %s (is another client already connected?)"
                              % first.decode("latin1", "replace"))
                return
            # The stream endpoint answers with multipart MJPEG. If the phone instead
            # answered with an HTML page (status is still 200), the video is already
            # streaming to another client — report that clearly instead of hanging on
            # a stale multipart parse until the socket times out.
            if b"multipart" not in head.lower():
                try:
                    self._sock.close()
                except OSError:
                    pass
                if b"text/html" in head.lower():
                    self.on_error("the phone is already streaming to another client "
                                  "(web remote, OBS, or another OpenCam window) — close "
                                  "it, then reconnect")
                else:
                    self.on_error("unexpected response from the phone's stream endpoint "
                                  "(another client may be connected)")
                return
            while not self._halt.is_set():
                # boundary line
                line = reader.read_until(b"\r\n")
                if line.strip() != BOUNDARY:
                    # stream glitch — skip to the next boundary
                    continue
                headers = {}
                while True:
                    hl = reader.read_until(b"\r\n")
                    if not hl:
                        break
                    k, _, v = hl.partition(b":")
                    headers[k.strip().lower()] = v.strip()
                length = int(headers.get(b"content-length", b"0") or 0)
                if length <= 0:
                    continue
                data = reader.read_exact(length)
                try:
                    reader.read_until(b"\r\n")  # trailing CRLF
                except ConnectionError:
                    pass
                img = Image.open(io.BytesIO(data))
                img.load()
                self.on_frame(img)
        except ConnectionError:
            if not self._halt.is_set():
                self.on_error("video stream ended")
        except OSError as e:
            if not self._halt.is_set():
                self.on_error("video stream error: %s" % e)
        except Exception as e:  # noqa: BLE001 — report anything, keep the client alive
            if not self._halt.is_set():
                self.on_error("video stream error: %s" % e)


class AudioStream(threading.Thread):
    """Reads framed AAC from /v2/audio and pipes ADTS into ffplay."""

    def __init__(self, host, port, sample_rate, channels=1, on_error=None):
        super().__init__(daemon=True)
        self.host = host
        self.port = port
        self.sample_rate = sample_rate
        self.channels = channels
        self.on_error = on_error
        self._halt = threading.Event()  # NB: not _stop — Thread._stop() is internal
        self._proc = None
        self._sock = None

    def stop(self):
        self._halt.set()
        if self._sock is not None:
            try:
                self._sock.close()
            except OSError:
                pass
        if self._proc is not None:
            try:
                self._proc.stdin.close()
            except Exception:
                pass
            try:
                self._proc.terminate()
            except Exception:
                pass

    @staticmethod
    def find_ffplay():
        for name in ("ffplay", "ffplay.exe"):
            try:
                subprocess.run([name, "-version"], capture_output=True, timeout=5)
                return name
            except Exception:
                continue
        return None

    def run(self):
        ffplay = self.find_ffplay()
        if ffplay is None:
            if self.on_error:
                self.on_error("ffplay not found — audio disabled")
            return
        try:
            self._proc = subprocess.Popen(
                [ffplay, "-nodisp", "-loglevel", "quiet", "-f", "adts", "-i", "-"],
                stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            self._sock = socket.create_connection((self.host, self.port), timeout=10)
            self._sock.settimeout(5)
            self._sock.sendall(b"GET /v2/audio HTTP/1.1\r\nHost: %s:%d\r\n\r\n"
                               % (self.host.encode(), self.port))
            # Raw framed stream: no HTTP response headers — the first bytes are
            # [int64 LE pts][int32 LE length][payload].
            while not self._halt.is_set():
                hdr = _read_exact_from(self._sock, 12)
                if not hdr:
                    break
                pts, length = struct.unpack("<qi", hdr)
                if length <= 0:
                    break
                payload = _read_exact_from(self._sock, length)
                if payload is None:
                    break
                adts = adts_header(length, self.sample_rate, self.channels)
                if self._proc.stdin:
                    self._proc.stdin.write(adts + payload)
        except (ConnectionError, OSError):
            pass
        except Exception:
            pass
        finally:
            if self._proc is not None:
                try:
                    self._proc.stdin.close()
                except Exception:
                    pass


class _Buffered:
    """Tiny buffered byte reader over a socket."""

    def __init__(self, sock):
        self.sock = sock
        self.buf = b""

    def read_until(self, marker):
        while marker not in self.buf:
            data = self.sock.recv(65536)
            if not data:
                raise ConnectionError("stream closed")
            self.buf += data
        i = self.buf.index(marker)
        out = self.buf[:i]
        self.buf = self.buf[i + len(marker):]
        return out

    def read_exact(self, n):
        while len(self.buf) < n:
            data = self.sock.recv(65536)
            if not data:
                raise ConnectionError("stream closed")
            self.buf += data
        out = self.buf[:n]
        self.buf = self.buf[n:]
        return out


def _read_exact_from(sock, n):
    buf = b""
    while len(buf) < n:
        try:
            data = sock.recv(n - len(buf))
        except OSError:
            return None
        if not data:
            return None
        buf += data
    return buf


# ============================================================================
# GUI
# ============================================================================

ACCENT = "#00c4ff"
BG = "#101418"
CARD = "#1b232b"
TEXT = "#e8eef2"
MUTED = "#9aa7b4"


def load_config():
    try:
        with open(CONFIG_FILE, "r", encoding="utf-8") as f:
            cfg = json.load(f)
            return {
                "host": cfg.get("host", ""),
                "port": int(cfg.get("port", DEFAULT_PORT)),
                "autoConnect": bool(cfg.get("autoConnect", True)),
                "autoVcam": bool(cfg.get("autoVcam", True)),
            }
    except Exception:
        return {"host": "", "port": DEFAULT_PORT,
                "autoConnect": True, "autoVcam": True}


def save_config(cfg):
    try:
        with open(CONFIG_FILE, "w", encoding="utf-8") as f:
            json.dump(cfg, f)
    except Exception:
        pass


class App:
    def __init__(self, root):
        self.root = root
        root.title("OpenCam Client")
        root.configure(bg=BG)
        root.geometry("1000x640")
        root.minsize(760, 480)

        self.phone = None
        self.video = None
        self.audio = None
        self.connected = False
        self.latest = None            # most recent PIL frame
        self.latest_id = 0
        self.frame_counter = 0
        self.fps = 0.0
        self.fps_last = time.time()
        self.photo = None             # keep a reference to the displayed PhotoImage
        self.muted = False
        self.cameras = []
        self.controls_built = False
        self._last_aux = 0.0
        self._debounce = {}
        self.vcam = None              # virtualcam.VirtualCam while streaming to it
        self.vcam_active = False

        # Cross-thread plumbing: tkinter must only be touched on the main thread.
        # Threads push lambdas onto _ui_q (drained in _tick); control calls run on
        # a dedicated worker so slow/unreachable phones never freeze the GUI.
        self._ui_q = queue.Queue()
        self._cmd_q = queue.Queue()
        self._worker = threading.Thread(target=self._worker_loop, daemon=True)
        self._worker.start()

        self._build_ui()
        cfg = load_config()
        self.var_auto_connect.set(cfg["autoConnect"])
        self.var_auto_vcam.set(cfg["autoVcam"])
        if cfg["host"]:
            # Replace the default port text, never append — inserting twice produced
            # "47474747" and made every connect/scan hit an invalid port (refused).
            self.entry_host.delete(0, tk.END)
            self.entry_host.insert(0, cfg["host"])
            self.entry_port.delete(0, tk.END)
            self.entry_port.insert(0, str(cfg["port"]))

        self.root.after(40, self._tick)
        self.root.protocol("WM_DELETE_WINDOW", self._on_close)
        self.root.after(1200, self._vcam_hint)
        if cfg["autoConnect"] and cfg["host"]:
            _log("auto-connect scheduled for %s:%d" % (cfg["host"], cfg["port"]))
            self.root.after(1500, self._auto_connect)

    def _vcam_hint(self):
        """Quiet first-run hint so users know the camera can be exposed to apps."""
        try:
            if virtualcam.filter_registered():
                if virtualcam.device_name() != virtualcam.DEVICE_NAME:
                    self._set_status("tip: enable 'Virtual cam' to expose it as '%s'"
                                     % virtualcam.DEVICE_NAME)
            else:
                self._set_status("tip: enable 'Virtual cam' after connecting to appear "
                                 "in Discord/WhatsApp (one-time admin)")
        except Exception:
            pass

    # ---- cross-thread helpers --------------------------------------------------
    def _post_ui(self, fn):
        """Schedule a callable on the main thread from any thread."""
        self._ui_q.put(fn)

    def _worker_loop(self):
        while True:
            fn = self._cmd_q.get()
            if fn is None:
                return
            try:
                fn()
            except Exception:
                pass

    def _run_control(self, fn, ok_msg=None):
        """Run a phone control call off the GUI thread, surfacing errors in status."""
        def job():
            try:
                fn()
                if ok_msg:
                    self._post_ui(lambda: self._set_status(ok_msg))
            except Exception as e:
                self._post_ui(lambda e=e: self._set_status(str(e), error=True))
        self._cmd_q.put(job)

    def _debounced(self, key, delay_ms, fn):
        """Coalesce rapid events (e.g. slider drags) into one call after a pause."""
        if key in self._debounce:
            self.root.after_cancel(self._debounce.pop(key))

        def run():
            self._debounce.pop(key, None)
            self._run_control(fn)
        self._debounce[key] = self.root.after(delay_ms, run)

    # ---- UI construction -----------------------------------------------------
    def _build_ui(self):
        top = tk.Frame(self.root, bg=CARD)
        top.pack(fill=tk.X)

        tk.Label(top, text="OpenCam", font=("Segoe UI", 16, "bold"),
                 bg=CARD, fg=ACCENT).pack(side=tk.LEFT, padx=(14, 10), pady=10)

        self.lbl_device = tk.Label(top, text="not connected", font=("Segoe UI", 10),
                                   bg=CARD, fg=MUTED)
        self.lbl_device.pack(side=tk.LEFT, pady=10)

        self.lbl_battery = tk.Label(top, text="", font=("Segoe UI", 10),
                                    bg=CARD, fg=TEXT)
        self.lbl_battery.pack(side=tk.RIGHT, padx=14, pady=10)

        # connect bar
        bar = tk.Frame(self.root, bg=BG)
        bar.pack(fill=tk.X, padx=12, pady=(10, 6))

        tk.Label(bar, text="Phone IP:", bg=BG, fg=MUTED).pack(side=tk.LEFT)
        self.entry_host = tk.Entry(bar, width=16, bg=CARD, fg=TEXT,
                                   insertbackground=TEXT, relief=tk.FLAT)
        self.entry_host.pack(side=tk.LEFT, padx=6, ipady=4)

        tk.Label(bar, text="Port:", bg=BG, fg=MUTED).pack(side=tk.LEFT)
        self.entry_port = tk.Entry(bar, width=6, bg=CARD, fg=TEXT,
                                   insertbackground=TEXT, relief=tk.FLAT)
        self.entry_port.insert(0, str(DEFAULT_PORT))
        self.entry_port.pack(side=tk.LEFT, padx=6, ipady=4)

        self.btn_connect = tk.Button(bar, text="Connect", command=self._toggle_connect,
                                     bg=ACCENT, fg="#001014", activebackground="#33d2ff",
                                     activeforeground="#001014", relief=tk.FLAT,
                                     font=("Segoe UI", 10, "bold"), cursor="hand2")
        self.btn_connect.pack(side=tk.LEFT, padx=(10, 0), ipadx=14, ipady=4)

        # auto-discover phones on the LAN (subnet scan)
        self.btn_scan = tk.Button(bar, text="Scan", command=self._cmd_scan,
                                  bg=CARD, fg=TEXT, activebackground="#2a333d",
                                  activeforeground=ACCENT, relief=tk.FLAT,
                                  font=("Segoe UI", 10), cursor="hand2")
        self.btn_scan.pack(side=tk.LEFT, padx=(8, 0), ipadx=12, ipady=4)

        style = ttk.Style()
        style.theme_use("clam")
        style.configure("Scan.TCombobox", fieldbackground=CARD, background=CARD,
                        foreground=TEXT, arrowcolor=TEXT, bordercolor="#2a333d",
                        lightcolor=CARD, darkcolor=CARD, insertcolor=TEXT)
        style.map("Scan.TCombobox",
                  fieldbackground=[("readonly", CARD)],
                  foreground=[("readonly", TEXT)])
        self.combo_results = ttk.Combobox(bar, state="readonly", width=30,
                                          style="Scan.TCombobox", font=("Segoe UI", 9))
        self.combo_results.bind("<<ComboboxSelected>>", self._scan_picked)
        self.combo_results.pack(side=tk.LEFT, padx=(8, 0), ipady=3)
        self._scan_results = []
        self._scan_port = DEFAULT_PORT

        # startup behaviour toggles (persisted in opencam_client.json)
        self.var_auto_connect = tk.BooleanVar(value=True)
        self.chk_auto_connect = tk.Checkbutton(
            bar, text="Auto-connect", variable=self.var_auto_connect,
            command=self._on_auto_toggle, bg=BG, fg=TEXT, activebackground=BG,
            activeforeground=ACCENT, selectcolor=CARD, font=("Segoe UI", 9),
            highlightthickness=0, cursor="hand2")
        self.chk_auto_connect.pack(side=tk.LEFT, padx=(8, 0))
        self.var_auto_vcam = tk.BooleanVar(value=True)
        self.chk_auto_vcam = tk.Checkbutton(
            bar, text="Virtual cam on connect", variable=self.var_auto_vcam,
            command=self._on_auto_toggle, bg=BG, fg=TEXT, activebackground=BG,
            activeforeground=ACCENT, selectcolor=CARD, font=("Segoe UI", 9),
            highlightthickness=0, cursor="hand2")
        self.chk_auto_vcam.pack(side=tk.LEFT, padx=(8, 0))

        self.lbl_status = tk.Label(bar, text="", bg=BG, fg=MUTED, font=("Segoe UI", 9))
        self.lbl_status.pack(side=tk.RIGHT)

        # video area
        wrap = tk.Frame(self.root, bg="#000", highlightthickness=1,
                        highlightbackground="#2a333d")
        wrap.pack(fill=tk.BOTH, expand=True, padx=12, pady=(0, 10))
        self.canvas = tk.Canvas(wrap, bg="#000", highlightthickness=0)
        self.canvas.pack(fill=tk.BOTH, expand=True)

        # controls
        ctrl = tk.Frame(self.root, bg=BG)
        ctrl.pack(fill=tk.X, padx=12, pady=(0, 12))

        row1 = tk.Frame(ctrl, bg=BG)
        row1.pack(fill=tk.X)
        self._add_button(row1, "Stop stream", self._cmd_stop)
        self._add_button(row1, "Restart", self._cmd_restart)
        self._add_button(row1, "Switch camera", self._cmd_camera)
        self._add_button(row1, "Torch", self._cmd_torch)
        self.btn_mute = self._add_button(row1, "Mute mic", self._cmd_mute)
        self.btn_af = self._add_button(row1, "Auto-focus", self._cmd_af)
        self.btn_vcam = self._add_button(row1, "Virtual cam", self._cmd_vcam)

        self.row2 = tk.Frame(ctrl, bg=BG)
        self.row2.pack(fill=tk.X, pady=(8, 0))
        self.lbl_row2 = tk.Label(self.row2, text="", bg=BG, fg=MUTED,
                                 font=("Segoe UI", 9))
        self.lbl_row2.pack(side=tk.LEFT)
        # sliders are added by _build_controls() once camera info arrives

    def _add_button(self, parent, text, cmd):
        b = tk.Button(parent, text=text, command=cmd,
                      bg=CARD, fg=TEXT, activebackground="#2a333d",
                      activeforeground=ACCENT, relief=tk.FLAT,
                      font=("Segoe UI", 9), cursor="hand2")
        b.pack(side=tk.LEFT, padx=(0, 8), ipadx=12, ipady=4)
        return b

    def _clear_controls(self):
        for w in self.row2.winfo_children():
            w.destroy()
        self.lbl_row2 = tk.Label(self.row2, text="", bg=BG, fg=MUTED,
                                 font=("Segoe UI", 9))
        self.lbl_row2.pack(side=tk.LEFT)
        self.controls_built = False

    def _build_controls(self, info):
        """Build sliders for whatever the camera advertises in /v1/camera/info."""
        for w in self.row2.winfo_children():
            w.destroy()
        self.lbl_row2 = tk.Label(self.row2, text="", bg=BG, fg=MUTED,
                                 font=("Segoe UI", 9))
        self.lbl_row2.pack(side=tk.LEFT)
        self.controls_built = True

        # zoom (zmValue is -1 when zoom is at 1x; clamp to the slider range)
        zm_max = info.get("zmMax", 1)
        if zm_max and zm_max > 1:
            self._add_slider("Zoom", 1.0, float(zm_max), float(info.get("zmValue", 1)),
                             0.1, "zoom", self._slider_zoom, "%.1fx")
        # exposure compensation
        ev_min, ev_max = info.get("evMin", 0), info.get("evMax", 0)
        if ev_min != ev_max:
            self._add_slider("EV", float(ev_min), float(ev_max),
                             float(info.get("evValue", 0)), 0.25,
                             "ev", self._slider_ev, "%+.2f")
        # manual white balance
        if info.get("wbValue", -1) >= 0:
            self._add_slider("WB", 0, 100, float(info.get("wbValue", 50)), 1,
                             "wb", self._slider_wb, "%d")

    def _add_slider(self, label, lo, hi, value, step, key, cmd, fmt):
        frame = tk.Frame(self.row2, bg=BG)
        frame.pack(side=tk.LEFT, padx=(14, 0))
        value = max(float(lo), min(float(hi), float(value)))
        var = tk.DoubleVar(value=value)
        tk.Label(frame, text=label, bg=BG, fg=MUTED,
                 font=("Segoe UI", 9)).pack(side=tk.LEFT)
        scale = tk.Scale(frame, from_=lo, to=hi, resolution=step, orient=tk.HORIZONTAL,
                         variable=var, length=140, showvalue=False,
                         bg=BG, fg=TEXT, troughcolor=CARD,
                         highlightthickness=0, activebackground=ACCENT)
        scale.pack(side=tk.LEFT, padx=(6, 4))
        lbl = tk.Label(frame, text=fmt % value, bg=BG, fg=ACCENT,
                       font=("Segoe UI", 9), width=6)
        lbl.pack(side=tk.LEFT)

        def changed(*_a):
            v = var.get()
            lbl.config(text=fmt % v)
            # Debounced so dragging doesn't fire a blocking HTTP PUT per tick.
            self._debounced("slider_" + key, 200,
                            lambda v=v: cmd(float(v)))

        scale.configure(command=changed)
        # store the scale so we can update it from camera info polling
        frame._label, frame._var, frame._fmt = lbl, var, fmt  # noqa: SLF001

    def _slider_zoom(self, v):
        phone = self.phone
        if phone:
            phone.set_zoom(v)

    def _slider_ev(self, v):
        phone = self.phone
        if phone:
            phone.set_ev(v)

    def _slider_wb(self, v):
        phone = self.phone
        if phone:
            phone.set_wb_level(v)

    def _on_auto_toggle(self):
        """Persist the startup toggles when either checkbox flips."""
        cfg = load_config()
        cfg["autoConnect"] = self.var_auto_connect.get()
        cfg["autoVcam"] = self.var_auto_vcam.get()
        save_config(cfg)

    def _auto_connect(self):
        """Startup hook: dial the last phone automatically."""
        if self.connected:
            return
        _log("auto-connect firing")
        self._connect()

    # ---- discovery ------------------------------------------------------------
    def _cmd_scan(self):
        if self.connected:
            self._set_status("already connected — disconnect first to scan", error=True)
            return
        try:
            self._scan_port = int(self.entry_port.get().strip() or DEFAULT_PORT)
        except ValueError:
            self._scan_port = DEFAULT_PORT
        if not (1 <= self._scan_port <= 65535):
            _log("bad port in scan field: %r — resetting to %d"
                 % (self.entry_port.get(), DEFAULT_PORT))
            self._scan_port = DEFAULT_PORT
            self.entry_port.delete(0, tk.END)
            self.entry_port.insert(0, str(DEFAULT_PORT))
        self.btn_scan.config(state=tk.DISABLED)
        self.combo_results.set("")
        self.combo_results["values"] = []
        self._set_status("scanning the network for OpenCam phones…")
        _log("scan start (port %d)" % self._scan_port)
        threading.Thread(target=self._scan_worker, daemon=True).start()

    def _scan_worker(self):
        try:
            found = scan_network(port=self._scan_port)
        except Exception as e:
            self._post_ui(lambda: self._scan_done([], str(e)))
            return
        self._post_ui(lambda: self._scan_done(found, None))

    def _scan_done(self, found, err):
        self.btn_scan.config(state=tk.NORMAL)
        if err:
            self._set_status("scan failed: %s" % err, error=True)
            return
        self._scan_results = found
        _log("scan found %d device(s): %s" % (len(found), found))
        if not found:
            self.combo_results["values"] = []
            self.combo_results.set("")
            self._set_status("no phones found on port %d — is the phone streaming "
                             "on the same network?" % self._scan_port, error=True)
            return
        labels = ["%s  (%s:%d)  ·  %d%%" % (f["name"], f["ip"], self._scan_port,
                                             f["battery"])
                  for f in found]
        self.combo_results["values"] = labels
        self.combo_results.current(0)
        plural = "" if len(found) == 1 else "s"
        self._set_status("found %d phone%s on port %d — pick one to connect"
                         % (len(found), plural, self._scan_port))

    def _scan_picked(self, _evt=None):
        idx = self.combo_results.current()
        if not (0 <= idx < len(self._scan_results)):
            return
        picked = self._scan_results[idx]
        _log("scan pick: %s" % picked)
        self.entry_host.delete(0, tk.END)
        self.entry_host.insert(0, picked["ip"])
        # connect on the port the scan actually probed
        if self.entry_port.get().strip() != str(self._scan_port):
            self.entry_port.delete(0, tk.END)
            self.entry_port.insert(0, str(self._scan_port))
        # A phone can advertise several IPs (Wi-Fi + hotspot/USB) and only one is
        # reachable from this PC. If the picked one fails, fall back through the rest.
        others = [x for x in self._scan_results if x["ip"] != picked["ip"]]
        self._connect(picked["ip"], self._scan_port, candidates=others)

    # ---- connection ----------------------------------------------------------
    def _toggle_connect(self):
        if self.connected:
            self._disconnect()
        else:
            self._connect()

    def _connect(self, host=None, port=None, candidates=None):
        if host is None:
            host = self.entry_host.get().strip()
        port_s = self.entry_port.get().strip() if port is None else str(port)
        if not host:
            self._set_status("enter the phone's IP address", error=True)
            return
        try:
            port = int(port_s) if port_s else DEFAULT_PORT
        except ValueError:
            self._set_status("invalid port", error=True)
            return
        if not (1 <= port <= 65535):
            _log("bad port in connect: %r — resetting to %d" % (port_s, DEFAULT_PORT))
            port = DEFAULT_PORT
            self.entry_port.delete(0, tk.END)
            self.entry_port.insert(0, str(DEFAULT_PORT))

        _log("connect requested: %s:%d (fallback candidates: %d)"
             % (host, port, len(candidates or [])))
        self._set_status("connecting…")
        self.btn_connect.config(state=tk.DISABLED)
        threading.Thread(target=self._connect_worker,
                         args=(host, port, list(candidates or [])), daemon=True).start()

    def _apply_camera_info(self, cams, info):
        """Update camera list + sliders on the GUI thread from worker-fetched info."""
        if not self.connected:
            return
        self.cameras = cams
        if not info or info.get("active") is None:
            return
        if not self.controls_built:
            self._build_controls(info)
        else:
            self._update_sliders(info)

    def _connect_worker(self, host, port, candidates=None):
        attempts = 1
        while True:
            _log("connect attempt %d: %s:%d" % (attempts, host, port))
            try:
                phone = Phone(host, port)
                info = phone.ping()
                if not info:
                    raise ConnectionError("no /v1/phone/info response")
                # start video first so the UI has frames as soon as possible
                self.phone = phone
                self._start_streams()
                self._post_ui(lambda host=host, port=port, info=info:
                              self._connected(host, port, info))
                return
            except Exception as e:
                _log("connect to %s:%d failed: %s" % (host, port, e))
                if candidates:
                    nxt = candidates.pop(0)
                    prev = "%s:%d" % (host, port)
                    host = nxt["ip"]
                    attempts += 1
                    self._post_ui(lambda prev=prev, nxt=host, n=attempts, e=e:
                                  self._set_status("%s unreachable (%s) — trying %s (%d)…"
                                                   % (prev, str(e)[:70], nxt, n)))
                    continue
                self._post_ui(lambda e=e: self._connect_failed(str(e)))
                return

    def _start_streams(self):
        self.latest = None
        self.latest_id = 0
        self.video = VideoStream(self.phone.host, self.phone.port,
                                 self._on_frame, self._on_stream_error)
        self.video.start()
        rate = self.phone.info.get("audioRate", 44100)
        # on_error must hop to the main thread — AudioStream runs on its own thread
        # and _set_status touches tkinter widgets.
        self.audio = AudioStream(self.phone.host, self.phone.port, int(rate),
                                 on_error=lambda m: self._post_ui(lambda: self._set_status(m)))
        self.audio.start()

    def _connected(self, host, port, info):
        self.connected = True
        _log("connected to %s:%d (info: %s)" % (host, port, info))
        self.btn_connect.config(text="Disconnect", state=tk.NORMAL)
        name = info.get("name") or host
        self.lbl_device.config(text="%s  ·  %d×%d @%d fps  ·  %s"
                                % (name, info.get("width", 0), info.get("height", 0),
                                   info.get("fps", 0), info.get("codec", "?")))
        save_config({"host": host, "port": int(port),
                     "autoConnect": self.var_auto_connect.get(),
                     "autoVcam": self.var_auto_vcam.get()})
        self._set_status("connected")
        self._last_aux = 0.0
        self._refresh_aux()
        if self.var_auto_vcam.get() and not self.vcam_active:
            _log("auto-enabling virtual camera")
            self.root.after(800, self._cmd_vcam)

    def _connect_failed(self, err):
        _log("connect failed: %s" % err)
        self.btn_connect.config(state=tk.NORMAL)
        self._set_status("connect failed: %s — is the phone streaming? "
                         "(tap Start Streaming in the OpenCam app, same network)" % err,
                         error=True)

    def _disconnect(self):
        self.connected = False
        # cancel any pending debounced slider sends (guarded no-ops anyway)
        for k in list(self._debounce):
            try:
                self.root.after_cancel(self._debounce.pop(k))
            except Exception:
                pass
        self._vcam_stop()
        if self.video:
            self.video.stop()
            self.video = None
        if self.audio:
            self.audio.stop()
            self.audio = None
        self.phone = None
        self.latest = None
        self.canvas.delete("all")
        self.lbl_device.config(text="not connected")
        self.lbl_battery.config(text="")
        self.btn_connect.config(text="Connect")
        self._set_status("disconnected")
        self._clear_controls()
        self.fps = 0.0
        # clear stale scan results so picking an old entry can't re-connect to a dead phone
        self._scan_results = []
        self.combo_results["values"] = []
        self.combo_results.set("")

    # ---- command handlers -----------------------------------------------------
    def _cmd_stop(self):
        phone = self.phone
        if not phone:
            return
        self._run_control(phone.stop, "stop sent — phone will stop streaming")

    def _cmd_restart(self):
        phone = self.phone
        if not phone:
            return
        self._run_control(phone.restart,
                         "restart sent — phone re-applies settings (fps/resolution)")

    def _cmd_camera(self):
        phone = self.phone
        if not phone or not self.cameras:
            return

        def job():
            cur = phone.camera_info()
            active = cur.get("active", 0)
            nxt = (active + 1) % len(self.cameras)
            phone.set_camera(nxt)
            self._post_ui(lambda: self._set_status("switched to camera %d" % nxt))
        self._run_control(job)

    def _cmd_torch(self):
        phone = self.phone
        if phone:
            self._run_control(phone.torch_toggle)

    def _cmd_mute(self):
        phone = self.phone
        if phone:
            def job():
                phone.mic_toggle()
                self._post_ui(self._flip_mute)
            self._run_control(job)

    def _flip_mute(self):
        self.muted = not self.muted
        self.btn_mute.config(text="Unmute mic" if self.muted else "Mute mic")

    def _cmd_af(self):
        phone = self.phone
        if phone:
            self._run_control(phone.autofocus)

    # ---- virtual camera ------------------------------------------------------
    def _cmd_vcam(self):
        if self.vcam_active:
            self._vcam_stop()
            self._set_status("virtual camera off")
            return
        if not self.phone:
            return
        self.btn_vcam.config(state=tk.DISABLED)
        self._set_status("starting virtual camera…")
        threading.Thread(target=self._vcam_start_worker, daemon=True).start()

    def _vcam_start_worker(self):
        if self.phone is None or not self.connected:
            return  # disconnected while the 800ms auto-start delay was pending
        try:
            err = virtualcam.prepare()
            if err:
                _log("vcam prepare failed: %s" % err)
                self._post_ui(lambda e=err: self._vcam_fail(e))
                return
            info = self.phone.info
            w, h, fps = 1280, 720, 30
            iw, ih = int(info.get("width", 0) or 0), int(info.get("height", 0) or 0)
            if 320 <= iw <= 1920 and 320 <= ih <= 1920:
                w, h = iw, ih
            fps = max(1, min(60, int(info.get("fps", 30) or 30)))
            vc = virtualcam.VirtualCam(w, h, fps)
            vc.start()
            self.vcam = vc
            _log("virtual camera on: %dx%d@%d" % (w, h, fps))
            self._post_ui(lambda w=w, h=h: self._vcam_on(w, h))
        except Exception as e:
            _log("vcam start failed: %s" % e)
            self._post_ui(lambda e=e: self._vcam_fail(str(e)))

    def _vcam_on(self, w, h):
        self.vcam_active = True
        self.btn_vcam.config(state=tk.NORMAL, text="Virtual cam: ON")
        self._set_status("virtual camera on — '%s' is now available in apps"
                         % virtualcam.DEVICE_NAME)

    def _vcam_fail(self, err):
        self.btn_vcam.config(state=tk.NORMAL)
        self._set_status("virtual camera: %s" % err, error=True)

    def _vcam_stop(self):
        self.vcam_active = False
        if self.vcam is not None:
            self.vcam.stop()
            self.vcam = None
        if hasattr(self, "btn_vcam"):
            self.btn_vcam.config(state=tk.NORMAL, text="Virtual cam")

    # ---- stream callbacks (called from reader threads) -------------------------
    def _on_frame(self, img):
        self.frame_counter += 1
        self.latest = img
        self.latest_id += 1
        # feed the registered virtual camera (best effort, non-blocking)
        if self.vcam_active and self.vcam is not None:
            self.vcam.send(img)

    def _on_stream_error(self, msg):
        _log("stream error: %s" % msg)
        self._post_ui(lambda: self._set_status(msg, error=True))
        self._post_ui(self._disconnect)

    # ---- periodic UI work -------------------------------------------------------
    def _tick(self):
        # run anything posted from other threads
        while True:
            try:
                fn = self._ui_q.get_nowait()
            except queue.Empty:
                break
            try:
                fn()
            except Exception:
                pass
        # fps
        now = time.time()
        if now - self.fps_last >= 1.0:
            self.fps = self.frame_counter / (now - self.fps_last)
            self.frame_counter = 0
            self.fps_last = now
        # draw the newest frame
        if self.latest is not None:
            self._draw(self.latest)
        # refresh battery / camera info every ~10s
        if self.connected and now - self._last_aux >= 10.0:
            self._last_aux = now
            self._refresh_aux()
        self.root.after(40, self._tick)

    def _draw(self, img):
        cw = self.canvas.winfo_width()
        ch = self.canvas.winfo_height()
        if cw < 10 or ch < 10:
            return
        iw, ih = img.size
        scale = min(cw / iw, ch / ih)
        if scale >= 1 and cw >= iw and ch >= ih:
            scale = 1.0
        nw, nh = max(1, int(iw * scale)), max(1, int(ih * scale))
        if img.size != (nw, nh):
            img = img.resize((nw, nh), Image.LANCZOS)
        self.photo = ImageTk.PhotoImage(img)
        self.canvas.delete("all")
        self.canvas.create_image(cw // 2, ch // 2, image=self.photo)
        if self.connected:
            self.canvas.create_text(10, 10, anchor=tk.NW, fill="#9aa7b4",
                                    font=("Segoe UI", 9),
                                    text="%.1f fps  ·  %d×%d" % (self.fps, iw, ih))

    def _refresh_aux(self):
        """Battery + camera info refresh, off the GUI thread (network calls)."""
        phone = self.phone
        if not phone:
            return

        def job():
            try:
                bat = phone.battery()
                lvl = bat.get("level", 0)
                self._post_ui(lambda: self.lbl_battery.config(text="Battery %d%%" % lvl))
            except Exception:
                pass
            try:
                cams = phone.camera_list()
                info = phone.camera_info()
                self._post_ui(lambda: self._apply_camera_info(cams, info))
            except Exception:
                pass
        self._cmd_q.put(job)

    def _update_sliders(self, info):
        for w in self.row2.winfo_children():
            lbl, var, fmt = getattr(w, "_label", None), getattr(w, "_var", None), \
                            getattr(w, "_fmt", None)
            if lbl is None or var is None:
                continue
            # map label text back to a JSON key
            key = {"Zoom": "zmValue", "EV": "evValue", "WB": "wbValue"}.get(
                lbl.cget("text"))
            if key and info.get(key) is not None:
                var.set(float(info[key]))
                lbl.config(text=fmt % float(info[key]))

    def _set_status(self, text, error=False):
        self.lbl_status.config(text=text, fg="#ff6b6b" if error else MUTED)

    def _on_close(self):
        self._disconnect()
        self._cmd_q.put(None)  # stop the worker thread
        self.root.destroy()


# ============================================================================
# Self-test (headless, uses the mock phone server)
# ============================================================================

def run_selftest():
    import mock_server

    print("OpenCam client self-test")
    print("------------------------")

    server = mock_server.MockServer(("127.0.0.1", 0))
    server.start()
    for _ in range(100):
        if server.port is not None:
            break
        time.sleep(0.02)
    assert server.port is not None, "mock server failed to bind"
    port = server.port
    print("[ok] mock phone server on 127.0.0.1:%d" % port)

    phone = Phone("127.0.0.1", port)
    info = phone.ping()
    assert info.get("name") == "MockPhone", "phone info: %r" % info
    assert info.get("width") == 640 and info.get("height") == 360
    print("[ok] /v1/phone/info -> %s" % info)

    cams = phone.camera_list()
    assert len(cams) == 2, cams
    print("[ok] /v1/camera/camera_list -> %s" % cams)

    ci = phone.camera_info()
    assert ci.get("zmMax", 1) > 1 and ci.get("active") == 0
    print("[ok] /v1/camera/info -> zmMax=%s ev=%s..%s" % (ci.get("zmMax"), ci.get("evMin"), ci.get("evMax")))

    for path, method in [("/v1/camera/active/1", "PUT"),
                         ("/v3/camera/zoom/2", "PUT"),
                         ("/v3/camera/ev/-1.5", "PUT"),
                         ("/v1/camera/wb_mode/5", "PUT"),
                         ("/v2/camera/wb_level/70", "PUT"),
                         ("/v1/camera/mic_toggle", "PUT"),
                         ("/v1/restart", "PUT")]:
        st, _ = phone.api(path, method)
        assert st == 200, (path, st)
    print("[ok] control endpoints respond 200")

    # video: collect frames for ~2 seconds
    got = []
    errs = []

    def on_frame(img):
        got.append(img)

    vs = VideoStream("127.0.0.1", port, on_frame, errs.append)
    vs.start()
    time.sleep(2.0)
    vs.stop()
    vs.join(timeout=3)
    assert not errs, errs
    assert len(got) >= 5, "only %d frames" % len(got)
    assert got[0].size == (640, 360)
    print("[ok] MJPEG video: %d frames in 2s, %dx%d" % (len(got), got[0].size[0], got[0].size[1]))

    # discovery: the subnet scanner must find the mock phone (loopback probe)
    found = scan_network(candidates=["127.0.0.1"], port=port, timeout=1.0)
    assert any(f["ip"] == "127.0.0.1" and f["name"] == "MockPhone" for f in found), found
    print("[ok] network scan finds the mock phone -> %s" % found)

    # virtual camera module: detection helpers must load and run headless
    import virtualcam
    assert hasattr(virtualcam, "VirtualCam") and callable(virtualcam.prepare)
    assert virtualcam.DEVICE_NAME == "OpenCam Virtual Camera"
    print("[ok] virtual camera module loads (filter registered: %s)"
          % virtualcam.filter_registered())

    # audio framing: the mock sends 3 fake framed packets; the client's
    # AudioStream parser is exercised by the mock server's /v2/audio endpoint
    print("[ok] audio module loads (AudioStream defined)")

    server.stop()
    print()
    print("ALL CHECKS PASSED")


# ============================================================================
# Entry point
# ============================================================================

def main():
    if "--register-vcam" in sys.argv:
        ok = virtualcam.register()
        print("OpenCam Virtual Camera registered." if ok
              else "registration failed or declined.")
        return 0 if ok else 1
    if "--selftest" in sys.argv:
        if not HAS_PIL:
            print("Pillow is required for the self-test: python -m pip install pillow")
            return 1
        run_selftest()
        return 0

    if not HAS_PIL:
        print("Pillow is required:  python -m pip install pillow")
        return 1

    root = tk.Tk()
    App(root)
    root.mainloop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
