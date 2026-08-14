// OpenCam Virtual Camera Feeder & Registration Manager
// Manages DirectShow & Windows Media Foundation (MF) registration and high-performance
// real-time video frame feeding into Windows Shared Memory for Discord, OBS, WhatsApp,
// Teams, Windows Camera App, and WebRTC browsers.

'use strict';

const path = require('path');
const fs = require('fs');
const { spawn, execSync, exec } = require('child_process');

const VCAM_DIR = path.join(__dirname, 'vcam');
const FEEDER_EXE = path.join(VCAM_DIR, 'vcam_feeder.exe');
const FEEDER_SOURCE = path.join(VCAM_DIR, 'OpenCamVirtualCamFeeder.cs');

/** Ensures the native feeder binary is compiled and up-to-date. */
function ensureFeederBinary() {
  const cscPaths = [
    path.join(process.env.windir || 'C:\\Windows', 'Microsoft.NET', 'Framework64', 'v4.0.30319', 'csc.exe'),
    path.join(process.env.windir || 'C:\\Windows', 'Microsoft.NET', 'Framework', 'v4.0.30319', 'csc.exe'),
  ];

  const csc = cscPaths.find((p) => fs.existsSync(p));
  if (!csc || !fs.existsSync(FEEDER_SOURCE)) {
    return fs.existsSync(FEEDER_EXE);
  }

  // Recompile if binary is missing or source is newer
  let needsCompile = !fs.existsSync(FEEDER_EXE);
  if (!needsCompile) {
    try {
      const srcMtime = fs.statSync(FEEDER_SOURCE).mtimeMs;
      const exeMtime = fs.statSync(FEEDER_EXE).mtimeMs;
      if (srcMtime > exeMtime) {
        needsCompile = true;
      }
    } catch (_) {}
  }

  if (!needsCompile) {
    return true;
  }

  try {
    const cmd = `"${csc}" /nologo /unsafe /optimize /platform:x64 /r:System.Drawing.dll /out:"${FEEDER_EXE}" "${FEEDER_SOURCE}"`;
    execSync(cmd, { cwd: VCAM_DIR, stdio: 'ignore', timeout: 15000 });
    return fs.existsSync(FEEDER_EXE);
  } catch (err) {
    console.error('Failed to compile virtual camera feeder binary:', err);
    return fs.existsSync(FEEDER_EXE);
  }
}

class VirtualCamFeeder {
  constructor() {
    this.process = null;
    this.currentWidth = 1920;
    this.currentHeight = 1080;
    this.currentFps = 30;
    this.isStarting = false;
    this.framesPushed = 0;
    this.droppedFrames = 0;
  }

  start(config = {}) {
    const width = config.width || 1920;
    const height = config.height || 1080;
    const fps = config.fps || 30;

    if (this.process && !this.process.killed) {
      if (this.currentWidth === width && this.currentHeight === height && this.currentFps === fps) {
        return true;
      }
      this.stop();
    }

    if (!ensureFeederBinary()) {
      console.warn('Virtual camera feeder binary not available');
      return false;
    }

    this.currentWidth = width;
    this.currentHeight = height;
    this.currentFps = fps;
    this.framesPushed = 0;
    this.droppedFrames = 0;

    try {
      this.process = spawn(FEEDER_EXE, ['--feed', String(width), String(height), String(fps)], {
        cwd: VCAM_DIR,
        stdio: ['pipe', 'ignore', 'pipe'],
        windowsHide: true,
      });

      if (this.process.stdin) {
        this.process.stdin.on('error', (err) => {
          // Swallow EPIPE / write errors on stdin during shutdown/restart
        });
      }

      this.process.on('error', (err) => {
        console.error('Virtual camera feeder process error:', err);
        this.process = null;
      });

      this.process.on('exit', () => {
        this.process = null;
      });

      return true;
    } catch (err) {
      console.error('Failed to spawn virtual camera feeder process:', err);
      this.process = null;
      return false;
    }
  }

  pushFrame(jpegBuffer, ptsUs = 0) {
    if (!this.process || !this.process.stdin || this.process.killed || this.process.stdin.destroyed) {
      return false;
    }

    if (!Buffer.isBuffer(jpegBuffer) || jpegBuffer.length === 0) {
      return false;
    }

    // Drop frame if write queue is congested to maintain real-time low latency
    if (this.process.stdin.writableLength > 4 * 1024 * 1024) {
      this.droppedFrames++;
      return false;
    }

    try {
      const header = Buffer.allocUnsafe(12);
      header.writeBigUInt64BE(BigInt(ptsUs || Date.now() * 1000), 0);
      header.writeUInt32BE(jpegBuffer.length, 8);

      this.process.stdin.write(header);
      this.process.stdin.write(jpegBuffer);
      this.framesPushed++;
      return true;
    } catch (err) {
      return false;
    }
  }

