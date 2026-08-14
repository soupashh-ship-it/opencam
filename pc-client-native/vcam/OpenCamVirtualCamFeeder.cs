using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using Microsoft.Win32;

namespace OpenCam.VirtualCamera
{
    [StructLayout(LayoutKind.Sequential, Pack = 8)]
    public struct QueueHeader
    {
        public volatile uint write_idx; // 0x00: 0, 1, 2
        public volatile uint read_idx;  // 0x04: 0, 1, 2
        public volatile uint state;     // 0x08: 0 = stopped, 1 = running/active
        public uint offset0;            // 0x0C: byte offset to buffer 0
        public uint offset1;            // 0x10: byte offset to buffer 1
        public uint offset2;            // 0x14: byte offset to buffer 2
        public uint type;               // 0x18: 2 = NV12, 4 = YUY2, 7 = BGRA
        public uint cx;                 // 0x1C: width
        public uint cy;                 // 0x20: height
        public uint pad;                // 0x24: explicit alignment padding (4 bytes)
        public ulong interval;          // 0x28: 100ns units (e.g. 333333 for 30fps) - matches 64-bit and 32-bit DLL offset
        public uint res0;               // 0x30
        public uint res1;
        public uint res2;
        public uint res3;
        public uint res4;
        public uint res5;
        public uint res6;
        public uint res7;
    }

    public class Feeder
    {
        private const int HEADER_SIZE = 128;
        private const uint VIDEO_FORMAT_NV12 = 2;
        private const uint VIDEO_FORMAT_BGRA = 7;

        // Security descriptor: Full access for Everyone (WD), All Application Packages (AC),
        // Restricted Application Packages (RC), Authenticated Users (AU), plus Low Mandatory Level (LW)
        // for sandboxed UWP, Windows Camera, WhatsApp Desktop, and WebRTC browsers.
        private const string SDDL_ALL_ACCESS = "D:(A;;GA;;;WD)(A;;GA;;;AC)(A;;GA;;;RC)(A;;GA;;;AU)S:(ML;;NW;;;LW)";

        public const string DSHOW_VIDEO_INPUT_CATEGORY = "{860BB310-5D01-11d0-BD3B-00A0C911CE86}";
        public const string OPENCAM_INSTANCE_GUID = "{A7D3E5B1-8C2F-4D9A-901B-2C3D4E5F6A7B}";
        public const string OBS_FILTER_CLSID = "{A3FCE0F5-3493-419F-958A-ABA1250EC20B}";
        public const string MF_TRANSFORM_CATEGORY_CAPTURE = "{49438d24-f6f2-4ec6-8a59-3428f738d7fe}";
        public const string MF_TRANSFORM_CATEGORY_PROCESSOR = "{f79eac7d-e545-4387-bdee-d647d7bde42a}";
        public const string KSCATEGORY_CAPTURE_GUID = "{e5323777-ec62-4a8b-864b-0e5407163e58}";
        public const string KSCATEGORY_VIDEO_GUID = "{65e8773d-8f56-11d0-a3b9-00a0c9223196}";
        public const string KSCATEGORY_SENSOR_CAMERA_GUID = "{24e552d7-6523-47f7-a647-d3465bf1f5ca}";

        [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Auto)]
        private static extern bool ConvertStringSecurityDescriptorToSecurityDescriptor(
            string StringSecurityDescriptor,
            uint StringSDRevision,
            out IntPtr SecurityDescriptor,
            out UIntPtr SecurityDescriptorSize);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern IntPtr LocalFree(IntPtr hMem);

