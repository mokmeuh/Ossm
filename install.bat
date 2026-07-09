@echo off
REM [install] Installe l'APK debug deja compile sur l'appareil (adb) et lance l'app.
setlocal
cd /d "%~dp0"
set "APK=app\build\outputs\apk\debug\app-debug.apk"
if not exist "%APK%" (
    echo [install] APK introuvable : %APK% ^(lance build.bat d'abord^)
    exit /b 1
)
echo [install] adb install -r %APK% ...
adb install -r "%APK%"
if errorlevel 1 (
    echo [install] ECHOUE ^(adb dans le PATH ? telephone branche + debogage USB ?^)
    exit /b 1
)
adb shell monkey -p com.ossm.remote -c android.intent.category.LAUNCHER 1 >nul 2>&1
echo [install] OK
exit /b 0
