@echo off
REM [wifi_connect] Double-clic : (re)connecte adb en Wi-Fi.
REM Essaie l'IP memorisee ; si echec, amorce via l'USB (detecte l'IP, tcpip 5555, reconnecte).
setlocal
cd /d "%~dp0"
echo === OSSM : reconnexion adb Wi-Fi ===
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0wifi_connect.ps1"
echo.
echo --- adb devices ---
adb devices
echo.
pause
