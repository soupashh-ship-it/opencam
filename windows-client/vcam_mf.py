"""
OpenCam Media Foundation virtual camera (Windows 11 22H2+).

Creates a REAL camera — "OpenCam Virtual Camera" — with MFCreateVirtualCamera
(user-mode only: no kernel driver, no code-signing). It shows up in BOTH the
UWP enumeration (WhatsApp, Teams, Windows Camera) and DirectShow (Discord,
OBS, Chrome) because it registers as a PnP software camera (SWD\\VCAMDEVAPI).

Architecture
------------
  opencam-vcam.exe        calls MFCreateVirtualCamera + Start and keeps the
                          camera alive for its lifetime.
  OpenCamVcamSource.dll   the FrameServer-hosted custom source
                          (IMFCameraFrameServerCustomSource). Its FrameGenerator
                          pulls the client's latest BGRA frame from a
                          shared-memory buffer and converts it to NV12/RGB32.
  this module             installs/registers the DLL (elevated, once), launches
                          the exe, and writes decoded frames into shared memory.

The camera advertises a fixed 1920x1080 stream (RGB32 + NV12); this module
resizes/color-converts every phone frame to that size before writing it.
"""

import base64
import ctypes
from ctypes import wintypes
import os
import subprocess
import sys
import threading
import time

try:
    from PIL import Image
    HAS_PIL = True
except ImportError:
    Image = None
    HAS_PIL = False

# ---------------------------------------------------------------------------
# protocol constants — must match vcam_mf_cpp/VCamSampleSource/SharedFrame.h
# ---------------------------------------------------------------------------
CLSID = "{84e6175f-bd77-4633-ad1f-17aa72c8e7da}"
DEVICE_NAME = "OpenCam Virtual Camera"

STOP_EVENT_NAME = "Local\\OpenCamVcamStop"
READY_EVENT_NAME = "Local\\OpenCamVcamReady"

OCV_MAGIC = 0x4F435646
OCV_VERSION = 1
FRAME_W = 1920
FRAME_H = 1080
FRAME_STRIDE = FRAME_W * 4
PIXEL_BYTES = FRAME_STRIDE * FRAME_H
SHM_SIZE = 64 + PIXEL_BYTES

# header field offsets (OcvSharedHeader, packed)
OFF_FRAMEINDEX = 24

_INVALID_HANDLE_VALUE = ctypes.c_void_p(-1).value
_PAGE_READWRITE = 0x04
_FILE_MAP_WRITE = 0x0002
_GENERIC_READ = 0x80000000
_GENERIC_WRITE = 0x40000000
_FILE_SHARE_RW = 0x00000006
_OPEN_ALWAYS = 4
_FILE_BEGIN = 0


def _log(msg):
    try:
        from opencam_client import _log as _c
        _c("vcam_mf: " + str(msg))
    except Exception:
        pass


# ---------------------------------------------------------------------------
# OS / platform support
# ---------------------------------------------------------------------------

def supported():
    """MFCreateVirtualCamera needs Windows 11 22H2 (build 22621) or newer."""
    try:
        return sys.getwindowsversion().build >= 22621
    except Exception:
        return False


# ---------------------------------------------------------------------------
# install / registration
# ---------------------------------------------------------------------------

def bundled_dir():
    """Where the DLL + exe live in the packaged app (or the repo)."""
    if getattr(sys, "frozen", False):
        base = getattr(sys, "_MEIPASS", os.path.dirname(sys.executable))
    else:
        base = os.path.dirname(os.path.abspath(__file__))
    return os.path.join(base, "vcam_mf")


def install_dir():
    """Stable per-machine location the FrameServer service can access.

    The source DLL is loaded out-of-proc by the Windows Frame Server, so it
    must NOT live under the user's home directory (a documented pitfall of
    IMFCameraFrameServerCustomSource). ProgramData is the safe choice.

    Versioned subfolders (vcam_mf\v1, v2, ...) are used so a DLL that the
    FrameServer service still has loaded never blocks installing an update —
    a new version goes to a fresh folder and the CLSID registration is
    pointed at it. Old folders are pruned later.
    """
    return os.path.join(os.environ.get("ProgramData", r"C:\ProgramData"),
                        "OpenCamClient", "vcam_mf")


