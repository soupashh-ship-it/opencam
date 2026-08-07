"""
Virtual camera support for the OpenCam Client.

Exposes the phone stream as a real Windows webcam ("OpenCam Virtual Camera")
that shows up in Discord, Zoom, WhatsApp, Google Meet, etc. — the same
mechanism DroidCam Client uses (a registered DirectShow source filter).

How it works
------------
* The DirectShow filter itself is the one OBS Studio ships for its own Virtual
  Camera (a self-registering COM server, no other software needed at runtime).
  OBS Studio installs it as the "OBS Virtual Camera" device.
* This module does **not** touch that entry. It adds a brand-new device
  instance named "OpenCam Virtual Camera" that points at the same filter, so
  Discord offers *both* "OBS Virtual Camera" and "OpenCam Virtual Camera"
  side-by-side (plus DroidCam's own device if installed).
* The filter exposes frames written to a named shared-memory buffer; this
  module writes the phone's frames into that buffer with pyvirtualcam.

Registration happens once (admin/UAC) — afterwards the device persists and the
client only needs to open it and send frames while streaming. Uninstalling only
removes our extra entry; OBS Virtual Camera stays untouched.

Why WhatsApp (and other UWP/MSIX apps) don't list it
-----------------------------------------------------
Windows has TWO camera enumerations:

* DirectShow (``ICreateDevEnum`` / ``@device_sw_``) — used by Discord, OBS,
  browsers, Zoom, Teams and ffmpeg. Lists every "Video Input Devices"
  registration, including ours and OBS Virtual Camera.
* UWP/Media Foundation (``DeviceClass.VideoCapture`` / ``MFEnumDeviceSources``
  vidcap) — used by Store-packaged apps like WhatsApp Desktop. On modern
  Windows this enumeration only surfaces **kernel-streaming (KS) cameras**
  (physical webcams and driver-backed virtual cameras like DroidCam's, which
  installs a ``ROOT\MEDIA`` kernel driver). DirectShow-only software devices are
  not listed — this is why neither "OpenCam Virtual Camera" nor "OBS Virtual
  Camera" appears in WhatsApp's camera picker while DroidCam's does.

A registry-only DirectShow registration cannot appear in that list. Making the
camera visible to WhatsApp requires one of:

* a kernel-streaming virtual camera driver (what DroidCam ships), or
* a Windows 11 "Camera Frame Server" custom source (``IMFCameraFrameServerCustomSource``).

Until then the OpenCam camera works in every DirectShow-based app (Discord,
OBS, Chrome/Edge/Meet, Zoom, Teams, ffmpeg, VLC). This module mirrors the
reference OBS registration exactly (including the ``FilterData`` pin/media-type
blob) so our entry is byte-identical to OBS's everywhere OBS works.
"""

import base64
import os
import shutil
import subprocess
import sys
import threading
import time

try:
    import winreg
except ImportError:  # non-Windows fallback for the selftest
    winreg = None

try:
    import numpy as np
    import pyvirtualcam
    HAS_PYVCAM = True
except Exception:  # noqa: BLE001 — optional dependency
    np = pyvirtualcam = None
    HAS_PYVCAM = False

try:
    from PIL import Image
except ImportError:
    Image = None

# DirectShow "Video Input Devices" category.
VIDEO_INPUT_CATEGORY = "{860BB310-5D01-11D0-BD3B-00A0C911CE86}"
# The filter class OBS Studio's virtual camera registers (interop constant).
FILTER_CLSID = "{A3FCE0F5-3493-419F-958A-ABA1250EC20B}"
# Our *own* device instance GUID — a separate entry next to OBS Virtual Camera.
INSTANCE_GUID = "{F9092F73-393F-44AB-997C-5B891FFC54F9}"
DEVICE_NAME = "OpenCam Virtual Camera"