        [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        private static extern IntPtr CreateFileMapping(
            IntPtr hFile,
            IntPtr lpFileMappingAttributes,
            uint flProtect,
            uint dwMaximumSizeHigh,
            uint dwMaximumSizeLow,
            string lpName);

        [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        private static extern IntPtr OpenFileMapping(
            uint dwDesiredAccess,
            bool bInheritHandle,
            string lpName);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern IntPtr MapViewOfFile(
            IntPtr hFileMappingObject,
            uint dwDesiredAccess,
            uint dwFileOffsetHigh,
            uint dwFileOffsetLow,
            UIntPtr dwNumberOfBytesToMap);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool UnmapViewOfFile(IntPtr lpBaseAddress);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool CloseHandle(IntPtr hObject);

        private const uint PAGE_READWRITE = 0x04;
        private const uint FILE_MAP_ALL_ACCESS = 0xF001F;
        private static readonly IntPtr INVALID_HANDLE_VALUE = new IntPtr(-1);

        [StructLayout(LayoutKind.Sequential)]
        private struct SECURITY_ATTRIBUTES
        {
            public int nLength;
            public IntPtr lpSecurityDescriptor;
            public bool bInheritHandle;
        }

        private class MappedBuffer : IDisposable
        {
            public IntPtr Handle;
            public IntPtr View;
            public int TotalSize;
            public string Name;

            public void Dispose()
            {
                if (View != IntPtr.Zero)
                {
                    try
                    {
                        // Signal stopped state (state = 0) before unmapping
                        Marshal.WriteInt32(View, 8, 0);
                    }
                    catch { }
                    UnmapViewOfFile(View);
                    View = IntPtr.Zero;
                }
                if (Handle != IntPtr.Zero && Handle != INVALID_HANDLE_VALUE)
                {
                    CloseHandle(Handle);
                    Handle = IntPtr.Zero;
                }
            }
        }

        private static List<MappedBuffer> _mappedBuffers = new List<MappedBuffer>();
        private static int _currentWidth = 1920;
        private static int _currentHeight = 1080;
        private static uint _currentFormat = VIDEO_FORMAT_NV12;
        private static int _frameSize = 0;
        private static int _totalBufferSize = 0;
        private static uint _writeIndex = 0;
        private static int _currentFps = 30;
        private static byte[] _nv12Buffer = null;

        // Preallocate mapping for up to 4K (3840x2160 NV12 = ~12.5MB per frame * 3 = ~38MB)
        // so resolution switches (720p, 1080p, 1440p, 4K) do not fail when consumer apps hold section handles.
        private const int MAX_WIDTH = 3840;
        private const int MAX_HEIGHT = 2160;
        private static readonly int MAX_FRAME_SIZE = (MAX_WIDTH * MAX_HEIGHT * 3) / 2;
        private static readonly int MAX_TOTAL_SIZE = ((HEADER_SIZE + 31) & ~31) + (3 * ((MAX_FRAME_SIZE + 31) & ~31));

        public static void CleanupSharedMemory()
        {
            foreach (var mb in _mappedBuffers)
            {
                try { mb.Dispose(); } catch { }
            }
            _mappedBuffers.Clear();
        }

        private static void InitSharedMemory(int width, int height, uint format, int fps)
        {
            // Sanitize dimensions to even numbers and clamp within preallocated bounds
            width = Math.Max(320, Math.Min(MAX_WIDTH, width & ~1));
            height = Math.Max(240, Math.Min(MAX_HEIGHT, height & ~1));
            fps = (fps > 0 && fps <= 120) ? fps : 30;

            _currentWidth = width;
            _currentHeight = height;
            _currentFormat = format;
            _currentFps = fps;

            if (format == VIDEO_FORMAT_NV12)
            {
                _frameSize = (width * height * 3) / 2;
            }
            else
            {
                _frameSize = width * height * 4;
            }

            if (_nv12Buffer == null || _nv12Buffer.Length < _frameSize)
            {
                _nv12Buffer = new byte[_frameSize];
            }

            int alignedHeader = (HEADER_SIZE + 31) & ~31;
            int alignedFrame = (_frameSize + 31) & ~31;
            _totalBufferSize = Math.Max(alignedHeader + (3 * alignedFrame), MAX_TOTAL_SIZE);
            ulong interval = (fps > 0) ? (10000000UL / (ulong)fps) : 333333UL;

            // If mappings already exist and are valid, update the QueueHeaders directly without re-creating handles
            if (_mappedBuffers.Count > 0)
            {
                foreach (var mb in _mappedBuffers)
                {
                    if (mb.View != IntPtr.Zero)
                    {
                        QueueHeader header = new QueueHeader
                        {
                            write_idx = 0,
                            read_idx = 0,
                            state = 1,
                            offset0 = (uint)alignedHeader,
                            offset1 = (uint)(alignedHeader + alignedFrame),
                            offset2 = (uint)(alignedHeader + 2 * alignedFrame),
                            type = _currentFormat,
                            cx = (uint)width,
                            cy = (uint)height,
                            pad = 0,
                            interval = interval
                        };
                        Marshal.StructureToPtr(header, mb.View, false);
                    }
                }
                _writeIndex = 0;
                return;
            }

            // Create security attributes for AppContainer / All users access
            IntPtr pSD = IntPtr.Zero;
            UIntPtr sdSize;
            ConvertStringSecurityDescriptorToSecurityDescriptor(SDDL_ALL_ACCESS, 1, out pSD, out sdSize);

            SECURITY_ATTRIBUTES sa = new SECURITY_ATTRIBUTES();
            sa.nLength = Marshal.SizeOf(typeof(SECURITY_ATTRIBUTES));
            sa.lpSecurityDescriptor = pSD;
            sa.bInheritHandle = false;

            IntPtr pSa = Marshal.AllocHGlobal(sa.nLength);
            Marshal.StructureToPtr(sa, pSa, false);

            // Create distinct mappings for OBS and OpenCam consumers without duplicate aliases
            string[] baseNames = new string[]
            {
                "OBSVirtualCamVideo",
                "OBSVirtualCamVideo0",
                "OpenCamVirtualCamVideo"
            };

            foreach (var baseName in baseNames)
            {
                IntPtr hMap = IntPtr.Zero;

                // Try Global namespace first for cross-session/UWP compatibility, fall back to Local
                try
                {
                    hMap = CreateFileMapping(INVALID_HANDLE_VALUE, pSa, PAGE_READWRITE, 0, (uint)_totalBufferSize, @"Global\" + baseName);
                }
                catch { }

                if (hMap == IntPtr.Zero || hMap == INVALID_HANDLE_VALUE)
                {
                    try
                    {
                        hMap = CreateFileMapping(INVALID_HANDLE_VALUE, pSa, PAGE_READWRITE, 0, (uint)_totalBufferSize, baseName);
                    }
                    catch { }
                }

                if (hMap != IntPtr.Zero && hMap != INVALID_HANDLE_VALUE)
                {
                    // Pass UIntPtr.Zero to map the full section safely even if created by external process
                    IntPtr view = MapViewOfFile(hMap, FILE_MAP_ALL_ACCESS, 0, 0, UIntPtr.Zero);
                    if (view != IntPtr.Zero)
                    {
                        var mb = new MappedBuffer { Handle = hMap, View = view, TotalSize = _totalBufferSize, Name = baseName };
                        _mappedBuffers.Add(mb);

                        // Initialize QueueHeader
                        QueueHeader header = new QueueHeader
                        {
                            write_idx = 0,
                            read_idx = 0,
                            state = 1,
                            offset0 = (uint)alignedHeader,
                            offset1 = (uint)(alignedHeader + alignedFrame),
                            offset2 = (uint)(alignedHeader + 2 * alignedFrame),
                            type = _currentFormat,
                            cx = (uint)width,
                            cy = (uint)height,
                            pad = 0,
                            interval = interval
                        };

                        Marshal.StructureToPtr(header, view, false);
                    }
                }
            }

            Marshal.FreeHGlobal(pSa);
            if (pSD != IntPtr.Zero) LocalFree(pSD);
            _writeIndex = 0;
        }

        public static void WriteStandbyToAllBuffers(byte[] pixelData)
        {
            if (_mappedBuffers.Count == 0 || pixelData == null || pixelData.Length == 0) return;
            int alignedHeader = (HEADER_SIZE + 31) & ~31;
            int alignedFrame = (_frameSize + 31) & ~31;
            int copyLen = Math.Min(pixelData.Length, _frameSize);

            foreach (var mb in _mappedBuffers)
            {
                if (mb.View == IntPtr.Zero) continue;
                for (int b = 0; b < 3; b++)
                {
                    IntPtr dst = new IntPtr(mb.View.ToInt64() + alignedHeader + (b * alignedFrame));
                    Marshal.Copy(pixelData, 0, dst, copyLen);
                }
                Marshal.WriteInt32(mb.View, 0, 0); // write_idx = 0
                Marshal.WriteInt32(mb.View, 8, 1); // state = 1
            }
            _writeIndex = 0;
        }

        public static void WriteFrameToSharedMemory(byte[] pixelData)
        {
            if (_mappedBuffers.Count == 0 || pixelData == null || pixelData.Length == 0) return;

            uint nextIndex = (_writeIndex + 1) % 3;
            int alignedHeader = (HEADER_SIZE + 31) & ~31;
            int alignedFrame = (_frameSize + 31) & ~31;
            int targetOffset = alignedHeader + (int)(nextIndex * alignedFrame);
            int copyLen = Math.Min(pixelData.Length, _frameSize);

            foreach (var mb in _mappedBuffers)
            {
                if (mb.View == IntPtr.Zero) continue;
                IntPtr dst = new IntPtr(mb.View.ToInt64() + targetOffset);
                Marshal.Copy(pixelData, 0, dst, copyLen);

                // Update write_idx and state in header atomically
                Marshal.WriteInt32(mb.View, 0, (int)nextIndex); // write_idx at offset 0
                Marshal.WriteInt32(mb.View, 8, 1);               // state at offset 8 (1 = running)
            }

            _writeIndex = nextIndex;
        }

        public static void ConvertBmpToNv12InPlace(Bitmap bmp, int targetW, int targetH, byte[] outNv12)
        {
            int w = Math.Max(320, Math.Min(MAX_WIDTH, targetW & ~1));
            int h = Math.Max(240, Math.Min(MAX_HEIGHT, targetH & ~1));

            Bitmap scaled = bmp;
            bool disposeScaled = false;
            if (bmp.Width != w || bmp.Height != h)
            {
                scaled = new Bitmap(w, h, PixelFormat.Format32bppRgb);
                using (Graphics g = Graphics.FromImage(scaled))
                {
                    g.InterpolationMode = InterpolationMode.Bilinear;
                    g.DrawImage(bmp, 0, 0, w, h);
                }
                disposeScaled = true;
            }

            BitmapData data = scaled.LockBits(new Rectangle(0, 0, w, h), ImageLockMode.ReadOnly, PixelFormat.Format32bppRgb);
            try
            {
                unsafe
                {
                    byte* rgbPtr = (byte*)data.Scan0.ToPointer();
                    fixed (byte* pNv12 = outNv12)
                    {
                        byte* yPlane = pNv12;
                        byte* uvPlane = pNv12 + (w * h);
                        int stride = data.Stride;

                        for (int y = 0; y < h; y++)
                        {
                            byte* row = rgbPtr + (y * stride);
                            byte* yRow = yPlane + (y * w);
                            for (int x = 0; x < w; x++)
                            {
                                int b = row[x * 4];
                                int g = row[x * 4 + 1];
                                int r = row[x * 4 + 2];

                                // ITU-R BT.601 Studio Range (Y in [16..235], U,V in [16..240])
                                int yVal = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                                yRow[x] = (byte)(yVal < 16 ? 16 : (yVal > 235 ? 235 : yVal));

                                if ((x & 1) == 0 && (y & 1) == 0)
                                {
                                    int uvIdx = (y / 2) * w + x;
                                    int uVal = (((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128);
                                    int vVal = (((112 * r - 94 * g - 18 * b + 128) >> 8) + 128);
                                    uvPlane[uvIdx] = (byte)(uVal < 16 ? 16 : (uVal > 240 ? 240 : uVal));
                                    uvPlane[uvIdx + 1] = (byte)(vVal < 16 ? 16 : (vVal > 240 ? 240 : vVal));
                                }
                            }
                        }
                    }
                }
            }
            finally
            {
                scaled.UnlockBits(data);
                if (disposeScaled) scaled.Dispose();
            }
        }

        public static byte[] GenerateStandbyCard(int w, int h, string message)
        {
            w = Math.Max(320, w & ~1);
            h = Math.Max(240, h & ~1);

            using (Bitmap bmp = new Bitmap(w, h, PixelFormat.Format32bppRgb))
            {
                using (Graphics g = Graphics.FromImage(bmp))
                {
                    g.SmoothingMode = SmoothingMode.AntiAlias;
                    g.TextRenderingHint = System.Drawing.Text.TextRenderingHint.ClearTypeGridFit;

                    // Dark gradient background
                    using (LinearGradientBrush brush = new LinearGradientBrush(new Point(0, 0), new Point(0, h), Color.FromArgb(12, 15, 23), Color.FromArgb(20, 26, 38)))
                    {
                        g.FillRectangle(brush, 0, 0, w, h);
                    }

                    // Cyan accent rounded card
                    int cardW = (int)(w * 0.65f);
                    int cardH = (int)(h * 0.45f);
                    int cardX = (w - cardW) / 2;
                    int cardY = (h - cardH) / 2;

                    using (Pen pen = new Pen(Color.FromArgb(56, 189, 248), 3))
                    using (SolidBrush cardBg = new SolidBrush(Color.FromArgb(180, 18, 24, 38)))
                    {
                        using (GraphicsPath path = GetRoundedPath(cardX, cardY, cardW, cardH, 20))
                        {
                            g.FillPath(cardBg, path);
                            g.DrawPath(pen, path);
                        }
                    }

                    // Text
                    int titleSize = Math.Max(16, w / 40);
                    int subSize = Math.Max(12, w / 65);
                    using (Font titleFont = new Font("Segoe UI", titleSize, FontStyle.Bold))
                    using (Font subFont = new Font("Segoe UI", subSize, FontStyle.Regular))
                    using (SolidBrush textBrush = new SolidBrush(Color.White))
                    using (SolidBrush cyanBrush = new SolidBrush(Color.FromArgb(56, 189, 248)))
                    using (SolidBrush subBrush = new SolidBrush(Color.FromArgb(156, 163, 175)))
                    {
                        StringFormat sf = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center };
                        g.DrawString("OpenCam Virtual Camera", titleFont, cyanBrush, new RectangleF(0, cardY + cardH * 0.15f, w, cardH * 0.25f), sf);
                        g.DrawString(message ?? "Ready — Waiting for OpenCam Connection", subFont, textBrush, new RectangleF(0, cardY + cardH * 0.45f, w, cardH * 0.22f), sf);
                        g.DrawString("Connect OpenCam Studio on PC or launch OpenCam App on Android", subFont, subBrush, new RectangleF(0, cardY + cardH * 0.70f, w, cardH * 0.20f), sf);
                    }
                }

                byte[] outBuf = new byte[(w * h * 3) / 2];
                ConvertBmpToNv12InPlace(bmp, w, h, outBuf);
                return outBuf;
            }
        }

        private static GraphicsPath GetRoundedPath(float x, float y, float width, float height, float radius)
        {
            GraphicsPath path = new GraphicsPath();
            path.AddArc(x, y, radius * 2, radius * 2, 180, 90);
            path.AddArc(x + width - radius * 2, y, radius * 2, radius * 2, 270, 90);
            path.AddArc(x + width - radius * 2, y + height - radius * 2, radius * 2, radius * 2, 0, 90);
            path.AddArc(x, y + height - radius * 2, radius * 2, radius * 2, 90, 90);
            path.CloseFigure();
            return path;
        }

        public static void RunFeeder(int initialWidth, int initialHeight, int fps)
        {
            try
            {
                InitSharedMemory(initialWidth, initialHeight, VIDEO_FORMAT_NV12, fps);

                // Feed initial standby frame into all 3 buffers so any consumer gets a crisp card immediately
                byte[] standby = GenerateStandbyCard(initialWidth, initialHeight, "Ready — Connect OpenCam on your phone");
                WriteStandbyToAllBuffers(standby);

                Stream stdin = Console.OpenStandardInput();
                byte[] headerBuf = new byte[12];
                int headerRead = 0;
                byte[] payloadBuffer = new byte[1024 * 1024]; // Reusable 1MB initial frame buffer

                while (true)
                {
                    try
                    {
                        // Read 12-byte wire header (8 bytes PTS + 4 bytes Length)
                        while (headerRead < 12)
                        {
                            int r = stdin.Read(headerBuf, headerRead, 12 - headerRead);
                            if (r <= 0) return; // Stdin closed
                            headerRead += r;
                        }

                        int payloadLength = (headerBuf[8] << 24) | (headerBuf[9] << 16) | (headerBuf[10] << 8) | headerBuf[11];

                        // Sanity check: valid frame payload length between 64 bytes and 25 MB
                        if (payloadLength < 64 || payloadLength > 25 * 1024 * 1024)
                        {
                            // Shift left by 1 byte to resync on wire boundary
                            Buffer.BlockCopy(headerBuf, 1, headerBuf, 0, 11);
                            headerRead = 11;
                            continue;
                        }

                        if (payloadBuffer.Length < payloadLength)
                        {
                            payloadBuffer = new byte[Math.Max(payloadLength, payloadBuffer.Length * 2)];
                        }

                        int payloadRead = 0;
                        while (payloadRead < payloadLength)
                        {
                            int r = stdin.Read(payloadBuffer, payloadRead, payloadLength - payloadRead);
                            if (r <= 0) return;
                            payloadRead += r;
                        }

                        // Next cycle will read a full 12-byte header again
                        headerRead = 0;

                        // Decode JPEG payload (SOI marker: 0xFF 0xD8)
                        if (payloadLength >= 4 && payloadBuffer[0] == 0xFF && payloadBuffer[1] == 0xD8)
                        {
                            using (MemoryStream ms = new MemoryStream(payloadBuffer, 0, payloadLength, false, false))
                            using (Bitmap bmp = (Bitmap)Image.FromStream(ms))
                            {
                                int sw = Math.Max(320, Math.Min(MAX_WIDTH, bmp.Width & ~1));
                                int sh = Math.Max(240, Math.Min(MAX_HEIGHT, bmp.Height & ~1));
                                if (sw != _currentWidth || sh != _currentHeight)
                                {
                                    InitSharedMemory(sw, sh, _currentFormat, fps);
                                }
                                ConvertBmpToNv12InPlace(bmp, _currentWidth, _currentHeight, _nv12Buffer);
                                WriteFrameToSharedMemory(_nv12Buffer);
                            }
                        }
                    }
                    catch (ThreadAbortException)
                    {
                        break;
                    }
                    catch (Exception)
                    {
                        // Frame decode blip — safely continue
                    }
                }
            }
            finally
            {
                CleanupSharedMemory();
            }
        }

        public static bool RegisterVirtualCamera(string dllDir)
        {
            try
            {
                if (string.IsNullOrEmpty(dllDir))
                {
                    dllDir = AppDomain.CurrentDomain.BaseDirectory;
                }
                else
                {
                    dllDir = dllDir.Trim('"', '\'', ' ', '\t', '\r', '\n').TrimEnd('\\', '/');
                    if (string.IsNullOrEmpty(dllDir))
                    {
                        dllDir = AppDomain.CurrentDomain.BaseDirectory;
                    }
                }

                string dll64 = Path.Combine(dllDir, "obs-virtualcam-module64.dll");
                string dll32 = Path.Combine(dllDir, "obs-virtualcam-module32.dll");

                // Fallback to Program Files OBS directory if not bundled
                if (!File.Exists(dll64))
                {
                    string obs64 = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), @"obs-studio\data\obs-plugins\win-dshow\obs-virtualcam-module64.dll");
                    if (File.Exists(obs64)) dll64 = obs64;
                }
                if (!File.Exists(dll32))
                {
                    string obs32 = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), @"obs-studio\data\obs-plugins\win-dshow\obs-virtualcam-module32.dll");
                    if (File.Exists(obs32)) dll32 = obs32;
                }

                // 1. Register DLLs via regsvr32 using correct bitness
                if (File.Exists(dll64))
                {
                    try
                    {
                        string regsvr64 = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System), "regsvr32.exe");
                        Process p64 = Process.Start(new ProcessStartInfo(regsvr64, string.Format("/s \"{0}\"", dll64)) { CreateNoWindow = true, UseShellExecute = false });
                        if (p64 != null) p64.WaitForExit(5000);
                    }
                    catch { }
                }