def _dll_fingerprint():
    """Stable id for the bundled source build (size+name hash)."""
    import hashlib
    p = os.path.join(bundled_dir(), "OpenCamVcamSource.dll")
    try:
        with open(p, "rb") as f:
            h = hashlib.sha256(f.read()).hexdigest()[:16]
        return h
    except OSError:
        return "missing"


def _current_ver_dir():
    """The version folder the CLSID currently points at, or None."""
    import winreg
    key = r"SOFTWARE\Classes\CLSID\%s\InprocServer32" % CLSID
    try:
        k = winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, key)
        try:
            val, _ = winreg.QueryValueEx(k, None)
        finally:
            k.Close()
        d = os.path.dirname(val or "")
        return d if d else None
    except OSError:
        return None


def _ver_dir(version):
    return os.path.join(install_dir(), "v" + str(version))


def dll_path():
    """Path of the DLL the CLSID points at (or the latest installed copy)."""
    d = _current_ver_dir()
    if d:
        p = os.path.join(d, "OpenCamVcamSource.dll")
        if os.path.isfile(p):
            return p
    latest = _latest_installed_ver()
    if latest:
        return os.path.join(_ver_dir(latest), "OpenCamVcamSource.dll")
    return os.path.join(_ver_dir(1), "OpenCamVcamSource.dll")


def exe_path():
    d = _current_ver_dir()
    if d:
        p = os.path.join(d, "opencam-vcam.exe")
        if os.path.isfile(p):
            return p
    latest = _latest_installed_ver()
    if latest:
        return os.path.join(_ver_dir(latest), "opencam-vcam.exe")
    return os.path.join(_ver_dir(1), "opencam-vcam.exe")


def _latest_installed_ver():
    try:
        vers = [int(n[1:]) for n in os.listdir(install_dir())
                if n.startswith("v") and n[1:].isdigit()]
        return max(vers) if vers else None
    except OSError:
        return None


def _copy_files():
    """Copy the DLL + exe into a fresh version folder (no elevation needed).

    Always installs into a NEW vN folder keyed by the build fingerprint, so a
    DLL still held by the FrameServer service never blocks an update. Returns
    the folder used. A folder is only REUSED when it contains BOTH files of the
    current build — a folder with a stale/missing exe (e.g. an interrupted
    update) is never reused, so start() can't point at a half-installed copy.
    """
    src = bundled_dir()
    base = install_dir()
    try:
        os.makedirs(base, exist_ok=True)
    except OSError:
        return None, "cannot create %s" % base
    # reuse an existing folder with this exact build if present (both files)
    try:
        for n in sorted(os.listdir(base), reverse=True):
            if not (n.startswith("v") and n[1:].isdigit()):
                continue
            d = os.path.join(base, n)
            if _build_matches(d, src):
                return d, None
    except OSError:
        pass
    # new version folder
    import re
    used = []
    try:
        used = [int(m.group(1)) for m in
                (re.match(r"v(\d+)", n) for n in os.listdir(base))
                if m]
    except OSError:
        pass
    ver = (max(used) + 1) if used else 1
    d = _ver_dir(ver)
    try:
        os.makedirs(d, exist_ok=True)
    except OSError:
        return None, "cannot create %s" % d
    for name in ("OpenCamVcamSource.dll", "opencam-vcam.exe"):
        s = os.path.join(src, name)
        if not os.path.isfile(s):
            return None, "missing bundled file %s" % s
        try:
            with open(s, "rb") as f:
                data = f.read()
            with open(os.path.join(d, name), "wb") as f:
                f.write(data)
        except OSError as e:
            return None, "cannot copy %s: %s" % (name, e)
    return d, None


def _build_matches(d, src):
    """True if folder `d` holds a complete, current copy of both binaries."""
    for name in ("OpenCamVcamSource.dll", "opencam-vcam.exe"):
        p = os.path.join(d, name)
        if not os.path.isfile(p):
            return False
        s = os.path.join(src, name)
        if not os.path.isfile(s) or os.path.getsize(p) != os.path.getsize(s):
            return False
    return True


