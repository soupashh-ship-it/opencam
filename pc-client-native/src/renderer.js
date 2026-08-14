// OpenCam Studio Renderer Process
let isConnected = false;
let hasRenderedFrame = false;
let rotationDegrees = 0;
let isMirrored = false;
let isTorchOn = false;
let currentLens = 'BACK';
let fpsFrameCount = 0;
let lastFpsCheck = performance.now();
let statusPollTimer = null;
let frameWatchdog = null;
let watchdogShown = false;
let currentFrameBitmap = null;
let lastFrameW = 0;
let lastFrameH = 0;
let previewCodecGuard = false;
let statusPollInFlight = false;
let lastPhoneStatus = null;

// DOM Elements
const canvas = document.getElementById('video-canvas');
const ctx = canvas.getContext('2d');
const placeholder = document.getElementById('canvas-placeholder');

const ipInput = document.getElementById('phone-ip');
const portInput = document.getElementById('phone-port');
const btnConnect = document.getElementById('btn-connect');
const btnConnectText = document.getElementById('btn-connect-text');
const connectionDot = document.getElementById('connection-dot');

const selectCodec = document.getElementById('select-codec');
const selectRes = document.getElementById('select-res');
const sliderFps = document.getElementById('slider-fps');
const fpsVal = document.getElementById('fps-val');
const sliderBitrate = document.getElementById('slider-bitrate');
const bitrateVal = document.getElementById('bitrate-val');

const sliderZoom = document.getElementById('slider-zoom');
const zoomVal = document.getElementById('zoom-val');
const btnTorch = document.getElementById('btn-torch');
const btnFlip = document.getElementById('btn-flip');
const btnRotate = document.getElementById('btn-rotate');
const rotateLabel = document.getElementById('rotate-label');
const btnMirror = document.getElementById('btn-mirror');
const btnSnapshot = document.getElementById('btn-snapshot');

const hudStatusText = document.getElementById('hud-status-text');
const hudFps = document.getElementById('hud-fps');
const hudRes = document.getElementById('hud-res');
const hudBattery = document.getElementById('hud-battery');
const toast = document.getElementById('toast');

// Toast Helper
let toastTimer = null;
function showToast(message, duration = 3500) {
  toast.textContent = message;
  toast.classList.remove('hidden');
  if (toastTimer) clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    toast.classList.add('hidden');
  }, duration);
}

// Resizing Canvas Viewport
function resizeCanvasToContainer() {
  const container = canvas.parentElement;
  if (!container) return;
  canvas.width = container.clientWidth;
  canvas.height = container.clientHeight;
  if (currentFrameBitmap) renderFrame(currentFrameBitmap);
}
window.addEventListener('resize', resizeCanvasToContainer);
resizeCanvasToContainer();

// GPU-Accelerated Frame Renderer
function renderFrame(imgBitmap) {
  if (!imgBitmap) return;
  const cw = canvas.width;
  const ch = canvas.height;
  ctx.clearRect(0, 0, cw, ch);

  ctx.save();
  ctx.translate(cw / 2, ch / 2);

  // Apply rotation
  if (rotationDegrees !== 0) {
    ctx.rotate((rotationDegrees * Math.PI) / 180);
  }

  // Apply mirror
  if (isMirrored) {
    ctx.scale(-1, 1);
  }

  // Determine drawn dimensions after rotation
  const is90or270 = rotationDegrees === 90 || rotationDegrees === 270;
  const imgW = is90or270 ? imgBitmap.height : imgBitmap.width;
  const imgH = is90or270 ? imgBitmap.width : imgBitmap.height;

  // Scale to fit canvas with correct aspect ratio (never stretch)
  const scale = Math.min(cw / imgW, ch / imgH);
  const drawW = imgBitmap.width * scale;
  const drawH = imgBitmap.height * scale;

  ctx.drawImage(imgBitmap, -drawW / 2, -drawH / 2, drawW, drawH);
  ctx.restore();
}

// ---------------------------------------------------------------------------
//  Codec: this build renders MJPEG (JPEG frames) only. H.264/H.265 selections
//  are redirected to MJPEG so the stream can never silently fail to decode.
// ---------------------------------------------------------------------------
function effectiveCodec() {
  if (selectCodec.value !== 'jpg') {
    selectCodec.value = 'jpg';
    showToast('OpenCam Studio preview uses MJPEG for reliable hardware-independent decoding.', 4500);
  }
  return 'jpg';
}

