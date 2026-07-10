@echo off
REM [build_install] Build l'APK debug (version courante) + installe sur le telephone.
REM Choix de l'appareil : USB filaire d'abord, sinon Wi-Fi, sinon wifi_connect (voir adb_target.ps1).
setlocal
cd /d "%~dp0"
set "APK=app\build\outputs\apk\debug\app-debug.apk"
set "PKG=com.ossm.remote.debug"

echo ===== OSSM : BUILD + INSTALL =====
echo.
echo [build] gradlew assembleDebug ...
call gradlew.bat assembleDebug
if errorlevel 1 (
    echo.
    echo *** BUILD ECHOUE ***
    pause
    exit /b 1
)
echo [build] OK

echo [adb] selection de l'appareil (USB ^> Wi-Fi ^> wifi_connect)...
set "ADB_TARGET="
for /f "delims=" %%t in ('powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0adb_target.ps1"') do set "ADB_TARGET=%%t"
if not defined ADB_TARGET (
    echo.
    echo *** Aucun appareil adb ^(ni USB ni Wi-Fi^). Branche le cable UNE fois, ou lance wifi_connect.bat ***
    pause
    exit /b 1
)
echo [install] cible = %ADB_TARGET%
adb -s %ADB_TARGET% install -r "%APK%"
if errorlevel 1 (
    echo.
    echo *** INSTALL ECHOUE ^(appareil %ADB_TARGET%^) ***
    pause
    exit /b 1
)
adb -s %ADB_TARGET% shell monkey -p %PKG% -c android.intent.category.LAUNCHER 1 >nul 2>&1
echo.
echo ===== OK : v courante installee sur %ADB_TARGET% =====
pause
