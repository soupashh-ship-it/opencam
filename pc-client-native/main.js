const { app, BrowserWindow, ipcMain } = require('electron');
const path = require('path');
const net = require('net');
const http = require('http');
const fs = require('fs');
const { buildVideoRequest, createFrameParser } = require('./stream-parser');
const {
  VirtualCamFeeder,
  registerVirtualCamera,
  unregisterVirtualCamera,
  getVirtualCameraStatus,
  ensureFeederBinary,
} = require('./vcam-feeder');

let mainWindow = null;
let videoSocket = null;
let isConnected = false;
let stopRequested = false;
const vcamFeeder = new VirtualCamFeeder();

// Connection bookkeeping (see retry policy in scheduleReconnect).
let connectAttempts = 0;
let framesEverReceived = false;
let lastConnectError = null;
let connectionGeneration = 0;
let reconnectTimer = null;

// How many failed attempts before we give up and ask the user to act.
const MAX_CONNECT_ATTEMPTS = 9;

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1280,
    height: 820,
    minWidth: 960,
    minHeight: 640,
    title: 'OpenCam Studio',
    backgroundColor: '#0c0f17',
    frame: true,
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });

  mainWindow.loadFile(path.join(__dirname, 'src', 'index.html'));

  mainWindow.on('closed', () => {
    disconnectStream();
    mainWindow = null;
  });
}

// ---------------------------------------------------------------------------
//  Networking & Wire Protocol Implementation
// ---------------------------------------------------------------------------
function sendStatus(type, msg) {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send('stream-status', { type, msg });
  }
}

function invalidateReconnectTimer() {
  if (reconnectTimer) {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }
}

function disconnectStream() {
  connectionGeneration++;
  invalidateReconnectTimer();
  stopRequested = true;
  isConnected = false;
  if (videoSocket) {
    try { videoSocket.destroy(); } catch (_) {}
    videoSocket = null;
  }
}

function connectVideo(ip, port, codec, width, height) {
  disconnectStream();
  stopRequested = false;
  isConnected = true;
  lastConnectError = null;
  const generation = connectionGeneration;

  const w = width || 1920;
  const h = height || 1080;
  try { vcamFeeder.start({ width: w, height: h, fps: 30 }); } catch (_) {}

  const sock = new net.Socket();
  videoSocket = sock;
  sock.setNoDelay(true);
  sock.setKeepAlive(true, 5000);
  sock.setTimeout(8000);

  const isCurrent = () => generation === connectionGeneration && videoSocket === sock;

  const parser = createFrameParser({
    onError: (message) => {
      if (!stopRequested && isCurrent()) {
        sendStatus('error', `Stream framing error — reconnecting safely (${message})`);
        try { sock.destroy(); } catch (_) {}
      }
    },
    onFrame: (payload, pts) => {
      // A frame means the stream is genuinely live: reset the failure counter.
      framesEverReceived = true;
      connectAttempts = 0;
      try { vcamFeeder.pushFrame(payload, pts); } catch (_) {}
      if (!stopRequested && mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.webContents.send('video-frame', payload);
      }
    },
  });

  sock.connect(port, ip, () => {
    if (stopRequested || !isCurrent()) return;
    const req = buildVideoRequest(codec, width, height, port);
    sock.write(req);
    sendStatus('connecting', `Connected to ${ip}:${port} — waiting for video…`);
  });

  sock.on('data', (chunk) => {
    if (stopRequested || !isCurrent()) return;
    parser.push(chunk);
  });

  sock.on('error', (err) => {
    if (!isCurrent()) return;
    lastConnectError = err;
    if (!stopRequested) {
      const code = err && err.code;
      if (code === 'ECONNREFUSED') {
        sendStatus(
          'error',
          `Phone not reachable at ${ip}:${port} — is the OpenCam app open and streaming (Start pressed)?`
        );
      } else if (code === 'ETIMEDOUT') {
        sendStatus('error', `No response from ${ip}:${port} — check both devices are on the same Wi-Fi.`);
      } else {
        sendStatus('error', `Connection error: ${err.message}`);
      }
    }
  });

  sock.on('close', () => {
    if (!isCurrent()) return;
    if (stopRequested || !isConnected) {
      if (!stopRequested) sendStatus('disconnected', 'Disconnected');
      return;
    }
    scheduleReconnect(ip, port, codec, width, height);
  });
}

