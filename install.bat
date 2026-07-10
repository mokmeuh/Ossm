@echo off
REM [install] Installe l'APK debug deja compile + lance l'app.
REM Choix de l'appareil : USB filaire d'abord, sinon Wi-Fi, sinon wifi_connect (adb_target.ps1).
setlocal
cd /d "%~dp0"
set "APK=app\build\outputs\apk\debug\app-debug.apk"
set "PKG=com.ossm.remote.debug"

if not exist "%APK%" (
    echo [install] APK introuvable : %APK% ^(lance build.bat d'abord^)
    pause
    exit /b 1
)

echo [adb] selection de l'appareil (USB ^> Wi-Fi ^> wifi_connect)...
set "ADB_TARGET="
for /f "delims=" %%t in ('powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0adb_target.ps1"') do set "ADB_TARGET=%%t"
if not defined ADB_TARGET (
    echo *** Aucun appareil adb ^(ni USB ni Wi-Fi^). Branche le cable UNE fois, ou lance wifi_connect.bat ***
    pause
    exit /b 1
)
echo [install] cible = %ADB_TARGET%
adb -s %ADB_TARGET% install -r "%APK%"
if errorlevel 1 (
    echo *** INSTALL ECHOUE ^(appareil %ADB_TARGET%^) ***
    pause
    exit /b 1
)
adb -s %ADB_TARGET% shell monkey -p %PKG% -c android.intent.category.LAUNCHER 1 >nul 2>&1
echo [install] OK sur %ADB_TARGET%
pause