def is_registered():
    """True when the source CLSID points at an installed copy of our DLL."""
    import winreg
    key = (r"SOFTWARE\Classes\CLSID\%s\InprocServer32" % CLSID)
    for hive, tag in ((winreg.HKEY_LOCAL_MACHINE, "HKLM"),
                      (winreg.HKEY_CURRENT_USER, "HKCU")):
        try:
            k = winreg.OpenKey(hive, key)
            try:
                val, _ = winreg.QueryValueEx(k, None)
            finally:
                k.Close()
            if val and os.path.isfile(val) and \
                    os.path.basename(val) == "OpenCamVcamSource.dll":
                return True
        except OSError:
            continue
    return False


def _ps_elevated(script, timeout=120):
    """Run a PowerShell script elevated (UAC prompt) and wait for it."""
    encoded = base64.b64encode(script.encode("utf-16-le")).decode("ascii")
    cmd = ('powershell -NoProfile -Command "Start-Process powershell -Verb '
           'RunAs -WindowStyle Hidden -ArgumentList \'-NoProfile\','
           '\'-ExecutionPolicy\',\'Bypass\',\'-EncodedCommand\',\'%s\' -Wait"'
           % encoded)
    try:
        subprocess.run(cmd, shell=True, timeout=timeout)
        return True
    except Exception:
        return False


def prepare():
    """Copy files into a fresh version folder and register the source DLL.

    Returns None on success, or an error string. The elevated regsvr32 only
    runs when the registration points at a different build than the bundled
    one; versioned folders mean an in-use DLL never blocks the update.
    """
    folder, err = _copy_files()
    if err:
        return err
    dll = os.path.join(folder, "OpenCamVcamSource.dll")

    import winreg
    key = r"SOFTWARE\Classes\CLSID\%s\InprocServer32" % CLSID
    registered_ok = False
    try:
        k = winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, key)
        try:
            val, _ = winreg.QueryValueEx(k, None)
        finally:
            k.Close()
        registered_ok = os.path.normcase(val or "") == os.path.normcase(dll)
    except OSError:
        pass
    if registered_ok:
        _prune_old_versions()
        return None

    _log("registering MF virtual camera source v%s (one-time admin)" % os.path.basename(folder))
    script = r"""
$ErrorActionPreference = 'Continue'
$log = '%(status)s'
$dll = '%(dll)s'
"step=start" | Out-File $log
& regsvr32.exe /s $dll
"step=regsvr32 rc=$LASTEXITCODE" | Out-File $log -Append
$key = 'HKLM:\SOFTWARE\Classes\CLSID\%(clsid)s\InprocServer32'
$v = (Get-ItemProperty -Path $key -ErrorAction SilentlyContinue).'(default)'
"step=value=$v" | Out-File $log -Append
"step=done" | Out-File $log -Append
""" % {"status": os.path.join(folder, "_reg_status.txt"),
       "dll": dll, "clsid": CLSID}

    if not _ps_elevated(script):
        return "the admin approval was cancelled — the virtual camera needs it once"
    # wait for the elevated process to write its status
    status = os.path.join(folder, "_reg_status.txt")
    for _ in range(40):
        if os.path.exists(status):
            break
        time.sleep(0.25)
    ok = is_registered()
    try:
        os.remove(status)
    except OSError:
        pass
    if not ok:
        return "registration failed — reinstall OpenCam as administrator"
    _prune_old_versions()
    return None


def _prune_old_versions(keep=2):
    """Remove old version folders (best effort; locked ones are skipped)."""
    try:
        import re
        base = install_dir()
        vers = sorted([int(m.group(1)) for m in
                       (re.match(r"v(\d+)", n) for n in os.listdir(base)) if m])
        for v in vers[:-keep]:
            import shutil
            shutil.rmtree(_ver_dir(v), ignore_errors=True)
    except Exception:
        pass


def uninstall():
    """Unregister the source DLL (elevated)."""
    if not is_registered():
        return
    script = r"""
$dll = '%(dll)s'
& regsvr32.exe /u /s $dll
""" % {"dll": dll_path()}
    _ps_elevated(script)


# ---------------------------------------------------------------------------
# shared memory helpers (ctypes, no dependencies)
# ---------------------------------------------------------------------------

