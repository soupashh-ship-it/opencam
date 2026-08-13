@echo off
REM ============================================================================
REM  OpenCam Virtual Camera Unregistration Script for Windows
REM ============================================================================
setlocal enableextensions
cd /d "%~dp0"

net session >nul 2>&1
if %errorlevel% neq 0 (
    powershell -Command "Start-Process '%~f0' -Verb RunAs"
    exit /b
)

echo Unregistering OpenCam Virtual Camera...
if exist "%ProgramFiles%\obs-studio\data\obs-plugins\win-dshow\obs-virtualcam-module64.dll" (
    regsvr32 /u /s "%ProgramFiles%\obs-studio\data\obs-plugins\win-dshow\obs-virtualcam-module64.dll"
)
regsvr32 /u /s "%~dp0opencam-vcam64.dll" >nul 2>&1

echo OpenCam Virtual Camera unregistered.
pause
exit /b 0
