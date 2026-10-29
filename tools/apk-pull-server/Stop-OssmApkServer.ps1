# Arrete le serveur de mise a jour OSSM (processus node qui execute ce server.js).
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$server = Join-Path $here 'server.js'
$procs = Get-CimInstance Win32_Process -Filter "Name = 'node.exe'" |
    Where-Object { $_.CommandLine -and $_.CommandLine.Contains($server) }
if (-not $procs) { Write-Host 'Le serveur ne tourne pas.' -ForegroundColor Yellow; exit 0 }
foreach ($p in $procs) { Stop-Process -Id $p.ProcessId -Force -Confirm:$false }
Write-Host 'Serveur APK arrete.' -ForegroundColor Green
