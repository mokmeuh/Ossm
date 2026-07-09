@echo off
REM [bump] Incremente versionCode (+1) et le patch de versionName (x.y.Z+1).
setlocal
cd /d "%~dp0"
for /f "delims=" %%v in ('powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0bump_version.ps1"') do set "VNAME=%%v"
if "%VNAME%"=="" (
    echo [bump] ECHOUE
    exit /b 1
)
echo [bump] Nouvelle version : %VNAME%
exit /b 0
