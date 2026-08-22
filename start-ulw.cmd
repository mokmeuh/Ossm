@echo off
setlocal
set "OPENCODE_EXPERIMENTAL_BACKGROUND_SUBAGENTS=1"
wt.exe -w 0 new-tab -p Ubuntu wsl.exe -d Ubuntu-24.04 bash -lc "cd /home/mikki/Projects/Ossm && opencode --continue"
endlocal