// ---------------------------------------------------------------------------
//  Connection state
// ---------------------------------------------------------------------------
function setUiState(state, message) {
  hudStatusText.textContent = message;
  if (state === 'connected') {
    isConnected = true;
    hasRenderedFrame = true;
    connectionDot.className = 'dot connected';
    btnConnectText.textContent = 'Disconnect';
    btnConnect.className = 'btn btn-outline';
  } else if (state === 'connecting') {
    connectionDot.className = 'dot connecting';
    btnConnectText.textContent = 'Connecting…';
  } else if (state === 'reconnecting') {
    connectionDot.className = 'dot connecting';
  } else {
    isConnected = false;
    connectionDot.className = 'dot disconnected';
    btnConnectText.textContent = 'Connect Stream';
    btnConnect.className = 'btn btn-primary';
    if (!hasRenderedFrame) {
      placeholder.style.display = 'flex';
    }
  }
}

function markFullyConnected(frameW, frameH) {
  if (!hasRenderedFrame) {
    setUiState('connected', `Live — MJPEG ${frameW}x${frameH}`);
    showToast('Stream Connected — video is live');
  } else {
    hudStatusText.textContent = `Live — MJPEG ${frameW}x${frameH}`;
  }
  startStatusPolling();
}

function clearFrameWatchdog() {
  if (frameWatchdog) {
    clearTimeout(frameWatchdog);
    frameWatchdog = null;
  }
}

function startFrameWatchdog() {
  clearFrameWatchdog();
  watchdogShown = false;
  // If the socket connected but the phone never sends decodable frames,
  // tell the user what to check instead of showing a blank "connected" screen.
  frameWatchdog = setTimeout(() => {
    if (!hasRenderedFrame && isConnected) {
      watchdogShown = true;
      hudStatusText.textContent =
        'Connected to the server, but no video frames are arriving — make sure the phone app is streaming (v1.6.2+)';
      showToast('No video frames received — update the phone app to the latest version', 5000);
    }
  }, 6000);
}

// IPC Frame Stream Consumer
window.api.onVideoFrame(async (buffer) => {
  try {
    const blob = new Blob([buffer], { type: 'image/jpeg' });
    const bitmap = await createImageBitmap(blob);
    if (currentFrameBitmap) {
      currentFrameBitmap.close();
    }
    currentFrameBitmap = bitmap;
    lastFrameW = bitmap.width;
    lastFrameH = bitmap.height;
    renderFrame(bitmap);

    if (!hasRenderedFrame) {
      clearFrameWatchdog();
      markFullyConnected(bitmap.width, bitmap.height);
    }
    if (placeholder.style.display !== 'none') {
      placeholder.style.display = 'none';
    }

    // FPS Meter
    fpsFrameCount++;
    const now = performance.now();
    if (now - lastFpsCheck >= 1000) {
      const liveFps = Math.round((fpsFrameCount * 1000) / (now - lastFpsCheck));
      hudFps.textContent = `${liveFps} FPS`;
      fpsFrameCount = 0;
      lastFpsCheck = now;
    }
  } catch (err) {
    console.error('Frame decode error:', err);
  }
});

// IPC Stream Status Listener
window.api.onStreamStatus(({ type, msg }) => {
  if (type === 'connecting') {
    clearFrameWatchdog();
    watchdogShown = false;
    hasRenderedFrame = false;
    setUiState('connecting', msg);
    startFrameWatchdog();
  } else if (type === 'connected') {
    // Kept for compatibility; full "connected" is driven by the first frame.
    setUiState('connecting', msg);
  } else if (type === 'reconnecting') {
    setUiState('reconnecting', msg);
  } else if (type === 'error') {
    clearFrameWatchdog();
    hudStatusText.textContent = msg;
    if (!hasRenderedFrame) {
      setUiState('disconnected', msg);
      showToast(msg, 4500);
    }
  } else if (type === 'failed') {
    clearFrameWatchdog();
    stopStatusPolling();
    setUiState('disconnected', msg);
    showToast(msg, 7000);
  } else if (type === 'disconnected') {
    clearFrameWatchdog();
    stopStatusPolling();
    if (hasRenderedFrame) {
      setUiState('disconnected', 'Disconnected');
      showToast('Stream Disconnected', 2500);
    } else {
      setUiState('disconnected', msg);
    }
  }
});

