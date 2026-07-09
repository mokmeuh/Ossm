@echo off
REM [all] Pipeline complet, dans l'ordre :
REM   bump -> build -> install (adb) -> commit -> push
REM   Usage : all.bat              -> message de commit auto "Release vX.Y.Z"
REM           all.bat "mon texte"  -> message de commit personnalise
setlocal
cd /d "%~dp0"

echo ===== OSSM : PIPELINE COMPLET =====
echo.

call "%~dp0bump.bat"        || goto :err
call "%~dp0build.bat"       || goto :err
call "%~dp0install.bat"     || goto :err
call "%~dp0commit.bat" %1   || goto :err
call "%~dp0push.bat"        || goto :err

echo.
echo ===== PIPELINE OK : buildee + installee sur le tel + commitee + poussee sur GitHub =====
pause
exit /b 0

:err
echo.
echo ===== PIPELINE INTERROMPU (voir l'erreur ci-dessus) =====
pause
exit /b 1