_REG_HIVES = (
    (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\Classes\CLSID") if winreg else (),
    (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\WOW6432Node\Classes\CLSID") if winreg else (),
)


# ============================================================================
# Paths
# ============================================================================

def base_dir():
    if getattr(sys, "frozen", False):
        return os.path.dirname(sys.executable)
    return os.path.dirname(os.path.abspath(__file__))


def bundled_vcam_dir():
    """Where the filter DLLs live in the distribution (temp dir when frozen)."""
    if getattr(sys, "frozen", False):
        return os.path.join(getattr(sys, "_MEIPASS", base_dir()), "vcam")
    return os.path.join(os.path.dirname(os.path.abspath(__file__)), "vcam")


def vcam_dir():
    """Persistent home for the filter DLLs.

    Always %LOCALAPPDATA%\\OpenCamClient\\vcam: it survives PyInstaller temp
    extraction and has no spaces in the path (which broke elevated launching
    via Start-Process on the old in-tree location).
    """
    d = os.path.join(os.environ.get("LOCALAPPDATA", os.path.expanduser("~")),
                     "OpenCamClient", "vcam")
    os.makedirs(d, exist_ok=True)
    return d


def ensure_vcam_files():
    """Copy the bundled filter DLLs to the persistent dir. Returns that dir."""
    src = bundled_vcam_dir()
    dst = vcam_dir()
    if os.path.isdir(src):
        for name in ("obs-virtualcam-module64.dll", "obs-virtualcam-module32.dll"):
            s = os.path.join(src, name)
            if os.path.exists(s) and not os.path.exists(os.path.join(dst, name)):
                try:
                    shutil.copy2(s, os.path.join(dst, name))
                except OSError:
                    pass
    return dst


def bundled():
    """True if the filter DLLs are present in the distribution."""
    d = bundled_vcam_dir()
    return os.path.isfile(os.path.join(d, "obs-virtualcam-module64.dll"))


# ============================================================================
# Detection (read-only registry, no admin needed)
# ============================================================================

def _instance_key(hive, base):
    return base + "\\" + VIDEO_INPUT_CATEGORY + "\\Instance\\" + INSTANCE_GUID


def filter_registered():
    """True if the shared filter COM class is registered (either hive)."""
    if winreg is None:
        return False
    for hive, base in _REG_HIVES:
        try:
            with winreg.OpenKey(hive, base + "\\" + FILTER_CLSID):
                return True
        except OSError:
            continue
    return False


def device_name():
    """FriendlyName registered for our device instance (or None)."""
    if winreg is None:
        return None
    for hive, base in _REG_HIVES:
        try:
            with winreg.OpenKey(hive, _instance_key(hive, base)) as k:
                try:
                    value, _ = winreg.QueryValueEx(k, "FriendlyName")
                    return value
                except OSError:
                    continue  # value missing — check the other hive
        except OSError:
            continue
    return None


def registered():
    """True if our separate device instance exists and is branded as ours."""
    return filter_registered() and device_name() == DEVICE_NAME


# ============================================================================
# One-time registration (elevated, UAC prompt)
#
# Elevation runs a PowerShell script via -EncodedCommand: the encoded payload
# is a single token with no quoting pitfalls, so paths with spaces work (a
# plain Start-Process -ArgumentList silently mangles quoted spaced paths).
# ============================================================================

def _ps_elevated(script, timeout=240):
    """Run `script` (PowerShell source) elevated. Returns (rc, output)."""
    payload = base64.b64encode(script.encode("utf-16-le")).decode("ascii")
    ps = ("try { Start-Process -FilePath 'powershell.exe' "
          "-ArgumentList '-NoProfile','-EncodedCommand','%s' "
          "-Verb RunAs -Wait -ErrorAction Stop; Write-Output 'OK' } "
          "catch { Write-Output ('ELEVATION ERROR: ' + $_.Exception.Message) }"
          % payload)
    try:
        r = subprocess.run(["powershell", "-NoProfile", "-Command", ps],
                           capture_output=True, text=True, timeout=timeout)
        return r.returncode, (r.stdout or "") + (r.stderr or "")
    except Exception as exc:  # noqa: BLE001 — timeout / powershell missing
        return 1, "launcher failed: %r" % exc


def _register_ps(d):
    """PowerShell body: ensure the filter is registered, then add OUR device
    instance. The OBS Virtual Camera entry is never renamed or re-pointed."""
    dll64 = os.path.join(d, "obs-virtualcam-module64.dll")
    dll32 = os.path.join(d, "obs-virtualcam-module32.dll")
    syswow = os.environ.get("windir", "C:\\Windows") + "\\SysWOW64\\regsvr32.exe"
    return r"""
$ErrorActionPreference = 'Continue'
# If the shared filter class isn't registered anywhere (e.g. OBS Studio was
# never installed), register our bundled copy. Otherwise reuse the existing
# registration so both device entries share the same filter engine.
$classKey = 'HKLM:\SOFTWARE\Classes\CLSID\%(clsid)s\InprocServer32'
if (-not (Test-Path $classKey)) {
  & regsvr32.exe /i /s "%(dll64)s"
  if (Test-Path "%(dll32)s") { & "%(syswow)s" /i /s "%(dll32)s" }
}
$hives = @(
  'HKLM\SOFTWARE\Classes\CLSID\%(cat)s\Instance\%(instance)s',
  'HKLM\SOFTWARE\WOW6432Node\Classes\CLSID\%(cat)s\Instance\%(instance)s'
)
foreach ($h in $hives) {
  New-Item -Path $h -Force | Out-Null
  reg add $h /v CLSID /t REG_SZ /d "%(clsid)s" /f | Out-Null
  reg add $h /v FriendlyName /t REG_SZ /d "%(name)s" /f | Out-Null
  # Mirror the FilterData blob from the filter's own OBS-installer registration
  # (same CLSID, same pins, same media types) so our instance is byte-identical
  # to the reference OBS Virtual Camera entry. FilterData carries the serialized
  # pin/media-type data DirectShow's device enumerator uses for matching.
  # (.Replace is a literal swap - no regex escaping pitfalls on GUID paths.)
  $src = $h.Replace('%(instance)s', '%(obsclsid)s')
  $fd = (Get-ItemProperty -Path $src -ErrorAction SilentlyContinue).FilterData
  if ($fd) { Set-ItemProperty -Path $h -Name FilterData -Value $fd -Type Binary }
}
Set-Content -Path "%(result)s" -Value "REGISTERED %(name)s"
""" % {
        "dll64": dll64, "dll32": dll32, "syswow": syswow,
        "cat": VIDEO_INPUT_CATEGORY, "clsid": FILTER_CLSID,
        "instance": INSTANCE_GUID, "obsclsid": FILTER_CLSID,
        "name": DEVICE_NAME,
        "result": os.path.join(d, "reg_result.txt"),
    }


def _instance_has_filterdata():
    """True if our device instance carries the FilterData blob (OBS parity)."""
    if winreg is None:
        return False
    for hive, base in _REG_HIVES:
        try:
            with winreg.OpenKey(hive, _instance_key(hive, base)) as k:
                winreg.QueryValueEx(k, "FilterData")
                return True
        except OSError:
            continue
    return False


def register():
    """Add our device instance next to OBS Virtual Camera. Elevated once."""
    d = ensure_vcam_files()
    # The bundled DLL is only needed to *register the filter class*; if the
    # filter is already registered (e.g. OBS Studio is installed) a plain
    # reg-add of our instance is enough and works without the DLL.
    if not filter_registered() and not os.path.isfile(
            os.path.join(d, "obs-virtualcam-module64.dll")):
        return False
    result = os.path.join(d, "reg_result.txt")
    if os.path.exists(result):
        try:
            os.remove(result)
        except OSError:
            pass
    rc, out = _ps_elevated(_register_ps(d))
    if rc != 0 or "ELEVATION ERROR" in out:
        return False
    # Give the elevated process a moment to flush, then verify against registry.
    for _ in range(20):
        if filter_registered() and device_name() == DEVICE_NAME:
            break
        time.sleep(0.25)
    ok = filter_registered() and device_name() == DEVICE_NAME
    # FilterData is optional (the device works without it in DirectShow apps),
    # but if the mirror silently failed, say so instead of promising parity.
    if ok and not _instance_has_filterdata():
        print("note: FilterData mirror did not land (device still registered)")
    return ok


def unregister():
    """Remove our device entry only (elevated, UAC). OBS Virtual Camera stays."""
    d = ensure_vcam_files()
    if not os.path.isdir(d):
        return
    dll64 = os.path.join(d, "obs-virtualcam-module64.dll")
    dll32 = os.path.join(d, "obs-virtualcam-module32.dll")
    syswow = os.environ.get("windir", "C:\\Windows") + "\\SysWOW64\\regsvr32.exe"
    script = r"""
$ErrorActionPreference = 'Continue'
# Only unregister our bundled filter copy if the filter class currently points
# at it — never unregister an OBS Studio-owned registration.
$classKey = 'HKLM:\SOFTWARE\Classes\CLSID\%(clsid)s\InprocServer32'
try {
  $cur = (Get-ItemProperty -Path $classKey -ErrorAction Stop).'(default)'
  if ($cur -and ($cur -eq '%(dll64)s')) {
    & regsvr32.exe /u /s "%(dll64)s"
    if (Test-Path "%(dll32)s") { & "%(syswow)s" /u /s "%(dll32)s" }
  }
} catch {}
$hives = @(
  'HKLM\SOFTWARE\Classes\CLSID\%(cat)s\Instance\%(instance)s',
  'HKLM\SOFTWARE\WOW6432Node\Classes\CLSID\%(cat)s\Instance\%(instance)s'
)
foreach ($h in $hives) { reg delete $h /f 2>$null | Out-Null }
""" % {"dll64": dll64, "dll32": dll32, "syswow": syswow,
       "cat": VIDEO_INPUT_CATEGORY, "clsid": FILTER_CLSID,
       "instance": INSTANCE_GUID}
    _ps_elevated(script)


# ============================================================================
# Manual .bat helpers (for docs / advanced users) — same logic, no UAC shim
# ============================================================================

_REGISTER_BAT = """@echo off
setlocal
cd /d "%~dp0"
set "FILTER={A3FCE0F5-3493-419F-958A-ABA1250EC20B}"
set "INST={F9092F73-393F-44AB-997C-5B891FFC54F9}"
set "CAT={860BB310-5D01-11D0-BD3B-00A0C911CE86}"
rem If the shared filter class is not registered yet, register the bundled copy.
reg query "HKLM\\SOFTWARE\\Classes\\CLSID\\%FILTER%\\InprocServer32" >nul 2>&1
if errorlevel 1 (
    echo Registering virtual camera filter (64-bit)...
    regsvr32.exe /i /s "%~dp0obs-virtualcam-module64.dll"
    if exist "%~dp0obs-virtualcam-module32.dll" (
        echo Registering virtual camera filter (32-bit)...
        "%windir%\\SysWOW64\\regsvr32.exe" /i /s "%~dp0obs-virtualcam-module32.dll"
    )
)
echo Adding %DEVICE_NAME% device (OBS Virtual Camera stays untouched)...
reg add "HKLM\\SOFTWARE\\Classes\\CLSID\\%CAT%\\Instance\\%INST%" /v CLSID /t REG_SZ /d "%FILTER%" /f >nul
reg add "HKLM\\SOFTWARE\\Classes\\CLSID\\%CAT%\\Instance\\%INST%" /v FriendlyName /t REG_SZ /d "%DEVICE_NAME%" /f >nul
reg add "HKLM\\SOFTWARE\\WOW6432Node\\Classes\\CLSID\\%CAT%\\Instance\\%INST%" /v CLSID /t REG_SZ /d "%FILTER%" /f >nul
reg add "HKLM\\SOFTWARE\\WOW6432Node\\Classes\\CLSID\\%CAT%\\Instance\\%INST%" /v FriendlyName /t REG_SZ /d "%DEVICE_NAME%" /f >nul
rem Mirror the FilterData blob from the filter's own OBS registration so our
rem instance matches the reference entry byte-for-byte (pin/media-type data).
powershell -NoProfile -Command "$s='HKLM:\SOFTWARE\Classes\CLSID\%CAT%\Instance\%FILTER%'; $d='HKLM:\SOFTWARE\Classes\CLSID\%CAT%\Instance\%INST%'; $fd=(Get-ItemProperty -Path $s -ErrorAction SilentlyContinue).FilterData; if($fd){Set-ItemProperty -Path $d -Name FilterData -Value $fd -Type Binary}; $s='HKLM:\SOFTWARE\WOW6432Node\Classes\CLSID\%CAT%\Instance\%FILTER%'; $d='HKLM:\SOFTWARE\WOW6432Node\Classes\CLSID\%CAT%\Instance\%INST%'; $fd=(Get-ItemProperty -Path $s -ErrorAction SilentlyContinue).FilterData; if($fd){Set-ItemProperty -Path $d -Name FilterData -Value $fd -Type Binary}"
echo done.
exit /b 0
""".replace("%DEVICE_NAME%", DEVICE_NAME)

_UNREGISTER_BAT = """@echo off
setlocal
cd /d "%~dp0"
set "INST={F9092F73-393F-44AB-997C-5B891FFC54F9}"
set "CAT={860BB310-5D01-11D0-BD3B-00A0C911CE86}"
reg delete "HKLM\\SOFTWARE\\Classes\\CLSID\\%CAT%\\Instance\\%INST%" /f >nul 2>&1
reg delete "HKLM\\SOFTWARE\\WOW6432Node\\Classes\\CLSID\\%CAT%\\Instance\\%INST%" /f >nul 2>&1
echo done.
exit /b 0
"""


def write_manual_bats():
    """Write register_vcam.bat / unregister_vcam.bat next to the DLLs."""
    d = ensure_vcam_files()
    for name, body in (("register_vcam.bat", _REGISTER_BAT),
                       ("unregister_vcam.bat", _UNREGISTER_BAT)):
        try:
            with open(os.path.join(d, name), "w", newline="\r\n") as f:
                f.write(body)
        except OSError:
            pass


def prepare():
    """Ensure our camera entry exists. Returns None or an error string."""
    if not HAS_PYVCAM:
        return ("virtual camera needs extra packages — run:\n"
                "  python -m pip install pyvirtualcam numpy")
    if not bundled() and not filter_registered():
        return ("virtual camera driver not bundled and no compatible filter found — "
                "install OBS Studio (free) once, or reinstall OpenCam Client with the "
                "vcam package")
    if registered():
        return None
    if not register():
        if filter_registered():
            return ("filter registered but the device entry needs admin — run OpenCam "
                    "Client as administrator once")
        return ("registration was declined or failed — 'OpenCam Virtual Camera' needs "
                "admin permission once (click Yes on the UAC prompt)")


# ============================================================================
# Frame feeder
# ============================================================================

class VirtualCam:
    """Writes frames to the registered virtual camera via pyvirtualcam."""

    def __init__(self, width, height, fps):
        self.width = int(width)
        self.height = int(height)
        self.fps = float(fps)
        self.cam = None
        self._lock = threading.Lock()

    def start(self):
        if not HAS_PYVCAM:
            raise RuntimeError("pyvirtualcam/numpy missing")
        with self._lock:
            if self.cam is not None:
                return
            self.cam = pyvirtualcam.Camera(
                width=self.width, height=self.height, fps=self.fps,
                fmt=pyvirtualcam.PixelFormat.RGB)

    def send(self, pil_img):
        cam = self.cam
        if cam is None or pil_img is None:
            return
        try:
            img = pil_img
            if img.size != (self.width, self.height):
                img = img.resize((self.width, self.height), Image.LANCZOS)
            arr = np.asarray(img)
            if arr.shape != (self.height, self.width, 3):
                arr = np.asarray(img.convert("RGB")).reshape(self.height, self.width, 3)
            cam.send(arr)
        except Exception:  # noqa: BLE001 — consumer not reading etc.; keep streaming
            pass

    def stop(self):
        with self._lock:
            if self.cam is not None:
                try:
                    self.cam.close()
                except Exception:  # noqa: BLE001
                    pass
                self.cam = None
