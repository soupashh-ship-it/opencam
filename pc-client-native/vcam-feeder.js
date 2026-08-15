// OpenCam Virtual Camera Feeder & Registration Manager
// Manages DirectShow & Windows Media Foundation (MF) registration and high-performance
// real-time video frame feeding into Windows Shared Memory for Discord, OBS, WhatsApp,
// Teams, Windows Camera App, and WebRTC browsers.

'use strict';

const path = require('path');
const fs = require('fs');
const { spawn, spawnSync, execSync, exec } = require('child_process');

// Bundled assets directory (may reside inside app.asar when packaged)
const BUNDLED_VCAM_DIR = path.join(__dirname, 'vcam');

// Permanent, physical runtime directory on disk for native binaries and COM registration
const LOCAL_APP_DATA = process.env.LOCALAPPDATA || path.join(process.env.USERPROFILE || 'C:\\Users\\Default', 'AppData', 'Local');
const RUNTIME_VCAM_DIR = path.join(LOCAL_APP_DATA, 'OpenCamStudio', 'vcam');

// Export VCAM_DIR as the permanent runtime directory
const VCAM_DIR = RUNTIME_VCAM_DIR;
const FEEDER_EXE = path.join(RUNTIME_VCAM_DIR, 'vcam_feeder.exe');
const FEEDER_SOURCE = path.join(RUNTIME_VCAM_DIR, 'OpenCamVirtualCamFeeder.cs');

/**
 * Extracts and synchronizes native helper binaries to permanent local disk storage (%LOCALAPPDATA%\OpenCamStudio\vcam).
 * When running packaged inside app.asar, regsvr32 and native process execution require real physical files.
 */
function extractVcamBinaries() {
  try {
    if (!fs.existsSync(RUNTIME_VCAM_DIR)) {
      fs.mkdirSync(RUNTIME_VCAM_DIR, { recursive: true });
    }

    const filesToExtract = [
      'vcam_feeder.exe',
      'obs-virtualcam-module64.dll',
      'obs-virtualcam-module32.dll',
      'register_vcam.bat',
      'unregister_vcam.bat',
      'OpenCamVirtualCamFeeder.cs',
    ];

    for (const filename of filesToExtract) {
      const srcPath = path.join(BUNDLED_VCAM_DIR, filename);
      const dstPath = path.join(RUNTIME_VCAM_DIR, filename);

      if (fs.existsSync(srcPath)) {
        let shouldCopy = !fs.existsSync(dstPath);
        if (!shouldCopy) {
          try {
            const srcStat = fs.statSync(srcPath);
            const dstStat = fs.statSync(dstPath);
            if (srcStat.size !== dstStat.size || srcStat.mtimeMs > dstStat.mtimeMs) {
              shouldCopy = true;
            }
          } catch (_) {
            shouldCopy = true;
          }
        }

        if (shouldCopy) {
          try {
            const content = fs.readFileSync(srcPath);
            fs.writeFileSync(dstPath, content);
            try {
              const srcStat = fs.statSync(srcPath);
              fs.utimesSync(dstPath, srcStat.atime, srcStat.mtime);
            } catch (_) {}
          } catch (copyErr) {
            // If file is locked by a running instance (EBUSY / EPERM), fallback gracefully if dest exists
            if (!fs.existsSync(dstPath)) {
              console.warn(`Could not extract ${filename}:`, copyErr.message);
            }
          }
        }
      }
    }
    return true;
  } catch (err) {
    console.error('Failed to extract virtual camera runtime binaries:', err);
    return false;
  }
}

