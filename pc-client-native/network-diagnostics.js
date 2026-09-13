'use strict';

/**
 * Diagnostic guidance for connection errors between PC client and Android phone.
 */
function formatDiagnosticMessage(code, ip, port, defaultMsg) {
  const isLoopback = ip === '127.0.0.1' || String(ip).toLowerCase() === 'localhost';
  if (code === 'ECONNREFUSED') {
    let msg = `Could not connect to ${ip}:${port}. Make sure the OpenCam app is open on your phone and you have pressed the START button.`;
    if (isLoopback) {
      msg += ' For USB connection, ensure you ran: adb forward tcp:4747 tcp:4747';
    }
    return msg;
  }
  if (code === 'ETIMEDOUT' || code === 'EHOSTUNREACH') {
    let msg = `Cannot reach phone at ${ip}. Ensure PC and phone are on the same Wi-Fi network (disable AP isolation), or connect using phone Mobile Hotspot or USB cable.`;
    if (isLoopback) {
      msg += ' For USB connection, run: adb forward tcp:4747 tcp:4747';
    }
    return msg;
  }
  let msg = defaultMsg || `Connection error (${code || 'unknown'})`;
  if (isLoopback) {
    msg += ' (For USB connection, run: adb forward tcp:4747 tcp:4747)';
  }
  return msg;
}

/**
 * Adapter priority ranking:
 * 0: Wi-Fi / WLAN physical adapters
 * 1: Physical Ethernet / LAN adapters
 * 2: Other unidentified adapters
 * 3: Virtual adapters, tunnels, and containers (WSL, Docker, VirtualBox, etc.)
 */
const getAdapterPriority = (name) => {
  const lower = name.toLowerCase();
  if (
    lower.includes('vethernet') ||
    lower.includes('wsl') ||
    lower.includes('virtualbox') ||
    lower.includes('vmware') ||
    lower.includes('docker') ||
    lower.includes('hyper-v') ||
    lower.includes('tap') ||
    lower.includes('tun') ||
    lower.includes('tailscale') ||
    lower.includes('loopback')
  ) {
    return 3;
  }
  if (lower.includes('wi-fi') || lower.includes('wlan') || lower.includes('wireless')) return 0;
  if (lower.includes('ethernet') || lower.startsWith('eth') || lower.startsWith('en')) return 1;
  return 2;
};

const ipv4ToInt = (value) => value.split('.').reduce((n, octet) => (n << 8) | Number(octet), 0) >>> 0;
const intToIp = (value) => [24, 16, 8, 0].map((shift) => (value >>> shift) & 255).join('.');

/**
 * Computes network ranges for device discovery across network interfaces.
 * Prioritizes physical Wi-Fi/Ethernet adapters.
 * For large subnets (> 1022 hosts, e.g. /16 or /8), scans host PC's immediate /24 neighborhood.
 */
function computeSubnetRanges(interfaces) {
  const ifaceEntries = Object.entries(interfaces || {}).sort(([nameA], [nameB]) => {
    return getAdapterPriority(nameA) - getAdapterPriority(nameB);
  });

  const ranges = [];
  const addedRanges = new Set();

  for (const [name, entries] of ifaceEntries) {
    for (const iface of entries || []) {
      if (iface.family !== 'IPv4' || iface.internal || !iface.netmask) continue;
      const mask = ipv4ToInt(iface.netmask);
      const address = ipv4ToInt(iface.address);
      const network = (address & mask) >>> 0;
      const broadcast = (network | (~mask >>> 0)) >>> 0;
      let first = network + 1;
      let last = broadcast - 1;

      // For large subnets (> 1022 hosts, e.g. /16 or /8), scan host PC's immediate /24 neighborhood
      if (last - first > 1022) {
        const cBlockBase = (address & 0xffffff00) >>> 0;
        first = cBlockBase + 1;
        last = cBlockBase + 254;
      }

      if (last >= first) {
        const key = `${first}-${last}`;
        if (!addedRanges.has(key)) {
          addedRanges.add(key);
          ranges.push({ first, last, name });
        }
      }
    }
  }

  return ranges;
}

function isValidIp(ip) {
  const isLocalhost = String(ip).toLowerCase() === 'localhost';
  const isIpv4 = /^(?:\d{1,3}\.){3}\d{1,3}$/.test(ip) && ip.split('.').every((v) => Number(v) >= 0 && Number(v) <= 255);
  return isLocalhost || isIpv4;
}

function normalizeHost(ip) {
  if (String(ip).toLowerCase() === 'localhost') return '127.0.0.1';
  return ip;
}

module.exports = {
  formatDiagnosticMessage,
  getAdapterPriority,
  computeSubnetRanges,
  isValidIp,
  normalizeHost,
  ipv4ToInt,
  intToIp,
};
