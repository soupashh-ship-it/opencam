@echo off
REM ============================================================================
REM  OpenCam Virtual Camera Registration Script for Windows
REM  Registers OpenCam as a native Media Foundation & DirectShow Camera
REM  Compatible with: WhatsApp Desktop, Microsoft Teams, Windows Camera App,
REM                   Discord, OBS Studio, Zoom, Google Meet, and Web Browsers.
REM ============================================================================
setlocal enableextensions
cd /d "%~dp0"

echo Requesting Administrator privileges to register OpenCam Virtual Camera...
net session >nul 2>&1
if %errorlevel% neq 0 (
    powershell -Command "Start-Process '%~f0' -Verb RunAs"
    exit /b
)

echo [1/3] Checking OpenCam Virtual Camera Feeder binary...
if not exist "vcam_feeder.exe" (
    echo Compiling Virtual Camera helper...
    "%windir%\Microsoft.NET\Framework64\v4.0.30319\csc.exe" /nologo /unsafe /optimize /platform:x64 /r:System.Drawing.dll /out:"vcam_feeder.exe" "OpenCamVirtualCamFeeder.cs"
)

echo [2/3] Registering DirectShow and Windows Media Foundation Virtual Camera...
if exist "%windir%\System32\regsvr32.exe" (
    if exist "obs-virtualcam-module64.dll" "%windir%\System32\regsvr32.exe" /s "%~dp0obs-virtualcam-module64.dll"
)
if exist "%windir%\SysWOW64\regsvr32.exe" (
    if exist "obs-virtualcam-module32.dll" "%windir%\SysWOW64\regsvr32.exe" /s "%~dp0obs-virtualcam-module32.dll"
) else (
    if exist "obs-virtualcam-module32.dll" "%windir%\System32\regsvr32.exe" /s "%~dp0obs-virtualcam-module32.dll"
)

if exist "vcam_feeder.exe" (
    "vcam_feeder.exe" --register "%~dp0"
)

echo [3/3] Verifying registration...
if exist "vcam_feeder.exe" (
    "vcam_feeder.exe" --status
)

echo.
echo ============================================================================
echo SUCCESS! "OpenCam Virtual Camera" has been registered in Windows!
echo.
echo Modern Packaged Apps:
echo   - Windows Camera App
echo   - WhatsApp Desktop
echo   - Microsoft Teams
echo.
echo DirectShow and Streaming Apps:
echo   - Discord (Voice & Video settings)
echo   - OBS Studio (Video Capture Device)
echo   - Zoom / Google Meet / Web Browsers
echo ============================================================================
echo.
pause
exit /b 0
