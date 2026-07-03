@echo off
REM Recompile + installe OSSM Remote. Tout est journalise dans build_result.log
REM (lisible par Claude/Cowork sans capture d'ecran).
cd /d "%~dp0"
set LOG=build_result.log
echo [%date% %time%] ===== Tests unitaires ===== > "%LOG%"
call gradlew.bat testDebugUnitTest >> "%LOG%" 2>&1
if errorlevel 1 (
    echo [%date% %time%] RESULTAT: ECHEC_TESTS >> "%LOG%"
    echo *** ECHEC DES TESTS - voir build_result.log ***
    exit /b 1
)
echo [%date% %time%] ===== Build + installation ===== >> "%LOG%"
call gradlew.bat installDebug >> "%LOG%" 2>&1
if errorlevel 1 (
    echo [%date% %time%] RESULTAT: ECHEC_INSTALL >> "%LOG%"
    echo *** ECHEC BUILD/INSTALL - telephone branche? debogage USB actif? ***
    exit /b 1
)
echo [%date% %time%] RESULTAT: SUCCES_INSTALL >> "%LOG%"
echo ===== OK : app installee =====
exit /b 0
