/**
 * OpenCam Interactive Web Application & Simulator Logic
 * Zero-dependency, performant Vanilla JavaScript
 */

document.addEventListener('DOMContentLoaded', () => {
  initTabs();
  initSimulator();
});

/* ==========================================================================
   Tab Navigation for Setup Guides
   ========================================================================== */
function initTabs() {
  const tabButtons = document.querySelectorAll('.tab-btn');
  const tabPanes = document.querySelectorAll('.tab-pane');

  tabButtons.forEach(btn => {
    btn.addEventListener('click', () => {
      const targetTabId = btn.getAttribute('data-tab');

      tabButtons.forEach(b => b.classList.remove('active'));
      tabPanes.forEach(p => p.classList.remove('active'));

      btn.classList.add('active');
      const targetPane = document.getElementById(targetTabId);
      if (targetPane) {
        targetPane.classList.add('active');
      }
    });
  });
}

/* ==========================================================================
   Two-Way Interactive Live Simulator
   ========================================================================== */
function initSimulator() {
  // Simulator Shared State
  const state = {
    torch: false,
    mirror: false,
    rotation: 0,
    lens: 'main', // 'ultra-wide' | 'main' | 'telephoto' | 'front'
    resolution: '1920x1080',
    fps: 30,
    codec: 'jpg',
    isStreaming: true,
    battery: 88,
    latencyMs: 12,
  };

  // DOM Elements - Phone
  const phoneCameraFeed = document.getElementById('sim-camera-feed');
  const phoneSubject = document.getElementById('sim-subject');
  const phoneSubjectLabel = document.getElementById('sim-subject-label');
  const phoneTorchBtn = document.getElementById('sim-phone-torch-btn');
  const phoneTorchState = document.getElementById('sim-phone-torch-state');
  const phoneMirrorBtn = document.getElementById('sim-phone-mirror-btn');
  const phoneMirrorState = document.getElementById('sim-phone-mirror-state');
  const phoneResBadge = document.getElementById('phone-res-badge');
  const phoneStreamCodec = document.getElementById('phone-stream-codec');
  const lensButtons = document.querySelectorAll('.lens-btn');

  // DOM Elements - PC Client
  const pcFeedDisplay = document.getElementById('pc-feed-display');
  const pcFeedSubject = document.getElementById('pc-feed-subject');
  const pcStatsFps = document.getElementById('pc-stats-fps');
  const pcStatsRes = document.getElementById('pc-stats-res');
  const pcStatsBitrate = document.getElementById('pc-stats-bitrate');
  const pcResSelect = document.getElementById('pc-res-select');
  const pcFpsSelect = document.getElementById('pc-fps-select');
  const pcCodecSelect = document.getElementById('pc-codec-select');
  const pcTorchBtn = document.getElementById('pc-toggle-torch');
  const pcMirrorBtn = document.getElementById('pc-toggle-mirror');
  const pcRotateBtn = document.getElementById('pc-rotate-feed');
  const pcScreenshotBtn = document.getElementById('pc-take-screenshot');
  const simLatencyReadout = document.getElementById('sim-latency-readout');

  // Helper: Update All Visuals from State
  function renderState() {
    // 1. Torch Visuals
    if (state.torch) {
      phoneTorchState.textContent = 'ON';
      phoneTorchState.style.color = '#f59e0b';
      phoneCameraFeed.style.filter = 'brightness(1.25) contrast(1.1)';
      pcFeedDisplay.style.filter = 'brightness(1.25) contrast(1.1)';
    } else {
      phoneTorchState.textContent = 'OFF';
      phoneTorchState.style.color = 'inherit';
      phoneCameraFeed.style.filter = 'none';
      pcFeedDisplay.style.filter = 'none';
    }

    // 2. Mirror Visuals
    phoneMirrorState.textContent = state.mirror ? 'ON' : 'OFF';
    phoneMirrorState.style.color = state.mirror ? '#06b6d4' : 'inherit';

    // 3. Transform (Zoom Lens + Mirror + Rotation)
    let zoomScale = 1.0;
    let lensName = '1.0x Main Lens';
    if (state.lens === 'ultra-wide') {
      zoomScale = 0.65;
      lensName = '0.6x Ultra-Wide Lens';
    } else if (state.lens === 'telephoto') {
      zoomScale = 1.8;
      lensName = '3.0x Optical Telephoto';
    } else if (state.lens === 'front') {
      zoomScale = 1.05;
      lensName = 'Front-Facing Selfie Cam';
    }

    phoneSubjectLabel.textContent = `Subject (${lensName})`;

    const mirrorScaleX = state.mirror ? -1 : 1;
    phoneSubject.style.transform = `scale(${zoomScale * mirrorScaleX}, ${zoomScale})`;
    
    // PC feed follows rotation + zoom + mirror
    pcFeedDisplay.style.transform = `rotate(${state.rotation}deg)`;
    pcFeedSubject.style.transform = `scale(${zoomScale * mirrorScaleX}, ${zoomScale})`;

    // 4. Resolution & FPS badges
    const [width, height] = state.resolution.split('x');
    const labelRes = height === '2160' ? '4K' : (height === '1080' ? '1080p' : (height === '720' ? '720p' : '480p'));
    phoneResBadge.textContent = `${labelRes} · ${state.fps} FPS`;

    pcStatsFps.textContent = `${state.fps}.0 FPS`;
    pcStatsRes.textContent = `${width}×${height}`;
    
    // Estimated bitrate calculation based on res & fps
    let baseBitrate = (parseInt(width) * parseInt(height) * state.fps) / 8000000;
    if (state.codec === 'hevc') baseBitrate *= 0.6;
    if (state.codec === 'jpg') baseBitrate *= 1.2;
    pcStatsBitrate.textContent = `${baseBitrate.toFixed(1)} Mbps`;

    // Codec name display
    const codecMap = {
      'jpg': 'MJPEG (Direct Frame)',
      'avc': 'H.264 / AVC (Hardware)',
      'hevc': 'H.265 / HEVC (Hardware)'
    };
    phoneStreamCodec.textContent = codecMap[state.codec] || 'MJPEG';

    // Latency readout fluctuation for realistic simulation
    const simulatedLatency = Math.floor(10 + Math.random() * 4);
    if (simLatencyReadout) {
      simLatencyReadout.textContent = `⚡ ${simulatedLatency} ms`;
    }
  }

  // Event: Lens Switcher (Phone Buttons)
  lensButtons.forEach(btn => {
    btn.addEventListener('click', () => {
      lensButtons.forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      state.lens = btn.getAttribute('data-lens');
      renderState();
    });
  });

  // Event: Toggle Torch (from Phone)
  if (phoneTorchBtn) {
    phoneTorchBtn.addEventListener('click', () => {
      state.torch = !state.torch;
      renderState();
    });
  }

  // Event: Toggle Torch (from PC Remote Control)
  if (pcTorchBtn) {
    pcTorchBtn.addEventListener('click', () => {
      state.torch = !state.torch;
      renderState();
    });
  }

  // Event: Toggle Mirror (from Phone)
  if (phoneMirrorBtn) {
    phoneMirrorBtn.addEventListener('click', () => {
      state.mirror = !state.mirror;
      renderState();
    });
  }

  // Event: Toggle Mirror (from PC)
  if (pcMirrorBtn) {
    pcMirrorBtn.addEventListener('click', () => {
      state.mirror = !state.mirror;
      renderState();
    });
  }

  // Event: Rotate Video Stream (from PC)
  if (pcRotateBtn) {
    pcRotateBtn.addEventListener('click', () => {
      state.rotation = (state.rotation + 90) % 360;
      pcRotateBtn.textContent = `🔄 Rotate (${state.rotation}°)`;
      renderState();
    });
  }

  // Event: Resolution Select (from PC)
  if (pcResSelect) {
    pcResSelect.addEventListener('change', (e) => {
      state.resolution = e.target.value;
      renderState();
    });
  }

  // Event: FPS Select (from PC)
  if (pcFpsSelect) {
    pcFpsSelect.addEventListener('change', (e) => {
      state.fps = parseInt(e.target.value, 10);
      renderState();
    });
  }

  // Event: Codec Select (from PC)
  if (pcCodecSelect) {
    pcCodecSelect.addEventListener('change', (e) => {
      state.codec = e.target.value;
      renderState();
    });
  }

  // Event: Take Screenshot (Flash Animation)
  if (pcScreenshotBtn) {
    pcScreenshotBtn.addEventListener('click', () => {
      const container = document.getElementById('pc-preview-container');
      if (!container) return;

      const flashOverlay = document.createElement('div');
      flashOverlay.style.position = 'absolute';
      flashOverlay.style.inset = '0';
      flashOverlay.style.backgroundColor = '#ffffff';
      flashOverlay.style.opacity = '0.8';
      flashOverlay.style.pointerEvents = 'none';
      flashOverlay.style.transition = 'opacity 0.4s ease';
      flashOverlay.style.zIndex = '10';

      container.appendChild(flashOverlay);

      setTimeout(() => {
        flashOverlay.style.opacity = '0';
        setTimeout(() => flashOverlay.remove(), 400);
      }, 50);

      // Brief button text feedback
      const originalText = pcScreenshotBtn.textContent;
      pcScreenshotBtn.textContent = '✅ Saved!';
      setTimeout(() => {
        pcScreenshotBtn.textContent = originalText;
      }, 1500);
    });
  }

  // Focus box tap-to-focus simulation
  const phoneScreen = document.getElementById('sim-viewfinder');
  const focusBox = document.getElementById('sim-focus-box');
  if (phoneScreen && focusBox) {
    phoneScreen.addEventListener('click', (e) => {
      const rect = phoneScreen.getBoundingClientRect();
      const clickX = e.clientX - rect.left - 30;
      const clickY = e.clientY - rect.top - 30;

      focusBox.style.left = `${Math.max(10, Math.min(rect.width - 70, clickX))}px`;
      focusBox.style.top = `${Math.max(10, Math.min(rect.height - 70, clickY))}px`;
    });
  }

  // Initial render
  renderState();
}
