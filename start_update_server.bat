@echo off
REM Build the named debug APK, then serve it only on an explicit ZeroTier interface.
setlocal
cd /d "%~dp0"

if "%~1"=="" (
    echo Usage: %~nx0 "ZeroTier interface alias" "ZeroTier IPv4 address"
    exit /b 2
)
if "%~2"=="" (
    echo Usage: %~nx0 "ZeroTier interface alias" "ZeroTier IPv4 address"
    exit /b 2
)

call gradlew.bat assembleNamedDebugApk
if errorlevel 1 (
    echo [update-server] BUILD FAILED
    exit /b 1
)

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\update-server\Start-OssmUpdateServer.ps1" -ZeroTierInterfaceAlias "%~1" -ZeroTierAddress "%~2"
exit /b %errorlevel%
