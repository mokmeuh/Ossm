@echo off
REM [push] Pousse les commits locaux vers GitHub (origin).
setlocal
cd /d "%~dp0"
echo [push] git push ...
git push
if errorlevel 1 (
    echo [push] ECHOUE ^(auth GitHub / reseau ?^)
    exit /b 1
)
echo [push] OK
exit /b 0