/** Ensures the native feeder binary is compiled, extracted, and up-to-date. */
function ensureFeederBinary() {
  // 1. Always extract bundled binaries to permanent physical runtime location first
  extractVcamBinaries();

  const cscPaths = [
    path.join(process.env.windir || 'C:\\Windows', 'Microsoft.NET', 'Framework64', 'v4.0.30319', 'csc.exe'),
    path.join(process.env.windir || 'C:\\Windows', 'Microsoft.NET', 'Framework', 'v4.0.30319', 'csc.exe'),
  ];

  const csc = cscPaths.find((p) => fs.existsSync(p));
  if (!csc || !fs.existsSync(FEEDER_SOURCE)) {
    return fs.existsSync(FEEDER_EXE);
  }

  // Recompile in RUNTIME_VCAM_DIR if binary is missing or source is newer
  const bundledSource = path.join(BUNDLED_VCAM_DIR, 'OpenCamVirtualCamFeeder.cs');
  let needsCompile = !fs.existsSync(FEEDER_EXE);
  if (!needsCompile) {
    try {
      const srcMtime = Math.max(
        fs.existsSync(FEEDER_SOURCE) ? fs.statSync(FEEDER_SOURCE).mtimeMs : 0,
        fs.existsSync(bundledSource) ? fs.statSync(bundledSource).mtimeMs : 0
      );
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
    execSync(cmd, { cwd: RUNTIME_VCAM_DIR, stdio: 'ignore', timeout: 15000 });
    const bundledExe = path.join(BUNDLED_VCAM_DIR, 'vcam_feeder.exe');
    try {
      if (fs.existsSync(FEEDER_EXE)) {
        fs.copyFileSync(FEEDER_EXE, bundledExe);
      }
    } catch (_) {}
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
      const child = spawn(FEEDER_EXE, ['--feed', String(width), String(height), String(fps)], {
        cwd: RUNTIME_VCAM_DIR,
        stdio: ['pipe', 'ignore', 'pipe'],
        windowsHide: true,
      });

      if (child.stdin) {
        child.stdin.on('error', (_err) => {
          // Swallow EPIPE / write errors on stdin during shutdown/restart
        });
      }

      child.on('error', (err) => {
        console.error('Virtual camera feeder process error:', err);
        if (this.process === child) {
          this.process = null;
        }
      });

      child.on('exit', () => {
        if (this.process === child) {
          this.process = null;
        }
      });

      this.process = child;
      return true;
    } catch (err) {
      console.error('Failed to spawn virtual camera feeder process:', err);
      this.process = null;
      return false;
    }
  }

  pushFrame(jpegBuffer, ptsUs = 0) {
    if (!this.process || !this.process.stdin || this.process.killed || this.process.stdin.destroyed) {
      // Auto-restart feeder process if it was unexpectedly terminated
      if (!this.start({ width: this.currentWidth, height: this.currentHeight, fps: this.currentFps })) {
        return false;
      }
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
    const res = spawnSync(FEEDER_EXE, ['--status'], { cwd: RUNTIME_VCAM_DIR, encoding: 'utf8', timeout: 5000 });
    const stdout = (res.stdout || '').trim();
    const jsonMatch = stdout.match(/\{[\s\S]*"registered"[\s\S]*\}/);
    if (jsonMatch) {
      return JSON.parse(jsonMatch[0]);
    }
    return JSON.parse(stdout);
  } catch (err) {
    return { registered: false, directShow: false, mediaFoundation: false, error: err.message };
  }
}

/** Build PowerShell base64 encoded command for robust UAC elevation with space-safe argument passing. */
function buildElevatedPowerShellCommand(feederExe, action, targetDir) {
  const cleanFeeder = (feederExe || '').replace(/[\\/]+$/, '');
  const cleanDir = (targetDir || '').replace(/[\\/]+$/, '');
  const escapedFeeder = cleanFeeder.replace(/'/g, "''");
  const escapedDir = cleanDir.replace(/'/g, "''");
  const psScript = [
    `$ErrorActionPreference = 'Stop'`,
    `try {`,
    `  $p = Start-Process -FilePath '${escapedFeeder}' -ArgumentList @('${action}', '"${escapedDir}"') -Verb RunAs -Wait -PassThru`,
    `  if ($p) { exit $p.ExitCode } else { exit 1 }`,
    `} catch {`,
    `  exit 1223`,
    `}`
  ].join('\r\n');

  const b64 = Buffer.from(psScript, 'utf16le').toString('base64');
  return `powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand ${b64}`;
}

/** Register Virtual Camera in DirectShow and Media Foundation. */
function registerVirtualCamera(elevateIfFailed = true) {
  ensureFeederBinary();

  // If elevation is not requested (e.g. automated tests or already elevated execution), run direct
  if (!elevateIfFailed) {
    try {
      spawnSync(FEEDER_EXE, ['--register', RUNTIME_VCAM_DIR], { cwd: RUNTIME_VCAM_DIR, encoding: 'utf8', timeout: 10000 });
      const status = getVirtualCameraStatus();
      return {
        success: !!(status && status.registered),
        message: (status && status.registered) ? 'OpenCam Virtual Camera registered successfully!' : 'Registration failed',
        status
      };
    } catch (err) {
      return { success: false, message: err.message, status: getVirtualCameraStatus() };
    }
  }

  // When elevation is requested, elevate with UAC prompt to register HKLM and DeviceClasses
  return new Promise((resolve) => {
    const psCmd = buildElevatedPowerShellCommand(FEEDER_EXE, '--register', RUNTIME_VCAM_DIR);
    exec(psCmd, { timeout: 45000 }, (err) => {
      const status = getVirtualCameraStatus();
      if (!err && status && status.registered) {
        resolve({ success: true, message: 'OpenCam Virtual Camera registered successfully with Administrator privileges!', status });
      } else if (err && (err.code === 1223 || (err.message && err.message.includes('1223')))) {
        resolve({ success: false, message: 'Administrator permission was cancelled. OpenCam Virtual Camera could not be registered.', status });
      } else {
        resolve({
          success: !!(status && status.registered),
          message: (status && status.registered) ? 'OpenCam Virtual Camera registered successfully!' : ((err && err.message) || 'Registration failed'),
          status
        });
      }
    });
  });
}

/** Unregister Virtual Camera. */
function unregisterVirtualCamera(elevateIfFailed = true) {
  ensureFeederBinary();

  if (!elevateIfFailed) {
    try {
      spawnSync(FEEDER_EXE, ['--unregister', RUNTIME_VCAM_DIR], { cwd: RUNTIME_VCAM_DIR, encoding: 'utf8', timeout: 10000 });
      const status = getVirtualCameraStatus();
      return {
        success: !(status && status.registered),
        message: !(status && status.registered) ? 'OpenCam Virtual Camera unregistered successfully!' : 'Unregistration failed',
        status
      };
    } catch (err) {
      return { success: false, message: err.message, status: getVirtualCameraStatus() };
    }
  }

  return new Promise((resolve) => {
    const psCmd = buildElevatedPowerShellCommand(FEEDER_EXE, '--unregister', RUNTIME_VCAM_DIR);
    exec(psCmd, { timeout: 45000 }, (err) => {
      const status = getVirtualCameraStatus();
      const isUnregistered = !(status && status.registered);
      if (err && (err.code === 1223 || (err.message && err.message.includes('1223')))) {
        resolve({
          success: false,
          message: 'Administrator permission was cancelled. OpenCam Virtual Camera could not be unregistered.',
          status
        });
      } else {
        resolve({
          success: isUnregistered,
          message: isUnregistered ? 'OpenCam Virtual Camera unregistered successfully!' : 'Unregistration failed or cancelled',
          status
        });
      }
    });
  });
}

module.exports = {
  VirtualCamFeeder,
  extractVcamBinaries,
  ensureFeederBinary,
  getVirtualCameraStatus,
  registerVirtualCamera,
  unregisterVirtualCamera,
  buildElevatedPowerShellCommand,
  BUNDLED_VCAM_DIR,
  RUNTIME_VCAM_DIR,
  VCAM_DIR,
  FEEDER_EXE,
  FEEDER_SOURCE,
};