function scheduleReconnect(ip, port, codec, width, height) {
  const generation = connectionGeneration;
  invalidateReconnectTimer();
  connectAttempts++;

  // Once video has flowed, a drop is just a blip — reconnect quickly.
  if (framesEverReceived) {
    sendStatus('reconnecting', 'Stream dropped — reconnecting…');
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null;
      if (!stopRequested && isConnected && generation === connectionGeneration) connectVideo(ip, port, codec, width, height);
    }, 1000);
    return;
  }

  // Never got a single frame: escalating backoff, then give up with guidance.
  if (connectAttempts < 4) {
    sendStatus('reconnecting', `Retrying (${connectAttempts}/${MAX_CONNECT_ATTEMPTS})…`);
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null;
      if (!stopRequested && isConnected && generation === connectionGeneration) connectVideo(ip, port, codec, width, height);
    }, 1500);
  } else if (connectAttempts < MAX_CONNECT_ATTEMPTS) {
    sendStatus('reconnecting', `Still no video — retrying (${connectAttempts}/${MAX_CONNECT_ATTEMPTS})…`);
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null;
      if (!stopRequested && isConnected && generation === connectionGeneration) connectVideo(ip, port, codec, width, height);
    }, 3000);
  } else {
    isConnected = false;
    sendStatus(
      'failed',
      `Could not connect to ${ip}:${port}. Check: (1) the OpenCam app is open and streaming, ` +
        '(2) phone and PC are on the same Wi-Fi, (3) the IP is correct. If the phone app is ' +
        'outdated, update it to v1.6.2 or newer. Press Connect to try again.'
    );
  }
}

function fetchStatus(ip, port) {
  return new Promise((resolve) => {
    const req = http.request(
      {
        host: ip,
        port: port,
        path: '/v1/status',
        method: 'GET',
        timeout: 2500,
      },
      (res) => {
        let data = '';
        res.on('data', (chunk) => { data += chunk; });
        res.on('end', () => {
          try { resolve(JSON.parse(data)); } catch (_) { resolve(null); }
        });
      }
    );
    req.on('error', () => resolve(null));
    req.on('timeout', () => { req.destroy(); resolve(null); });
    req.end();
  });
}

