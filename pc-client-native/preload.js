const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('api', {
  connectStream: (config) => ipcRenderer.invoke('connect-stream', config),
  disconnectStream: () => ipcRenderer.invoke('disconnect-stream'),
  getStatus: (ip, port) => ipcRenderer.invoke('get-status', { ip, port }),
  pushSettings: (ip, port, params) => ipcRenderer.invoke('push-settings', { ip, port, params }),
  saveSnapshot: (dataUrl) => ipcRenderer.invoke('save-snapshot', dataUrl),
  scanDevices: (port) => ipcRenderer.invoke('scan-devices', port),
  toggleAlwaysOnTop: () => ipcRenderer.invoke('toggle-always-on-top'),
  registerVcam: () => ipcRenderer.invoke('register-vcam'),
  unregisterVcam: () => ipcRenderer.invoke('unregister-vcam'),
  getVcamStatus: () => ipcRenderer.invoke('get-vcam-status'),
  onVideoFrame: (callback) => {
    ipcRenderer.removeAllListeners('video-frame');
    ipcRenderer.on('video-frame', (_event, buffer) => callback(buffer));
  },
  onStreamStatus: (callback) => {
    ipcRenderer.removeAllListeners('stream-status');
    ipcRenderer.on('stream-status', (_event, data) => callback(data));
  },
});
