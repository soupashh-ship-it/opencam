@echo off
rem Build OpenCamClient.exe (standalone, no Python needed on target machines).
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

%PY% -m pip install --upgrade pyinstaller pillow pyvirtualcam numpy av

%PY% -m PyInstaller --noconfirm --clean --onefile --windowed ^
    --name OpenCamClient --add-data "vcam;vcam" --add-data "vcam_mf;vcam_mf" ^
    --collect-all av opencam_client.py

if errorlevel 1 (
    echo.
    echo Build FAILED.
    pause
    exit /b 1
)

echo.
echo Verifying the bundle with the built-in self-test...
dist\OpenCamClient.exe --selftest
if errorlevel 1 (
    echo.
    echo Self-test FAILED — the exe is broken, do not ship it.
    pause
    exit /b 1
)

echo.
echo Done. The exe is in: %cd%\dist\OpenCamClient.exe
echo Copy it anywhere and double-click to run.
echo ^(First run: Windows SmartScreen may warn about the unsigned exe — More info -^> Run anyway^)
pause