// Status Poller
function syncStatusToUi(status) {
  lastPhoneStatus = status;
  if (status.battery !== undefined) hudBattery.textContent = `${status.battery}%`;
  const sw = status.streamWidth || status.width;
  const sh = status.streamHeight || status.height;
  if (sw && sh) {
    const liveResolution = `${sw}x${sh}`;
    hudRes.textContent = liveResolution;
    if ([...selectRes.options].some((option) => option.value === liveResolution)) {
      selectRes.value = liveResolution;
    }
  }
  if (status.fps) {
    const fps = Number(status.fps);
    if (Number.isFinite(fps)) {
      sliderFps.value = String(Math.max(15, Math.min(60, fps)));
      fpsVal.textContent = sliderFps.value;
    }
  }
  if (status.bitrateMbps) {
    const bitrate = Number(status.bitrateMbps);
    if (Number.isFinite(bitrate)) {
      sliderBitrate.value = String(Math.max(2, Math.min(60, Math.round(bitrate / 2) * 2)));
      bitrateVal.textContent = `${sliderBitrate.value} Mbps`;
    }
  }
  if (status.zoom !== undefined) {
    const zoom = Number(status.zoom);
    const maxZoom = Number(status.maxZoom) || 8;
    if (Number.isFinite(zoom)) {
      const bounded = Math.max(1, Math.min(8, Math.min(maxZoom, zoom)));
      sliderZoom.value = bounded.toFixed(1);
      zoomVal.textContent = `${bounded.toFixed(1)}x`;
    }
  }
  if (typeof status.torch === 'boolean') {
    isTorchOn = status.torch;
    btnTorch.classList.toggle('active', isTorchOn);
  }
  if (status.lens) {
    currentLens = String(status.lens).toUpperCase();
  }
  if (status.codec && String(status.codec).toLowerCase() !== 'jpg' && !previewCodecGuard && isConnected) {
    previewCodecGuard = true;
    window.api.pushSettings(ipInput.value.trim(), parseInt(portInput.value.trim(), 10) || 4747, { codec: 'jpg' })
      .finally(() => setTimeout(() => { previewCodecGuard = false; }, 1200));
    showToast('Phone switched to a non-MJPEG codec. OpenCam Studio restored MJPEG for desktop preview.', 5000);
  }
  if (status.running === false) {
    hudStatusText.textContent = 'Phone is connected but streaming is off — press Start on the phone';
  }
}

function startStatusPolling() {
  stopStatusPolling();
  const poll = async () => {
    if (statusPollInFlight || !isConnected) return;
    const ip = ipInput.value.trim();
    const port = parseInt(portInput.value.trim(), 10) || 4747;
    if (!ip) return;
    statusPollInFlight = true;
    try {
      const status = await window.api.getStatus(ip, port);
      if (status) syncStatusToUi(status);
    } finally {
      statusPollInFlight = false;
    }
  };
  poll();
  statusPollTimer = setInterval(poll, 3000);
}

function stopStatusPolling() {
  if (statusPollTimer) {
    clearInterval(statusPollTimer);
    statusPollTimer = null;
  }
}

// ---------------------------------------------------------------------------
//  UI Controls Event Handlers
// ---------------------------------------------------------------------------
btnConnect.addEventListener('click', () => {
  const ip = ipInput.value.trim();
  const port = parseInt(portInput.value.trim(), 10);
  const codec = effectiveCodec(); // always 'jpg' in this build
  const [w, h] = selectRes.value.split('x').map((v) => parseInt(v, 10));

  if (!ip) {
    showToast('Enter the phone IP or use Scan Wi-Fi first');
    ipInput.focus();
    return;
  }
  const ipValid = /^(?:\d{1,3}\.){3}\d{1,3}$/.test(ip) && ip.split('.').every((v) => Number(v) >= 0 && Number(v) <= 255);
  if (!ipValid) {
    showToast('Enter a valid IPv4 address, for example 192.168.1.42');
    ipInput.focus();
    return;
  }
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    showToast('Port must be between 1 and 65535');
    portInput.focus();
    return;
  }

  if (isConnected) {
    window.api.disconnectStream();
    setUiState('disconnected', 'Disconnected');
  } else {
    clearFrameWatchdog();
    hasRenderedFrame = false;
    setUiState('connecting', `Connecting to ${ip}:${port}…`);
    startFrameWatchdog();
    window.api.connectStream({ ip, port, codec, width: w, height: h });
  }
});

