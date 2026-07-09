@echo off
REM [build] Compile l'APK debug (aucune action sur l'appareil).
setlocal
cd /d "%~dp0"
echo [build] gradlew assembleDebug ...
call gradlew.bat assembleDebug
if errorlevel 1 (
    echo [build] ECHOUE
    exit /b 1
)
echo [build] OK -^> app\build\outputs\apk\debug\app-debug.apk
exit /b 0
