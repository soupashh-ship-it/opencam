@echo off
rem OpenCam Client launcher — double-click to start.
cd /d "%~dp0"

where py >nul 2>nul
if %errorlevel%==0 (
    set PY=py -3
) else (
    where python >nul 2>nul
    if %errorlevel%==0 (
        set PY=python
    ) else (
        echo Python 3 was not found. Install it from https://www.python.org/downloads/
        pause
        exit /b 1
    )
)

%PY% -c "import PIL" >nul 2>nul
if errorlevel 1 (
    echo Installing Pillow ^(needed for video preview^)...
    %PY% -m pip install pillow
)

%PY% opencam_client.py