let pendingLensSwitch = null;

// Settings Changes
async function pushCurrentSettings() {
  if (!isConnected) return;
  const ip = ipInput.value.trim();
  const port = parseInt(portInput.value.trim(), 10) || 4747;
  const [w, h] = selectRes.value.split('x').map((v) => parseInt(v, 10));
  const params = {
    codec: effectiveCodec(), // always 'jpg'
    width: w,
    height: h,
    fps: sliderFps.value,
    bitrate: sliderBitrate.value,
    zoom: sliderZoom.value,
    torch: isTorchOn ? '1' : '0',
  };
  if (pendingLensSwitch) {
    params.lens = pendingLensSwitch;
    pendingLensSwitch = null;
  }
  await window.api.pushSettings(ip, port, params);
}

selectCodec.addEventListener('change', () => {
  const codec = effectiveCodec();
  localStorage.setItem('opencam_codec', codec);
  if (isConnected && codec === 'jpg') pushCurrentSettings();
});
selectRes.addEventListener('change', () => {
  localStorage.setItem('opencam_res', selectRes.value);
  if (isConnected) pushCurrentSettings();
});

sliderFps.addEventListener('input', () => {
  fpsVal.textContent = sliderFps.value;
});
sliderFps.addEventListener('change', pushCurrentSettings);

sliderBitrate.addEventListener('input', () => {
  bitrateVal.textContent = `${sliderBitrate.value} Mbps`;
});
sliderBitrate.addEventListener('change', pushCurrentSettings);

sliderZoom.addEventListener('input', () => {
  zoomVal.textContent = `${parseFloat(sliderZoom.value).toFixed(1)}x`;
});
sliderZoom.addEventListener('change', pushCurrentSettings);

btnTorch.addEventListener('click', () => {
  isTorchOn = !isTorchOn;
  btnTorch.classList.toggle('active', isTorchOn);
  pushCurrentSettings();
});

btnFlip.addEventListener('click', () => {
  currentLens = currentLens === 'BACK' ? 'FRONT' : 'BACK';
  pendingLensSwitch = currentLens;
  pushCurrentSettings();
  showToast(`Switched to ${currentLens} camera`);
});

btnRotate.addEventListener('click', () => {
  rotationDegrees = (rotationDegrees + 90) % 360;
  rotateLabel.textContent = `Rotate: ${rotationDegrees}°`;
  if (currentFrameBitmap) renderFrame(currentFrameBitmap);
});

btnMirror.addEventListener('click', () => {
  isMirrored = !isMirrored;
  btnMirror.classList.toggle('active', isMirrored);
  if (currentFrameBitmap) renderFrame(currentFrameBitmap);
});

btnSnapshot.addEventListener('click', async () => {
  if (!currentFrameBitmap) {
    showToast('No video frame to capture');
    return;
  }
  // Create offscreen canvas for snapshot with rotation/mirror applied
  const offscreen = document.createElement('canvas');
  const is90or270 = rotationDegrees === 90 || rotationDegrees === 270;
  const sw = is90or270 ? currentFrameBitmap.height : currentFrameBitmap.width;
  const sh = is90or270 ? currentFrameBitmap.width : currentFrameBitmap.height;
  offscreen.width = sw;
  offscreen.height = sh;
  const oCtx = offscreen.getContext('2d');

  oCtx.translate(sw / 2, sh / 2);
  if (rotationDegrees !== 0) oCtx.rotate((rotationDegrees * Math.PI) / 180);
  if (isMirrored) oCtx.scale(-1, 1);
  oCtx.drawImage(
    currentFrameBitmap,
    -currentFrameBitmap.width / 2,
    -currentFrameBitmap.height / 2
  );

  const dataUrl = offscreen.toDataURL('image/png');
  const res = await window.api.saveSnapshot(dataUrl);
  if (res.success) {
    showToast(`Snapshot saved to Pictures folder!`);
  } else {
    showToast(`Failed to save snapshot: ${res.error}`);
  }
});

