@echo off
REM [build_install] Build l'APK debug (version courante) + installe sur le telephone.
REM Ecrit le resultat dans build_install_status.txt pour verification.
setlocal
cd /d "%~dp0"
set "APK=app\build\outputs\apk\debug\app-debug.apk"
set "PKG=com.ossm.remote.debug"

echo RUNNING > build_install_status.txt
echo ===== OSSM : BUILD + INSTALL =====
echo.

echo [build] gradlew assembleDebug ...
call gradlew.bat assembleDebug
if errorlevel 1 (
    echo BUILD_FAILED > build_install_status.txt
    echo.
    echo *** BUILD ECHOUE ***
    pause
    exit /b 1
)
echo [build] OK

echo [install] adb install -r %APK% ...
adb install -r "%APK%" > install_result.txt 2>&1
type install_result.txt
findstr /i "Success" install_result.txt >nul
if errorlevel 1 (
    echo INSTALL_FAILED > build_install_status.txt
    type install_result.txt >> build_install_status.txt
    echo.
    echo *** INSTALL ECHOUE ^(voir ci-dessus : adb dans le PATH ? tel autorise ?^) ***
    pause
    exit /b 1
)

echo INSTALL_OK > build_install_status.txt
type install_result.txt >> build_install_status.txt
echo [install] OK - lancement de l'app...
adb shell monkey -p %PKG% -c android.intent.category.LAUNCHER 1 >nul 2>&1

echo.
echo ===== OK : version installee sur le telephone =====
pause
