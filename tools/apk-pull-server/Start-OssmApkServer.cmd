@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0Start-OssmApkServer.ps1" %*
pause