function pushSettings(ip, port, params) {
  return new Promise((resolve) => {
    const query = Object.entries(params)
      .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`)
      .join('&');
    const req = http.request(
      {
        host: ip,
        port: port,
        path: `/v1/settings?${query}`,
        method: 'PUT',
        timeout: 2500,
      },
      (res) => {
        resolve(res.statusCode === 200);
      }
    );
    req.on('error', () => resolve(false));
    req.on('timeout', () => { req.destroy(); resolve(false); });
    req.end();
  });
}

// ---------------------------------------------------------------------------
//  IPC Event Handlers
// ---------------------------------------------------------------------------
ipcMain.handle('connect-stream', async (_event, { ip, port, codec, width, height }) => {
  connectAttempts = 0;
  framesEverReceived = false;
  lastConnectError = null;
  connectVideo(ip, port, codec, width, height);
  return true;
});

ipcMain.handle('disconnect-stream', async () => {
  disconnectStream();
  sendStatus('disconnected', 'Disconnected');
  return true;
});

ipcMain.handle('get-status', async (_event, { ip, port }) => {
  return await fetchStatus(ip, port);
});

ipcMain.handle('push-settings', async (_event, { ip, port, params }) => {
  return await pushSettings(ip, port, params);
});

ipcMain.handle('save-snapshot', async (_event, dataUrl) => {
  try {
    const base64Data = dataUrl.replace(/^data:image\/png;base64,/, '');
    const picturesDir = app.getPath('pictures');
    const filename = `OpenCam_${Date.now()}.png`;
    const filePath = path.join(picturesDir, filename);
    fs.writeFileSync(filePath, base64Data, 'base64');
    return { success: true, path: filePath };
  } catch (err) {
    return { success: false, error: err.message };
  }
});

ipcMain.handle('scan-devices', async (_event, port = 4747) => {
  const numericPort = Number(port);
  if (!Number.isInteger(numericPort) || numericPort < 1 || numericPort > 65535) return null;

  const interfaces = require('os').networkInterfaces();
  const subnets = new Set();
  const ipv4ToInt = (value) => value.split('.').reduce((n, octet) => (n << 8) | Number(octet), 0) >>> 0;
  const intToIp = (value) => [24, 16, 8, 0].map((shift) => (value >>> shift) & 255).join('.');

  for (const entries of Object.values(interfaces)) {
    for (const iface of entries || []) {
      if (iface.family !== 'IPv4' || iface.internal || !iface.netmask) continue;
      const mask = ipv4ToInt(iface.netmask);
      const address = ipv4ToInt(iface.address);
      const network = (address & mask) >>> 0;
      const broadcast = (network | (~mask >>> 0)) >>> 0;
      const first = network + 1;
      const last = broadcast - 1;
      if (last >= first && last - first <= 1022) {
        subnets.add(JSON.stringify({ first, last }));
      }
    }
  }

  const ranges = [...subnets].map((value) => JSON.parse(value));
  if (ranges.length === 0) return null;

  const probe = (targetIp) => new Promise((resolve) => {
    const req = http.request(
      { host: targetIp, port: numericPort, path: '/v1/status', method: 'GET', timeout: 650 },
      (res) => {
        let data = '';
        res.setEncoding('utf8');
        res.on('data', (chunk) => { data += chunk; });
        res.on('end', () => {
          if (res.statusCode !== 200) return resolve(null);
          try {
            const status = JSON.parse(data);
            resolve(status && typeof status.version === 'string' ? targetIp : null);
          } catch (_) { resolve(null); }
        });
      }
    );
    req.on('error', () => resolve(null));
    req.on('timeout', () => { req.destroy(); resolve(null); });
    req.end();
  });

  for (const range of ranges) {
    let next = range.first;
    let found = null;
    const workers = Array.from({ length: Math.min(24, range.last - range.first + 1) }, async () => {
      while (found === null && next <= range.last) {
        const target = next++;
        const result = await probe(intToIp(target));
        if (result && found === null) found = result;
      }
    });
    await Promise.all(workers);
    if (found) return found;
  }
  return null;
});

ipcMain.handle('toggle-always-on-top', async () => {
  if (!mainWindow) return false;
  const current = mainWindow.isAlwaysOnTop();
  mainWindow.setAlwaysOnTop(!current);
  return !current;
});

ipcMain.handle('register-vcam', async () => {
  try {
    return await registerVirtualCamera(true);
  } catch (err) {
    return { success: false, message: err.message };
  }
});

ipcMain.handle('unregister-vcam', async () => {
  try {
    return await unregisterVirtualCamera(true);
  } catch (err) {
    return { success: false, message: err.message };
  }
});

ipcMain.handle('get-vcam-status', async () => {
  try {
    return getVirtualCameraStatus();
  } catch (err) {
    return { registered: false, directShow: false, mediaFoundation: false, error: err.message };
  }
});

// App Lifecycle
app.whenReady().then(() => {
  try {
    ensureFeederBinary();
    // Start always-on 30 FPS 1080p standby loop for virtual camera consumers
    vcamFeeder.start({ width: 1920, height: 1080, fps: 30 });
  } catch (err) {
    console.warn('Initial vcam binary extraction/feeder note:', err.message);
  }
  createWindow();
});

app.on('will-quit', () => {
  disconnectStream();
  try { vcamFeeder.stop(); } catch (_) {}
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});

process.on('exit', () => {
  try { vcamFeeder.stop(); } catch (_) {}
});

process.on('SIGINT', () => {
  try { vcamFeeder.stop(); } catch (_) {}
  process.exit(0);
});

process.on('SIGTERM', () => {
  try { vcamFeeder.stop(); } catch (_) {}
  process.exit(0);
});