class _K32:
    """kernel32 with correct prototypes (ctypes defaults to 32-bit args)."""

    def __init__(self):
        k = ctypes.WinDLL("kernel32", use_last_error=True)
        HANDLE = wintypes.HANDLE
        LPCWSTR = wintypes.LPCWSTR
        DWORD = wintypes.DWORD
        BOOL = wintypes.BOOL
        SIZE_T = ctypes.c_size_t
        LPVOID = ctypes.c_void_p
        SECURITY_ATTRIBUTES = ctypes.c_void_p

        k.OpenFileMappingW.restype = HANDLE
        k.OpenFileMappingW.argtypes = [DWORD, BOOL, LPCWSTR]
        k.CreateFileMappingW.restype = HANDLE
        k.CreateFileMappingW.argtypes = [HANDLE, ctypes.POINTER(SECURITY_ATTRIBUTES),
                                         DWORD, DWORD, DWORD, LPCWSTR]
        k.MapViewOfFile.restype = LPVOID
        k.MapViewOfFile.argtypes = [HANDLE, DWORD, DWORD, DWORD, SIZE_T]
        k.UnmapViewOfFile.restype = BOOL
        k.UnmapViewOfFile.argtypes = [LPVOID]
        k.CloseHandle.restype = BOOL
        k.CloseHandle.argtypes = [HANDLE]
        k.OpenEventW.restype = HANDLE
        k.OpenEventW.argtypes = [DWORD, BOOL, LPCWSTR]
        k.SetEvent.restype = BOOL
        k.SetEvent.argtypes = [HANDLE]
        k.CreateFileW.restype = HANDLE
        k.CreateFileW.argtypes = [LPCWSTR, DWORD, DWORD, LPVOID, DWORD, DWORD, HANDLE]
        k.SetFilePointerEx.restype = BOOL
        k.SetFilePointerEx.argtypes = [HANDLE, ctypes.c_longlong,
                                       ctypes.POINTER(ctypes.c_ulonglong), DWORD]
        k.SetEndOfFile.restype = BOOL
        k.SetEndOfFile.argtypes = [HANDLE]
        self.k = k


class _Shm:
    """Thin wrapper around a file mapping: open-or-create, map, write pixels."""

    def __init__(self):
        self._handle = None
        self._view = None
        self._pixels = None
        self._index = 0
        self._k32 = _K32().k

    def open(self):
        k32 = self._k32
        # File-backed section in ProgramData: every process (user session AND
        # the FrameServer host, which may run in a different context) maps the
        # same file, so no Local/Global namespace tricks are needed.
        import os as _os
        path = _os.path.join(install_dir(), "frame.bin")
        try:
            _os.makedirs(install_dir(), exist_ok=True)
        except OSError:
            pass
        file = k32.CreateFileW(
            path, _GENERIC_READ | _GENERIC_WRITE, _FILE_SHARE_RW, None,
            _OPEN_ALWAYS, 0x80, None)  # 0x80 = FILE_ATTRIBUTE_NORMAL
        if not file or file == _INVALID_HANDLE_VALUE:
            return "cannot open shared frame file"
        # ensure the file is large enough (offset is passed by value)
        k32.SetFilePointerEx(ctypes.c_void_p(file),
                             ctypes.c_longlong(SHM_SIZE), None, _FILE_BEGIN)
        k32.SetEndOfFile(ctypes.c_void_p(file))
        handle = k32.CreateFileMappingW(
            ctypes.c_void_p(file), None, _PAGE_READWRITE, 0, 0, None)
        k32.CloseHandle(ctypes.c_void_p(file))
        if not handle:
            return "cannot create file mapping"
        view = k32.MapViewOfFile(handle, _FILE_MAP_WRITE, 0, 0, SHM_SIZE)
        if not view:
            k32.CloseHandle(handle)
            return "cannot map shared frame buffer"
        self._handle = handle
        self._view = ctypes.c_void_p(view)
        self._pixels = self._view.value + 64
        # write the header once (magic, version, w, h, stride, format)
        header = (ctypes.c_uint32 * 6)(OCV_MAGIC, OCV_VERSION,
                                       FRAME_W, FRAME_H,
                                       FRAME_STRIDE, 0)
        ctypes.memmove(ctypes.c_void_p(self._view.value), header, 24)
        self._index = 0
        return None

    def write_frame(self, bgra_bytes):
        """Write one full BGRA frame; bumps frameIndex after the pixels land."""
        if self._view is None:
            return
        ctypes.memmove(ctypes.c_void_p(self._pixels), bgra_bytes,
                       min(len(bgra_bytes), PIXEL_BYTES))
        self._index += 1
        ctypes.memmove(ctypes.c_void_p(self._view.value + OFF_FRAMEINDEX),
                       ctypes.byref(ctypes.c_long(self._index)), 4)

    def close(self):
        k32 = self._k32
        if self._view:
            k32.UnmapViewOfFile(self._view)
            self._view = None
        if self._handle:
            k32.CloseHandle(self._handle)
            self._handle = None


