# Virtual camera filter (bundled)

`obs-virtualcam-module64.dll` / `obs-virtualcam-module32.dll` are the standalone
DirectShow source filters that **OBS Studio** ships for its own *Virtual Camera*,
copied unmodified from a stock OBS Studio installation.

* **Source / license:** [OBS Studio](https://github.com/obsproject/obs-studio),
  GPL-2.0. The DLLs are redistributed here with attribution so OpenCam Client can
  register itself as a camera in Discord / Zoom / WhatsApp / Google Meet without
  OBS being installed. See https://github.com/obsproject/obs-studio/blob/master/COPYING
* **Mechanism:** `virtualcam.py` registers the filter once with `regsvr32 /i`
  (admin / one UAC prompt) and writes the phone's frames into its shared-memory
  buffer with `pyvirtualcam`, then brands the device "OpenCam Virtual Camera".

## How the pieces fit

* On first use of the **Virtual cam** toggle, the client copies the DLLs from
  this folder to `%LOCALAPPDATA%\OpenCamClient\vcam` (a persistent, no-spaces
  path) and elevates a small PowerShell script that runs `regsvr32 /i` and sets
  the device's `FriendlyName` to **OpenCam Virtual Camera**.
* From then on the camera shows up in any app that lists webcams
  (Discord → Settings → Cameras, Zoom, WhatsApp, Google Meet, OBS itself, …).
* While the toggle is ON, the client feeds the phone's frames into the camera's
  shared-memory buffer — other apps see a live video source.

## Manual control (no client needed)

The same scripts are written to `%LOCALAPPDATA%\OpenCamClient\vcam`:

* `register_vcam.bat` — right-click → *Run as administrator* to install the
  camera and brand it "OpenCam Virtual Camera".
* `unregister_vcam.bat` — right-click → *Run as administrator* to remove it.

Or run the client once with `OpenCamClient.exe --register-vcam`.

## Remove

* Run `unregister_vcam.bat` as administrator, and/or delete
  `%LOCALAPPDATA%\OpenCamClient\vcam`.
* If you installed via the client, disabling the toggle does not unregister the
  device — unregistering is a one-time admin operation so the client leaves the
  device installed once you've chosen to set it up.

> Note for distributors: these DLLs are GPL-2.0 components. Keep this notice with
> them and make the OBS Studio sources available (see link above) when
> redistributing.
