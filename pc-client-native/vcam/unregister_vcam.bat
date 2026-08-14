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