  stop() {
    if (this.process) {
      try {
        if (this.process.stdin && !this.process.stdin.destroyed) {
          try { this.process.stdin.end(); } catch (_) {}
        }
        const proc = this.process;
        const killTimer = setTimeout(() => {
          if (proc && !proc.killed) {
            try { proc.kill(); } catch (_) {}
          }
        }, 500);
        if (killTimer.unref) killTimer.unref();
      } catch (_) {}
      this.process = null;
    }
  }

  isRunning() {
    return !!(this.process && !this.process.killed);
  }
}

/** Check Virtual Camera registration status. */
function getVirtualCameraStatus() {
  ensureFeederBinary();
  if (!fs.existsSync(FEEDER_EXE)) {
    return { registered: false, directShow: false, mediaFoundation: false, friendlyName: 'OpenCam Virtual Camera' };
  }

  try {
    const out = execSync(`"${FEEDER_EXE}" --status`, { cwd: VCAM_DIR, encoding: 'utf8', timeout: 5000 }).trim();
    return JSON.parse(out);
  } catch (err) {
    return { registered: false, directShow: false, mediaFoundation: false, error: err.message };
  }
}

/** Register Virtual Camera in DirectShow and Media Foundation. */
function registerVirtualCamera(elevateIfFailed = true) {
  ensureFeederBinary();

  // Try direct registration first (succeeds for HKCU + if running elevated)
  try {
    const out = execSync(`"${FEEDER_EXE}" --register "${VCAM_DIR}"`, { cwd: VCAM_DIR, encoding: 'utf8', timeout: 10000 });
    const status = getVirtualCameraStatus();
    if (status.registered) {
      return { success: true, message: 'OpenCam Virtual Camera registered successfully!', status };
    }
  } catch (_) {}

  // If HKLM registration requires elevation, prompt UAC via PowerShell
  if (elevateIfFailed) {
    return new Promise((resolve) => {
      const psCmd = `powershell -Command "Start-Process -FilePath '${FEEDER_EXE}' -ArgumentList '--register \\"${VCAM_DIR}\\"' -Verb RunAs -Wait"`;
      exec(psCmd, { timeout: 30000 }, (err) => {
        const status = getVirtualCameraStatus();
        if (!err && status.registered) {
          resolve({ success: true, message: 'OpenCam Virtual Camera registered successfully with Administrator privileges!', status });
        } else {
          resolve({ success: status.registered, message: status.registered ? 'Registered successfully' : 'Registration cancelled or failed', status });
        }
      });
    });
  }

  return { success: false, message: 'Registration failed' };
}

/** Unregister Virtual Camera. */
function unregisterVirtualCamera(elevateIfFailed = true) {
  ensureFeederBinary();

  try {
    execSync(`"${FEEDER_EXE}" --unregister "${VCAM_DIR}"`, { cwd: VCAM_DIR, encoding: 'utf8', timeout: 10000 });
    const status = getVirtualCameraStatus();
    if (!status.registered) {
      return { success: true, message: 'OpenCam Virtual Camera unregistered successfully!', status };
    }
  } catch (_) {}

  if (elevateIfFailed) {
    return new Promise((resolve) => {
      const psCmd = `powershell -Command "Start-Process -FilePath '${FEEDER_EXE}' -ArgumentList '--unregister \\"${VCAM_DIR}\\"' -Verb RunAs -Wait"`;
      exec(psCmd, { timeout: 30000 }, (err) => {
        const status = getVirtualCameraStatus();
        resolve({ success: !status.registered, message: !status.registered ? 'OpenCam Virtual Camera unregistered successfully' : 'Unregistration failed', status });
      });
    });
  }

  return { success: false, message: 'Unregistration failed' };
}

module.exports = {
  VirtualCamFeeder,
  ensureFeederBinary,
  getVirtualCameraStatus,
  registerVirtualCamera,
  unregisterVirtualCamera,
  VCAM_DIR,
  FEEDER_EXE,
  FEEDER_SOURCE,
};
