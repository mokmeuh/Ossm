@echo off
REM [commit] git add -A + commit local.
REM   Usage : commit.bat              -> message "Release vX.Y.Z" (version courante)
REM           commit.bat "mon texte"  -> message personnalise
setlocal enabledelayedexpansion
cd /d "%~dp0"
set "MSG=%~1"
if "%MSG%"=="" (
    for /f "delims=" %%v in ('powershell -NoProfile -Command "(Select-String -Path '%~dp0app\build.gradle.kts' -Pattern 'versionName\s*=\s*\"(.*?)\"').Matches.Groups[1].Value"') do set "VNAME=%%v"
    set "MSG=Release v!VNAME!"
)
echo [commit] "!MSG!"
git add -A
git commit -m "!MSG!"
if errorlevel 1 (
    echo [commit] rien a committer, ou echec du commit
    exit /b 1
)
echo [commit] OK
exit /b 0
