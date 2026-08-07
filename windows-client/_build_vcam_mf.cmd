@echo off
setlocal
cd /d "E:\Reverse Engineer Droid\OpenCam\windows-client\vcam_mf_cpp"

echo === restoring packages ===
"E:\Reverse Engineer Droid\OpenCam\windows-client\nuget.exe" restore VCamSample.sln -NonInteractive -Verbosity quiet
echo RESTORE_RC=%ERRORLEVEL%

set BT=C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools
echo === building x64 Release ===
"%BT%\MSBuild\Current\Bin\MSBuild.exe" VCamSample.sln /p:Configuration=Release /p:Platform=x64 /p:PlatformToolset=v143 /m /v:minimal
echo BUILD_RC=%ERRORLEVEL%

echo === staging outputs ===
set STAGE=E:\Reverse Engineer Droid\OpenCam\windows-client\vcam_mf
if not exist "%STAGE%" mkdir "%STAGE%"
copy /y "x64\Release\VCamSampleSource.dll" "%STAGE%\OpenCamVcamSource.dll" >nul
copy /y "x64\Release\VCamSample.exe"      "%STAGE%\opencam-vcam.exe" >nul
dir /b "%STAGE%"
endlocal