# ---------------------------------------------------------------------------
# the camera object used by opencam_client.py
# ---------------------------------------------------------------------------

class MfVcam:
    """Feeds the Media Foundation virtual camera with the phone's frames.

    Interface-compatible with virtualcam.VirtualCam from the client's point of
    view: start() / send(PIL.Image) / stop().
    """

    def __init__(self, w=FRAME_W, h=FRAME_H, fps=30):
        self._w = w
        self._h = h
        self._fps = fps
        self._proc = None
        self._shm = _Shm()
        self._latest = None
        self._latest_id = 0
        self._sent_id = -1
        self._lock = threading.Lock()
        self._thread = None
        self._halt = threading.Event()

    # -- lifecycle ----------------------------------------------------------
    def start(self):
        """Copy/register as needed, launch the host exe, map the buffer.

        Returns None on success or an error string. Never blocks on UAC: the
        registration prompt is triggered by prepare() beforehand.
        """
        if not supported():
            return "MF virtual camera needs Windows 11 22H2+ — the DirectShow " \
                   "virtual camera is used instead"
        if not HAS_PIL:
            return "Pillow is required to feed the virtual camera"
        # copy into a fresh version folder + register only when stale
        # (elevated UAC happens at most once per build)
        err = prepare()
        if err:
            return err
        exe = exe_path()
        if not os.path.isfile(exe):
            return "missing %s" % exe

        err = self._shm.open()
        if err:
            return err

        # Reap any stale camera hosts from a previous session. Without this a
        # dead host that never cleared its Local\\OpenCamVcamReady event makes
        # _camera_live() below return True instantly, so start() "succeeds"
        # while the actual camera is gone (the black/stuck camera in Discord).
        # If a REAL camera is already enumerable (another client instance), it
        # is reused instead — the shared frame buffer feeds the same camera.
        _reap_stale_hosts()

        if _camera_enumerated():
            # a live camera exists without us launching a host — feed it
            self._proc = None
        else:
            # launch the camera host (hidden window, no console)
            try:
                flags = 0x08000000  # CREATE_NO_WINDOW
                self._proc = subprocess.Popen(
                    [exe], creationflags=flags,
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            except OSError as e:
                self._shm.close()
                return "cannot launch the virtual camera host: %s" % e

        # wait for the camera to appear (the exe creates it right after mapping)
        for _ in range(40):
            if self._proc is not None and self._proc.poll() is not None:
                self._shm.close()
                self._proc = None
                return "the virtual camera host exited immediately"
            if _camera_live():
                break
            time.sleep(0.25)
        if not _camera_live():
            # never leave the host running without a camera (zombie process +
            # possible stale device) — kill it before giving up
            try:
                if self._proc is not None:
                    self._proc.kill()
                    self._proc.wait(timeout=3)
            except Exception:
                pass
            self._shm.close()
            self._proc = None
            return "the virtual camera did not come up — check that the " \
                   "OpenCamVcamSource.dll is registered"

        self._halt.clear()
        self._thread = threading.Thread(target=self._feeder, daemon=True)
        self._thread.start()
        return None

    def stop(self):
        self._halt.set()
        if self._thread is not None:
            self._thread.join(timeout=2)
            self._thread = None
        # If we reused another client instance's live camera (no host of our
        # own), do NOT signal STOP_EVENT — that would kill the other client's
        # camera. Just stop feeding the shared buffer.
        if self._proc is not None:
            # signal the host to exit cleanly (the exe creates this event), then
            # kill it if it doesn't go within a couple of seconds
            try:
                k32 = _K32().k
                evt = k32.OpenEventW(0x0002, False, STOP_EVENT_NAME)
                if evt:
                    k32.SetEvent(evt)
                    k32.CloseHandle(evt)
            except Exception:
                pass
            try:
                self._proc.wait(timeout=2)
            except Exception:
                try:
                    self._proc.kill()
                except Exception:
                    pass
            self._proc = None
        self._shm.close()

    # -- frame feed ---------------------------------------------------------
    def send(self, img):
        """Hand the newest frame to the feeder (latest-wins, non-blocking)."""
        if self._halt.is_set():
            return
        with self._lock:
            self._latest = img
            self._latest_id += 1

    def _feeder(self):
        """Background thread: resize+convert the newest frame and write it."""
        while not self._halt.is_set():
            with self._lock:
                img = self._latest
                fid = self._latest_id
            if img is None or fid == self._sent_id:
                time.sleep(0.01)
                continue
            try:
                # RGB -> BGRA at 1920x1080, all C-level (PIL channel merge).
                # MFVideoFormat_RGB32 / the NV12 converter expect BGRA byte order.
                # BILINEAR: re-scaling every frame with LANCZOS is needlessly slow
                # (30fps x 1080p); BILINEAR is a fraction of the cost at the same
                # visual quality for a virtual camera feed.
                #
                # Fast path: when the phone frame is already the target size,
                # skip the resize entirely (a 1920x1080 stream is the common
                # case). convert("RGB") is also skipped when the mode is right.
                if img.size == (FRAME_W, FRAME_H) and img.mode == "RGB":
                    rgb = img
                else:
                    rgb = img.convert("RGB")
                    if rgb.size != (FRAME_W, FRAME_H):
                        rgb = rgb.resize((FRAME_W, FRAME_H), Image.BILINEAR)
                r, g, b = rgb.split()
                bgra = Image.merge("RGBA",
                                   (b, g, r, Image.new("L", rgb.size, 255)))
                data = bgra.tobytes()
                self._shm.write_frame(data)
                self._sent_id = fid
            except Exception as e:
                _log("feed error: %s" % e)
                time.sleep(0.1)
            time.sleep(0.005)

    # -- stop event ---------------------------------------------------------


def _camera_live():
    """Cheap probe: is the OpenCam virtual camera registered and ready?

    Primary check: the host exe sets Local\\OpenCamVcamReady right after the
    MF camera is registered — no external tools needed (works in the packaged
    exe that has no ffmpeg). Falls back to a DirectShow enumeration when the
    event is unavailable.
    """
    try:
        k32 = _K32().k
        evt = k32.OpenEventW(0x001F0001, False, READY_EVENT_NAME)  # SYNCHRONIZE|EVENT_MODIFY_STATE
        if evt:
            k32.CloseHandle(evt)
            return True
    except Exception:
        pass
    try:
        r = subprocess.run(["ffmpeg", "-hide_banner", "-list_devices", "true",
                            "-f", "dshow", "-i", "dummy"],
                           capture_output=True, text=True, timeout=30)
        return DEVICE_NAME in (r.stderr or "")
    except Exception:
        return False


def _camera_enumerated():
    """True when the OpenCam camera is actually visible to DirectShow apps.

    Used to tell a LIVE camera (owned by this or another client instance) from
    a stale READY event left behind by a broken/old host. ffmpeg's dshow device
    list is the same enumeration Discord/OBS use.
    """
    try:
        r = subprocess.run(["ffmpeg", "-hide_banner", "-list_devices", "true",
                            "-f", "dshow", "-i", "dummy"],
                           capture_output=True, text=True, timeout=30)
        return DEVICE_NAME in (r.stderr or "")
    except Exception:
        return False


def _reap_stale_hosts():
    """Kill leftover opencam-vcam.exe hosts from previous sessions.

    Only kills processes with OUR exact image name, so OBS Virtual Camera and
    every other app are untouched — and only when NO camera is actually
    enumerable. If a real OpenCam camera is already live (e.g. a second client
    instance owns it), it is left running: the shared frame buffer means this
    client feeds the same pixels without needing its own host.

    A stale host (previous session that didn't exit cleanly) lingers with its
    READY event set after its registration was invalidated; without reaping it,
    _camera_live() would report "live" for a camera that doesn't exist — the
    classic black/stuck camera in Discord.
    """
    if _camera_enumerated():
        return  # a real camera exists — reuse it, never kill it
    try:
        r = subprocess.run(["taskkill", "/F", "/IM", "opencam-vcam.exe"],
                           capture_output=True, timeout=15)
        if r.returncode == 0:
            # give the OS a moment to tear down the PnP device registration
            time.sleep(1.0)
    except Exception:
        pass
