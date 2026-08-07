#pragma once
#include <windows.h>
#include <cstdint>
#include <string>

// ---------------------------------------------------------------------------
// OpenCam virtual camera — shared frame buffer protocol.
//
//   opencam-vcam.exe  (creator of the camera)
//   source DLL        (inside the FrameServer host; READS the latest frame)
//   Python client     (WRITES decoded BGRA frames)
//
// The buffer is a FILE-BACKED section in %ProgramData%\OpenCamClient\vcam_mf\
// (frame.bin). Unlike Local\Global\ named objects, a file-backed section is
// coherent across sessions and privilege contexts: every side just opens the
// file and maps it. This sidesteps the "FrameServer host runs in a different
// session/context" trap entirely.
//
// The DLL (FrameGenerator) is driven by the consumer's clock (RequestSample),
// so it always reads the newest complete frame from this buffer. The writer
// writes pixels first, then bumps frameIndex; the reader copies only when the
// index changed. 64-byte header + BGRA32 pixels at 1920x1080.
// ---------------------------------------------------------------------------

#define OCV_STOP_EVENT_NAME  L"Local\\OpenCamVcamStop"
#define OCV_READY_EVENT_NAME L"Local\\OpenCamVcamReady"
#define OCV_CAMERA_NAME      L"OpenCam Virtual Camera"

#define OCV_MAGIC            0x4F435646u   // "OCVF"
#define OCV_VERSION          1u
#define OCV_FRAME_W          1920
#define OCV_FRAME_H          1080
#define OCV_STRIDE           (OCV_FRAME_W * 4)      // BGRA32
#define OCV_PIXEL_BYTES      (OCV_STRIDE * OCV_FRAME_H)
#define OCV_SHM_SIZE         (64 + OCV_PIXEL_BYTES) // ~8.3 MB
#define OCV_FORMAT_BGRA      0

#pragma pack(push, 1)
struct OcvSharedHeader
{
    uint32_t magic;          // OCV_MAGIC
    uint32_t version;        // OCV_VERSION
    int32_t  width;          // OCV_FRAME_W
    int32_t  height;         // OCV_FRAME_H
    int32_t  stride;         // OCV_STRIDE (bytes per row)
    int32_t  format;         // OCV_FORMAT_BGRA
    volatile LONG frameIndex; // bumped by the writer after each complete frame
    uint32_t reserved[9];     // pad to 64 bytes
};
#pragma pack(pop)

static_assert(sizeof(OcvSharedHeader) == 64, "header must be exactly 64 bytes");

inline OcvSharedHeader* OcvHeader(BYTE* base) { return reinterpret_cast<OcvSharedHeader*>(base); }
inline BYTE* OcvPixels(BYTE* base) { return base + 64; }

// Full path of the backing file, e.g.
//   C:\ProgramData\OpenCamClient\vcam_mf\frame.bin
inline std::wstring OcvShmFilePath()
{
    wchar_t progData[1024] = {};
    DWORD n = GetEnvironmentVariableW(L"ProgramData", progData, 1024);
    if (!n)
        return std::wstring(L"C:\\ProgramData\\OpenCamClient\\vcam_mf\\frame.bin");
    return std::wstring(progData) + L"\\OpenCamClient\\vcam_mf\\frame.bin";
}

// Open (or create) the file-backed section and map it.
// File handles are opened with generous sharing so every process — including
// the FrameServer host running under a different account — can map the same
// file. Returns S_OK with *outHandle/*outBase on success.
//
// The access flag decides the file open mode:
//   FILE_MAP_WRITE  -> open the file read/write, create + size it (writer)
//   otherwise       -> open read-only (the FrameServer host runs as
//                      NT AUTHORITY\LocalService, which only has read access
//                      to files created by the user)
inline HRESULT OcvOpenMapping(HANDLE* outHandle, BYTE** outBase, DWORD access = FILE_MAP_ALL_ACCESS)
{
    if (!outHandle || !outBase)
        return E_POINTER;
    *outHandle = nullptr;
    *outBase = nullptr;

    bool writable = (access & FILE_MAP_WRITE) != 0;
    std::wstring path = OcvShmFilePath();

    // create the parent folder if missing (best effort — ProgramData is user-writable)
    if (writable)
    {
        size_t slash = path.find_last_of(L'\\');
        if (slash != std::wstring::npos)
        {
            std::wstring dir = path.substr(0, slash);
            CreateDirectoryW(dir.c_str(), nullptr);
            size_t s2 = dir.find_last_of(L'\\');
            if (s2 != std::wstring::npos)
            {
                std::wstring dir2 = dir.substr(0, s2);
                CreateDirectoryW(dir2.c_str(), nullptr);
                size_t s3 = dir2.find_last_of(L'\\');
                if (s3 != std::wstring::npos)
                {
                    CreateDirectoryW(dir2.substr(0, s3).c_str(), nullptr);
                }
            }
        }
    }

    // read-only consumers must not require write access to the file
    HANDLE file = CreateFileW(path.c_str(),
        writable ? (GENERIC_READ | GENERIC_WRITE) : GENERIC_READ,
        FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
        nullptr, writable ? OPEN_ALWAYS : OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (file == INVALID_HANDLE_VALUE)
        return HRESULT_FROM_WIN32(GetLastError());

    if (writable)
    {
        // make sure the file is big enough for the whole buffer
        LARGE_INTEGER size;
        size.QuadPart = OCV_SHM_SIZE;
        SetFilePointerEx(file, size, nullptr, FILE_BEGIN);
        SetEndOfFile(file);
    }

    HANDLE map = CreateFileMappingW(file, nullptr,
        writable ? PAGE_READWRITE : PAGE_READONLY, 0, 0, nullptr);
    CloseHandle(file); // the section keeps the file open
    if (!map)
        return HRESULT_FROM_WIN32(GetLastError());

    BYTE* base = (BYTE*)MapViewOfFile(map, access, 0, 0, OCV_SHM_SIZE);
    if (!base)
    {
        auto err = GetLastError();
        CloseHandle(map);
        return HRESULT_FROM_WIN32(err);
    }

    *outHandle = map;
    *outBase = base;
    return S_OK;
}