// LocalStorage Restoration & Persistence
function loadSavedPreferences() {
  const savedIp = localStorage.getItem('opencam_ip');
  const savedPort = localStorage.getItem('opencam_port');
  const savedCodec = localStorage.getItem('opencam_codec');
  const savedRes = localStorage.getItem('opencam_res');

  if (savedIp) ipInput.value = savedIp;
  if (savedPort) portInput.value = savedPort;
  if (savedCodec === 'jpg') selectCodec.value = 'jpg';
  if (savedRes) selectRes.value = savedRes;
  effectiveCodec(); // sanitize any saved H.26x selection
}
if (!localStorage.getItem('opencam_ip')) ipInput.value = '';
loadSavedPreferences();

ipInput.addEventListener('change', () => localStorage.setItem('opencam_ip', ipInput.value.trim()));
portInput.addEventListener('change', () => localStorage.setItem('opencam_port', portInput.value.trim()));

// Wi-Fi Auto-Scan Button
const btnScan = document.getElementById('btn-scan');
if (btnScan) {
  btnScan.addEventListener('click', async () => {
    btnScan.textContent = 'Scanning...';
    showToast('Scanning Wi-Fi network for OpenCam phones...');
    const port = parseInt(portInput.value.trim(), 10) || 4747;
    const foundIp = await window.api.scanDevices(port);
    btnScan.innerHTML = `<svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg> Scan Wi-Fi`;
    if (foundIp) {
      ipInput.value = foundIp;
      localStorage.setItem('opencam_ip', foundIp);
      showToast(`Discovered phone at ${foundIp}!`);
    } else {
      showToast('No phone found on Wi-Fi — ensure OpenCam app is open');
    }
  });
}

// Always on Top Toggle Button
const btnPin = document.getElementById('btn-pin');
if (btnPin) {
  btnPin.addEventListener('click', async () => {
    const isPinned = await window.api.toggleAlwaysOnTop();
    btnPin.classList.toggle('active', isPinned);
    showToast(isPinned ? 'Window pinned Always on Top' : 'Window unpinned');
  });
}

// Register Virtual Camera Button (for Teams / WhatsApp / Discord / OBS)
const btnVcam = document.getElementById('btn-vcam');
async function syncVcamStatus() {
  if (!btnVcam || !window.api.getVcamStatus) return;
  try {
    const status = await window.api.getVcamStatus();
    if (status && status.registered) {
      btnVcam.textContent = '🎥 Virtual Camera: Active (MF & DirectShow)';
      btnVcam.classList.add('active');
    } else {
      btnVcam.textContent = '🎥 Register Virtual Camera (Teams / WhatsApp / OBS)';
      btnVcam.classList.remove('active');
    }
  } catch (_) {}
}

if (btnVcam) {
  syncVcamStatus();
  btnVcam.addEventListener('click', async () => {
    btnVcam.textContent = 'Registering Virtual Camera...';
    showToast('Registering OpenCam Virtual Camera for DirectShow and Media Foundation...');
    const res = await window.api.registerVcam();
    if (res && res.success) {
      showToast('OpenCam Virtual Camera registered! Available in Teams, WhatsApp, Camera App, Discord & OBS.');
    } else {
      showToast(`Registration note: ${(res && res.message) || 'Completed'}`);
    }
    syncVcamStatus();
  });
}

// Clean Feed Mode Toggle (OBS Capture Mode)
function toggleCleanFeed() {
  document.body.classList.toggle('clean-feed');
  const isClean = document.body.classList.contains('clean-feed');
  const btnClean = document.getElementById('btn-clean');
  if (btnClean) btnClean.classList.toggle('active', isClean);
  resizeCanvasToContainer();
  showToast(isClean ? 'Clean Feed Active (OBS Mode) — Double Click or press ESC to exit' : 'Restored UI View');
}

const btnClean = document.getElementById('btn-clean');
if (btnClean) btnClean.addEventListener('click', toggleCleanFeed);

canvas.addEventListener('dblclick', toggleCleanFeed);

// Global Keyboard Hotkeys (unless focused in text inputs)
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape' && document.body.classList.contains('clean-feed')) {
    toggleCleanFeed();
    return;
  }
  const activeTag = document.activeElement ? document.activeElement.tagName : '';
  if (activeTag === 'INPUT' || activeTag === 'SELECT') return;

  const key = e.key.toLowerCase();
  if (key === 'r') btnRotate.click();
  else if (key === 'm') btnMirror.click();
  else if (key === 's') btnSnapshot.click();
  else if (key === 'f') btnFlip.click();
  else if (key === 'q') btnConnect.click();
  else if (key === 'c') toggleCleanFeed();
});
