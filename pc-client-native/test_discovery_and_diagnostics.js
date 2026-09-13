// Unit tests for PC Client Network Discovery, IP validation, and Diagnostic error messages.
'use strict';

const {
  formatDiagnosticMessage,
  getAdapterPriority,
  computeSubnetRanges,
  isValidIp,
  normalizeHost,
  ipv4ToInt,
  intToIp,
} = require('./network-diagnostics');

let passed = 0;
let failed = 0;

function check(name, cond, extra) {
  if (cond) {
    passed++;
    console.log(`PASS ${name}`);
  } else {
    failed++;
    console.log(`FAIL ${name}${extra ? ' — ' + extra : ''}`);
  }
}

// 1. IP Validation & Normalization logic
check('IP validation accepts localhost', isValidIp('localhost') === true);
check('IP validation accepts LOCALHOST case-insensitively', isValidIp('LOCALHOST') === true);
check('IP validation accepts 127.0.0.1', isValidIp('127.0.0.1') === true);
check('IP validation accepts 192.168.1.42', isValidIp('192.168.1.42') === true);
check('IP validation accepts 10.0.0.1', isValidIp('10.0.0.1') === true);
check('IP validation rejects empty string', isValidIp('') === false);
check('IP validation rejects 256.1.1.1', isValidIp('256.1.1.1') === false);
check('IP validation rejects random text', isValidIp('invalid-host') === false);

check('normalizeHost converts localhost to 127.0.0.1', normalizeHost('localhost') === '127.0.0.1');
check('normalizeHost converts LOCALHOST to 127.0.0.1', normalizeHost('LOCALHOST') === '127.0.0.1');
check('normalizeHost preserves 192.168.1.50', normalizeHost('192.168.1.50') === '192.168.1.50');

// 2. Diagnostic message formatting
const refusedMsg = formatDiagnosticMessage('ECONNREFUSED', '192.168.1.50', 4747);
check('ECONNREFUSED mentions START button', refusedMsg.includes('Make sure the OpenCam app is open on your phone and you have pressed the START button'));

const refusedUsb = formatDiagnosticMessage('ECONNREFUSED', '127.0.0.1', 4747);
check('ECONNREFUSED on 127.0.0.1 mentions adb forward', refusedUsb.includes('adb forward tcp:4747 tcp:4747'));

const refusedLocalhost = formatDiagnosticMessage('ECONNREFUSED', 'localhost', 4747);
check('ECONNREFUSED on localhost mentions adb forward', refusedLocalhost.includes('adb forward tcp:4747 tcp:4747'));

const timeoutMsg = formatDiagnosticMessage('ETIMEDOUT', '192.168.1.50', 4747);
check('ETIMEDOUT mentions same Wi-Fi, AP isolation, Mobile Hotspot or USB cable', timeoutMsg.includes('Ensure PC and phone are on the same Wi-Fi') && timeoutMsg.includes('AP isolation'));

const unreachMsg = formatDiagnosticMessage('EHOSTUNREACH', '10.0.0.5', 4747);
check('EHOSTUNREACH diagnostic guidance present', unreachMsg.includes('Cannot reach phone at 10.0.0.5'));

// 3. Adapter ranking logic
check('Wi-Fi adapter has rank 0', getAdapterPriority('Wi-Fi') === 0);
check('WLAN adapter has rank 0', getAdapterPriority('wlan0') === 0);
check('Ethernet adapter has rank 1', getAdapterPriority('Ethernet 2') === 1);
check('WSL vEthernet adapter has rank 3', getAdapterPriority('vEthernet (WSL)') === 3);
check('VirtualBox adapter has rank 3', getAdapterPriority('VirtualBox Host-Only Ethernet Adapter') === 3);
check('Docker adapter has rank 3', getAdapterPriority('docker0') === 3);
check('Tailscale adapter has rank 3', getAdapterPriority('Tailscale') === 3);

// 4. Large subnet neighborhood scanning logic via computeSubnetRanges
const mockInterfaces = {
  'vEthernet (WSL)': [
    { family: 'IPv4', internal: false, address: '172.28.16.1', netmask: '255.255.240.0' },
  ],
  'Wi-Fi': [
    { family: 'IPv4', internal: false, address: '192.168.1.50', netmask: '255.255.255.0' },
  ],
  'Corporate 10.x LAN': [
    { family: 'IPv4', internal: false, address: '10.50.20.10', netmask: '255.255.0.0' },
  ],
};

const computedRanges = computeSubnetRanges(mockInterfaces);
check('computeSubnetRanges ranks Wi-Fi first', computedRanges.length >= 3 && computedRanges[0].name === 'Wi-Fi');

// Wi-Fi range should be 192.168.1.1 to 192.168.1.254 (254 hosts)
const wifiRange = computedRanges.find((r) => r.name === 'Wi-Fi');
check('Wi-Fi produces correct /24 range', wifiRange && intToIp(wifiRange.first) === '192.168.1.1' && intToIp(wifiRange.last) === '192.168.1.254');

// /16 range should scan host /24 neighborhood
const corpRange = computedRanges.find((r) => r.name === 'Corporate 10.x LAN');
check('/16 subnet is NOT skipped and scans host /24 neighborhood', corpRange && intToIp(corpRange.first) === '10.50.20.1' && intToIp(corpRange.last) === '10.50.20.254');

// WSL vEthernet should be ranked last
const wslRangeIdx = computedRanges.findIndex((r) => r.name === 'vEthernet (WSL)');
check('WSL virtual adapter ranked after physical Wi-Fi and Ethernet', wslRangeIdx > 0);

console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed === 0 ? 0 : 1);
