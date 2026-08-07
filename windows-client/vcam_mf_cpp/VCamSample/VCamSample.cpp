#include "framework.h"
#include "tools.h"
#include "VCamSample.h"
#include "../VCamSampleSource/SharedFrame.h"

#define MAX_LOADSTRING 100

// 84e6175f-bd77-4633-ad1f-17aa72c8e7da  (OpenCam Virtual Camera source)
static GUID CLSID_VCam = { 0x84e6175f,0xbd77,0x4633,{0xad,0x1f,0x17,0xaa,0x72,0xc8,0xe7,0xda} };

HINSTANCE _instance;
WCHAR _title[MAX_LOADSTRING];
WCHAR _windowClass[MAX_LOADSTRING];
wil::com_ptr_nothrow<IMFVirtualCamera> _vcam;

HRESULT RegisterVirtualCamera();
HRESULT UnregisterVirtualCamera();

int APIENTRY wWinMain(_In_ HINSTANCE hInstance, _In_opt_ HINSTANCE hPrevInstance, _In_ LPWSTR lpCmdLine, _In_ int nCmdShow)
{
	UNREFERENCED_PARAMETER(hPrevInstance);
	UNREFERENCED_PARAMETER(lpCmdLine);
	UNREFERENCED_PARAMETER(nCmdShow);

	WinTraceRegister();
	WINTRACE(L"opencam-vcam starting '%s'", GetCommandLineW());
	_CrtSetReportMode(_CRT_WARN, _CRTDBG_MODE_DEBUG);

	wil::SetResultLoggingCallback([](wil::FailureInfo const& failure) noexcept
		{
			wchar_t str[2048];
			if (SUCCEEDED(wil::GetFailureLogString(str, _countof(str), failure)))
			{
				WinTrace(2, 0, str); // 2 => error
			}
		});

	lstrcpynW(_title, OCV_CAMERA_NAME, MAX_LOADSTRING);

	// ---- create the shared frame buffer first (the FrameServer-hosted source
	// DLL reads the client's BGRA frames from here). File-backed section so
	// every context (user session, FrameServer host) sees the same buffer. ----
	HANDLE mapHandle = nullptr;
	BYTE* mapBase = nullptr;
	HRESULT mapHr = OcvOpenMapping(&mapHandle, &mapBase);

	if (SUCCEEDED(mapHr) && mapBase)
	{
		ZeroMemory(mapBase, OCV_SHM_SIZE);
		auto hdr = OcvHeader(mapBase);
		hdr->magic = OCV_MAGIC;
		hdr->version = OCV_VERSION;
		hdr->width = OCV_FRAME_W;
		hdr->height = OCV_FRAME_H;
		hdr->stride = OCV_STRIDE;
		hdr->format = OCV_FORMAT_BGRA;
		hdr->frameIndex = 0;
		WINTRACE(L"shared frame buffer ready (%u x %u, %u bytes)",
			OCV_FRAME_W, OCV_FRAME_H, OCV_SHM_SIZE);
	}
	else
	{
		WINTRACE(L"WARNING: could not create shared frame buffer (0x%08X)", GetLastError());
	}

	winrt::init_apartment();
	if (SUCCEEDED(MFStartup(MF_VERSION)))
	{
		auto hr = RegisterVirtualCamera();
		if (SUCCEEDED(hr))
		{
			WINTRACE(L"virtual camera '%s' is live", OCV_CAMERA_NAME);

		// tell the Python client the camera is registered and ready to serve
		// frames (it waits on this event instead of probing with external tools)
		if (HANDLE readyEvent = CreateEventW(nullptr, TRUE, FALSE, OCV_READY_EVENT_NAME))
		{
			SetEvent(readyEvent);
			CloseHandle(readyEvent);
		}

		// stay alive until the client signals stop (or kills this process —
		// with MFVirtualCameraLifetime_Session the camera disappears then too)
		HANDLE stopEvent = CreateEventW(nullptr, TRUE, FALSE, OCV_STOP_EVENT_NAME);
			if (stopEvent)
			{
				ResetEvent(stopEvent); // clear a stale signal from a previous run
				WINTRACE(L"waiting for stop event");
				WaitForSingleObject(stopEvent, INFINITE);
				CloseHandle(stopEvent);
			}
			else
			{
				// no event support — just keep running until killed
				while (true)
					Sleep(1000);
			}

			UnregisterVirtualCamera();
		}
		else
		{
			wchar_t text[256];
			FormatMessage(FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS,
				nullptr, hr, 0, text, _countof(text), nullptr);
			WINTRACE(L"opencam-vcam could not start (0x%08X): %s", hr, text);
		}

		_vcam.reset();
		MFShutdown();
	}

	if (mapBase)
		UnmapViewOfFile(mapBase);
	if (mapHandle)
		CloseHandle(mapHandle);

	_CrtDumpMemoryLeaks();
	WINTRACE(L"opencam-vcam exiting");
	WinTraceUnregister();
	return 0;
}

HRESULT RegisterVirtualCamera()
{
	auto clsid = GUID_ToStringW(CLSID_VCam);
	RETURN_IF_FAILED_MSG(MFCreateVirtualCamera(
		MFVirtualCameraType_SoftwareCameraSource,
		MFVirtualCameraLifetime_Session,
		MFVirtualCameraAccess_CurrentUser,
		_title,
		clsid.c_str(),
		nullptr,
		0,
		&_vcam),
		"Failed to create virtual camera");

	WINTRACE(L"RegisterVirtualCamera '%s' ok", clsid.c_str());
	RETURN_IF_FAILED_MSG(_vcam->Start(nullptr), "Cannot start VCam");
	WINTRACE(L"VCam was started");
	return S_OK;
}

HRESULT UnregisterVirtualCamera()
{
	if (!_vcam)
		return S_OK;

	// NOTE: we don't call Shutdown or this will cause 2 Shutdown calls to the
	// media source and will prevent proper removing
	auto hr = _vcam->Remove();
	WINTRACE(L"Remove VCam hr:0x%08X", hr);
	return S_OK;
}