                if (File.Exists(dll32))
                {
                    try
                    {
                        string sysWow64 = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Windows), "SysWOW64");
                        string regsvr32Path = Directory.Exists(sysWow64)
                            ? Path.Combine(sysWow64, "regsvr32.exe")
                            : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System), "regsvr32.exe");

                        Process p32 = Process.Start(new ProcessStartInfo(regsvr32Path, string.Format("/s \"{0}\"", dll32)) { CreateNoWindow = true, UseShellExecute = false });
                        if (p32 != null) p32.WaitForExit(5000);
                    }
                    catch { }
                }

                // Exact DirectShow REGFILTER2 binary data for NV12 video capture pin
                byte[] filterData = new byte[] {
                    0x02,0x00,0x00,0x00,0x00,0x00,0x20,0x00,0x01,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
                    0x30,0x70,0x69,0x33,0x08,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x01,0x00,0x00,0x00,
                    0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x30,0x74,0x79,0x33,0x00,0x00,0x00,0x00,
                    0x38,0x00,0x00,0x00,0x48,0x00,0x00,0x00,0x76,0x69,0x64,0x73,0x00,0x00,0x10,0x00,
                    0x80,0x00,0x00,0xaa,0x00,0x38,0x9b,0x71,0x4e,0x56,0x31,0x32,0x00,0x00,0x10,0x00,
                    0x80,0x00,0x00,0xaa,0x00,0x38,0x9b,0x71
                };

                // 2. Register in DirectShow categories across HKLM, WOW6432Node, and HKCU
                RegistryKey[] roots = new RegistryKey[] { Registry.LocalMachine, Registry.CurrentUser };
                foreach (var root in roots)
                {
                    try
                    {
                        // DirectShow Video Input Category instances ({860BB310-5D01-11d0-BD3B-00A0C911CE86})
                        string[] instanceGuids = new string[] { OPENCAM_INSTANCE_GUID, OBS_FILTER_CLSID };
                        foreach (var instGuid in instanceGuids)
                        {
                            using (var catKey = root.CreateSubKey(string.Format(@"SOFTWARE\Classes\CLSID\{0}\Instance\{1}", DSHOW_VIDEO_INPUT_CATEGORY, instGuid)))
                            {
                                if (catKey != null)
                                {
                                    catKey.SetValue("FriendlyName", "OpenCam Virtual Camera", RegistryValueKind.String);
                                    catKey.SetValue("CLSID", OBS_FILTER_CLSID, RegistryValueKind.String);
                                    catKey.SetValue("FilterData", filterData, RegistryValueKind.Binary);
                                }
                            }
                        }

                        // WOW6432Node for 32-bit DirectShow applications in both HKLM and HKCU
                        foreach (var instGuid in instanceGuids)
                        {
                            string[] wowDshowPaths = new string[]
                            {
                                string.Format(@"SOFTWARE\WOW6432Node\Classes\CLSID\{0}\Instance\{1}", DSHOW_VIDEO_INPUT_CATEGORY, instGuid),
                                string.Format(@"SOFTWARE\Classes\WOW6432Node\CLSID\{0}\Instance\{1}", DSHOW_VIDEO_INPUT_CATEGORY, instGuid)
                            };
                            foreach (var p in wowDshowPaths)
                            {
                                using (var wowKey = root.CreateSubKey(p))
                                {
                                    if (wowKey != null)
                                    {
                                        wowKey.SetValue("FriendlyName", "OpenCam Virtual Camera", RegistryValueKind.String);
                                        wowKey.SetValue("CLSID", OBS_FILTER_CLSID, RegistryValueKind.String);
                                        wowKey.SetValue("FilterData", filterData, RegistryValueKind.Binary);
                                    }
                                }
                            }
                        }

                        // COM InprocServer32 registration
                        using (var clsidKey = root.CreateSubKey(string.Format(@"SOFTWARE\Classes\CLSID\{0}", OBS_FILTER_CLSID)))
                        {
                            if (clsidKey != null)
                            {
                                clsidKey.SetValue("", "OpenCam Virtual Camera Filter", RegistryValueKind.String);
                                if (File.Exists(dll64))
                                {
                                    using (var inproc = clsidKey.CreateSubKey("InprocServer32"))
                                    {
                                        if (inproc != null)
                                        {
                                            inproc.SetValue("", dll64, RegistryValueKind.String);
                                            inproc.SetValue("ThreadingModel", "Both", RegistryValueKind.String);
                                        }
                                    }
                                }
                            }
                        }

                        // WOW6432Node InprocServer32 for 32-bit DLL
                        if (File.Exists(dll32))
                        {
                            string[] wowClsidPaths = new string[]
                            {
                                string.Format(@"SOFTWARE\WOW6432Node\Classes\CLSID\{0}", OBS_FILTER_CLSID),
                                string.Format(@"SOFTWARE\Classes\WOW6432Node\CLSID\{0}", OBS_FILTER_CLSID)
                            };
                            foreach (var p in wowClsidPaths)
                            {
                                using (var wowClsid = root.CreateSubKey(p))
                                {
                                    if (wowClsid != null)
                                    {
                                        wowClsid.SetValue("", "OpenCam Virtual Camera Filter", RegistryValueKind.String);
                                        using (var inproc32 = wowClsid.CreateSubKey("InprocServer32"))
                                        {
                                            if (inproc32 != null)
                                            {
                                                inproc32.SetValue("", dll32, RegistryValueKind.String);
                                                inproc32.SetValue("ThreadingModel", "Both", RegistryValueKind.String);
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Media Foundation Transforms Categories
                        string[] mfCats = new string[] { MF_TRANSFORM_CATEGORY_CAPTURE, MF_TRANSFORM_CATEGORY_PROCESSOR };
                        foreach (var mfCatGuid in mfCats)
                        {
                            using (var mfCat = root.CreateSubKey(string.Format(@"SOFTWARE\Classes\MediaFoundation\Transforms\Categories\{0}\{1}", mfCatGuid, OPENCAM_INSTANCE_GUID)))
                            {
                                if (mfCat != null)
                                {
                                    mfCat.SetValue("FriendlyName", "OpenCam Virtual Camera", RegistryValueKind.String);
                                    mfCat.SetValue("CLSID", OBS_FILTER_CLSID, RegistryValueKind.String);
                                }
                            }
                        }
                    }
                    catch { }
                }

                // 3. Media Foundation & Windows Camera Frame Server DeviceClasses registration
                try
                {
                    string[] deviceCategories = new string[] { KSCATEGORY_CAPTURE_GUID, KSCATEGORY_VIDEO_GUID, KSCATEGORY_SENSOR_CAMERA_GUID };
                    foreach (var catGuid in deviceCategories)
                    {
                        using (var devClass = Registry.LocalMachine.CreateSubKey(string.Format(@"SYSTEM\CurrentControlSet\Control\DeviceClasses\{0}\##?#ROOT#OPENCAM#0000#{0}", catGuid)))
                        {
                            if (devClass != null)
                            {
                                devClass.SetValue("DeviceInstance", @"ROOT\OPENCAM\0000", RegistryValueKind.String);
                                using (var control = devClass.CreateSubKey("Control"))
                                {
                                    if (control != null) control.SetValue("ReferenceCount", 1, RegistryValueKind.DWord);
                                }
                                using (var devParams = devClass.CreateSubKey("#"))
                                {
                                    if (devParams != null)
                                    {
                                        devParams.SetValue("FriendlyName", "OpenCam Virtual Camera", RegistryValueKind.String);
                                        devParams.SetValue("CLSID", OBS_FILTER_CLSID, RegistryValueKind.String);
                                    }
                                }
                            }
                        }
                    }
                }
                catch { }

                return true;
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine("Registration error: " + ex.Message);
                return false;
            }
        }

        public static bool UnregisterVirtualCamera(string dllDir)
        {
            try
            {
                if (string.IsNullOrEmpty(dllDir))
                {
                    dllDir = AppDomain.CurrentDomain.BaseDirectory;
                }
                else
                {
                    dllDir = dllDir.Trim('"', '\'', ' ', '\t', '\r', '\n').TrimEnd('\\', '/');
                    if (string.IsNullOrEmpty(dllDir))
                    {
                        dllDir = AppDomain.CurrentDomain.BaseDirectory;
                    }
                }

                string dll64 = Path.Combine(dllDir, "obs-virtualcam-module64.dll");
                string dll32 = Path.Combine(dllDir, "obs-virtualcam-module32.dll");

                if (File.Exists(dll64))
                {
                    try
                    {
                        string regsvr64 = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System), "regsvr32.exe");
                        Process p64 = Process.Start(new ProcessStartInfo(regsvr64, string.Format("/u /s \"{0}\"", dll64)) { CreateNoWindow = true, UseShellExecute = false });
                        if (p64 != null) p64.WaitForExit(5000);
                    }
                    catch { }
                }

                if (File.Exists(dll32))
                {
                    try
                    {
                        string sysWow64 = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Windows), "SysWOW64");
                        string regsvr32Path = Directory.Exists(sysWow64)
                            ? Path.Combine(sysWow64, "regsvr32.exe")
                            : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System), "regsvr32.exe");

                        Process p32 = Process.Start(new ProcessStartInfo(regsvr32Path, string.Format("/u /s \"{0}\"", dll32)) { CreateNoWindow = true, UseShellExecute = false });
                        if (p32 != null) p32.WaitForExit(5000);
                    }
                    catch { }
                }

                RegistryKey[] roots = new RegistryKey[] { Registry.LocalMachine, Registry.CurrentUser };
                foreach (var root in roots)
                {
                    try
                    {
                        string[] instanceGuids = new string[] { OPENCAM_INSTANCE_GUID, OBS_FILTER_CLSID };
                        foreach (var instGuid in instanceGuids)
                        {
                            root.DeleteSubKeyTree(string.Format(@"SOFTWARE\Classes\CLSID\{0}\Instance\{1}", DSHOW_VIDEO_INPUT_CATEGORY, instGuid), false);
                            root.DeleteSubKeyTree(string.Format(@"SOFTWARE\Classes\WOW6432Node\CLSID\{0}\Instance\{1}", DSHOW_VIDEO_INPUT_CATEGORY, instGuid), false);
                            root.DeleteSubKeyTree(string.Format(@"SOFTWARE\WOW6432Node\Classes\CLSID\{0}\Instance\{1}", DSHOW_VIDEO_INPUT_CATEGORY, instGuid), false);
                        }

                        root.DeleteSubKeyTree(string.Format(@"SOFTWARE\Classes\CLSID\{0}", OBS_FILTER_CLSID), false);
                        root.DeleteSubKeyTree(string.Format(@"SOFTWARE\Classes\WOW6432Node\CLSID\{0}", OBS_FILTER_CLSID), false);
                        root.DeleteSubKeyTree(string.Format(@"SOFTWARE\WOW6432Node\Classes\CLSID\{0}", OBS_FILTER_CLSID), false);

                        root.DeleteSubKeyTree(string.Format(@"SOFTWARE\Classes\MediaFoundation\Transforms\Categories\{0}\{1}", MF_TRANSFORM_CATEGORY_CAPTURE, OPENCAM_INSTANCE_GUID), false);
                        root.DeleteSubKeyTree(string.Format(@"SOFTWARE\Classes\MediaFoundation\Transforms\Categories\{0}\{1}", MF_TRANSFORM_CATEGORY_PROCESSOR, OPENCAM_INSTANCE_GUID), false);
                    }
                    catch { }
                }

                try
                {
                    string[] deviceCategories = new string[] { KSCATEGORY_CAPTURE_GUID, KSCATEGORY_VIDEO_GUID, KSCATEGORY_SENSOR_CAMERA_GUID };
                    foreach (var catGuid in deviceCategories)
                    {
                        Registry.LocalMachine.DeleteSubKeyTree(string.Format(@"SYSTEM\CurrentControlSet\Control\DeviceClasses\{0}\##?#ROOT#OPENCAM#0000#{0}", catGuid), false);
                    }
                }
                catch { }

                return true;
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine("Unregistration error: " + ex.Message);
                return false;
            }
        }

        public static bool CheckStatus()
        {
            bool directShow = false;
            bool mediaFoundation = false;

            RegistryKey[] roots = new RegistryKey[] { Registry.CurrentUser, Registry.LocalMachine, Registry.ClassesRoot };
            foreach (var root in roots)
            {
                try
                {
                    string prefix = (root == Registry.ClassesRoot) ? "" : @"SOFTWARE\Classes\";
                    using (var key = root.OpenSubKey(string.Format(@"{0}CLSID\{1}\Instance\{2}", prefix, DSHOW_VIDEO_INPUT_CATEGORY, OPENCAM_INSTANCE_GUID)))
                    {
                        if (key != null)
                        {
                            var fn = key.GetValue("FriendlyName") as string;
                            if (!string.IsNullOrEmpty(fn) && fn.Contains("OpenCam"))
                            {
                                directShow = true;
                            }
                        }
                    }

                    if (!directShow)
                    {
                        using (var key = root.OpenSubKey(string.Format(@"{0}CLSID\{1}\Instance\{2}", prefix, DSHOW_VIDEO_INPUT_CATEGORY, OBS_FILTER_CLSID)))
                        {
                            if (key != null)
                            {
                                var fn = key.GetValue("FriendlyName") as string;
                                if (!string.IsNullOrEmpty(fn) && fn.Contains("OpenCam"))
                                {
                                    directShow = true;
                                }
                            }
                        }
                    }

                    using (var key = root.OpenSubKey(string.Format(@"{0}MediaFoundation\Transforms\Categories\{1}\{2}", prefix, MF_TRANSFORM_CATEGORY_CAPTURE, OPENCAM_INSTANCE_GUID)))
                    {
                        if (key != null) mediaFoundation = true;
                    }
                }
                catch { }

                if (directShow && mediaFoundation) break;
            }

            bool isRegistered = directShow || mediaFoundation;
            Console.WriteLine(string.Format("{{\"registered\":{0},\"directShow\":{1},\"mediaFoundation\":{2},\"friendlyName\":\"OpenCam Virtual Camera\"}}",
                isRegistered ? "true" : "false",
                directShow ? "true" : "false",
                mediaFoundation ? "true" : "false"));

            return isRegistered;
        }

        static int Main(string[] args)
        {
            // Register exit handler to ensure shared memory state is set to stopped
            AppDomain.CurrentDomain.ProcessExit += (s, e) => CleanupSharedMemory();
            Console.CancelKeyPress += (s, e) => CleanupSharedMemory();

            if (args.Length > 0)
            {
                string cmd = args[0].ToLowerInvariant();
                if (cmd == "--register" || cmd == "-r" || cmd == "/register")
                {
                    string dir = args.Length > 1 ? args[1] : null;
                    bool ok = RegisterVirtualCamera(dir);
                    Console.WriteLine(ok ? "SUCCESS: OpenCam Virtual Camera registered" : "FAILED: Could not register OpenCam Virtual Camera");
                    return ok ? 0 : 1;
                }
                if (cmd == "--unregister" || cmd == "-u" || cmd == "/unregister")
                {
                    string dir = args.Length > 1 ? args[1] : null;
                    bool ok = UnregisterVirtualCamera(dir);
                    Console.WriteLine(ok ? "SUCCESS: OpenCam Virtual Camera unregistered" : "FAILED: Could not unregister OpenCam Virtual Camera");
                    return ok ? 0 : 1;
                }
                if (cmd == "--status" || cmd == "-s" || cmd == "/status")
                {
                    bool ok = CheckStatus();
                    return ok ? 0 : 1;
                }
                if (cmd == "--feed" || cmd == "-f")
                {
                    int w = 1920;
                    int h = 1080;
                    int fps = 30;
                    if (args.Length > 1) int.TryParse(args[1], out w);
                    if (args.Length > 2) int.TryParse(args[2], out h);
                    if (args.Length > 3) int.TryParse(args[3], out fps);
                    RunFeeder(w, h, fps);
                    return 0;
                }
            }

            // Default: feed
            RunFeeder(1920, 1080, 30);
            return 0;
        }
    }
}
