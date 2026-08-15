@echo off
REM ============================================================================
REM  OpenCam Virtual Camera Unregistration Script for Windows
REM  Unregisters OpenCam from Windows Media Foundation and DirectShow
REM ============================================================================
setlocal enableextensions
cd /d "%~dp0"

net session >nul 2>&1
if %errorlevel% neq 0 (
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)

set "VCAM_DIR=%~dp0"
if "%VCAM_DIR:~-1%"=="\" set "VCAM_DIR=%VCAM_DIR:~0,-1%"

echo Unregistering OpenCam Virtual Camera...
if exist "vcam_feeder.exe" (
    "vcam_feeder.exe" --unregister "%VCAM_DIR%"
)

reg delete "HKLM\SOFTWARE\Microsoft\Windows Media Foundation\Platform" /v EnableFrameServerMode /f >nul 2>&1
reg delete "HKLM\SOFTWARE\WOW6432Node\Microsoft\Windows Media Foundation\Platform" /v EnableFrameServerMode /f >nul 2>&1
reg delete "HKCU\SOFTWARE\Microsoft\Windows Media Foundation\Platform" /v EnableFrameServerMode /f >nul 2>&1
reg delete "HKCU\SOFTWARE\WOW6432Node\Microsoft\Windows Media Foundation\Platform" /v EnableFrameServerMode /f >nul 2>&1

reg delete "HKLM\SYSTEM\CurrentControlSet\Control\DeviceClasses\{65e8773d-8f56-11d0-a3b9-00a0c9223196}\##?#ROOT#OPENCAM#0000#{65e8773d-8f56-11d0-a3b9-00a0c9223196}" /f >nul 2>&1
reg delete "HKLM\SYSTEM\CurrentControlSet\Control\DeviceClasses\{e5323777-ec62-4a8b-864b-0e5407163e58}\##?#ROOT#OPENCAM#0000#{e5323777-ec62-4a8b-864b-0e5407163e58}" /f >nul 2>&1
reg delete "HKLM\SYSTEM\CurrentControlSet\Control\DeviceClasses\{24e552d7-6523-47f7-a647-d3465bf1f5ca}\##?#ROOT#OPENCAM#0000#{24e552d7-6523-47f7-a647-d3465bf1f5ca}" /f >nul 2>&1

if exist "%windir%\System32\regsvr32.exe" (
    if exist "obs-virtualcam-module64.dll" "%windir%\System32\regsvr32.exe" /u /s "%VCAM_DIR%\obs-virtualcam-module64.dll"
)
if exist "%windir%\SysWOW64\regsvr32.exe" (
    if exist "obs-virtualcam-module32.dll" "%windir%\SysWOW64\regsvr32.exe" /u /s "%VCAM_DIR%\obs-virtualcam-module32.dll"
)

echo.
echo OpenCam Virtual Camera has been unregistered.
pause
exit /b 0
