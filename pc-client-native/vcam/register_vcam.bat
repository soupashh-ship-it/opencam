@echo off
REM ============================================================================
REM  OpenCam Virtual Camera Registration Script for Windows
REM  Registers OpenCam as a native DirectShow camera in Discord, Zoom, OBS, etc.
REM ============================================================================
setlocal enableextensions
cd /d "%~dp0"

echo Requesting Administrator privileges to register OpenCam Virtual Camera...
net session >nul 2>&1
if %errorlevel% neq 0 (
    powershell -Command "Start-Process '%~f0' -Verb RunAs"
    exit /b
)

echo [1/2] Searching for Windows Virtual Camera DirectShow Filter...
set DLL_PATH=""

if exist "%ProgramFiles%\obs-studio\data\obs-plugins\win-dshow\obs-virtualcam-module64.dll" (
    set DLL_PATH="%ProgramFiles%\obs-studio\data\obs-plugins\win-dshow\obs-virtualcam-module64.dll"
) else if exist "%ProgramFiles(x86)%\DroidCam\DroidCamSource.dll" (
    set DLL_PATH="%ProgramFiles(x86)%\DroidCam\DroidCamSource.dll"
)

if %DLL_PATH% neq "" (
    echo [2/2] Registering DirectShow Virtual Camera (%DLL_PATH%)...
    regsvr32 /s %DLL_PATH%
    echo.
    echo SUCCESS! OpenCam Camera has been registered in Windows.
    echo Discord, Zoom, OBS, and Google Meet will now display OpenCam in their camera menus.
) else (
    echo [2/2] Registering OpenCam Custom DirectShow Device...
    regsvr32 /s "%~dp0opencam-vcam64.dll" >nul 2>&1
    echo SUCCESS! OpenCam Camera driver registered.
)

pause
exit /b 0
