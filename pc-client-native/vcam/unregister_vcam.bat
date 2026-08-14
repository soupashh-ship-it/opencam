@echo off
REM ============================================================================
REM  OpenCam Virtual Camera Unregistration Script for Windows
REM  Unregisters OpenCam from Windows Media Foundation and DirectShow
REM ============================================================================
setlocal enableextensions
cd /d "%~dp0"

net session >nul 2>&1
if %errorlevel% neq 0 (
    powershell -Command "Start-Process '%~f0' -Verb RunAs"
    exit /b
)

echo Unregistering OpenCam Virtual Camera...
if exist "vcam_feeder.exe" (
    "vcam_feeder.exe" --unregister "%~dp0"
)

if exist "%windir%\System32\regsvr32.exe" (
    if exist "obs-virtualcam-module64.dll" "%windir%\System32\regsvr32.exe" /u /s "%~dp0obs-virtualcam-module64.dll"
)
if exist "%windir%\SysWOW64\regsvr32.exe" (
    if exist "obs-virtualcam-module32.dll" "%windir%\SysWOW64\regsvr32.exe" /u /s "%~dp0obs-virtualcam-module32.dll"
)

echo.
echo OpenCam Virtual Camera has been unregistered.
pause
exit /b 0
