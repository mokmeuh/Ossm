# Demarre le serveur de mise a jour OSSM (port 8091) dans une fenetre minimisee.
# Ecoute uniquement sur 127.0.0.1 et l'IPv4 Tailscale du PC (jamais 0.0.0.0).
#   Start-OssmApkServer.ps1 [-Dossier <dossier des APK>]
param([string]$Dossier = '')
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$server = Join-Path $here 'server.js'
$port = 8091
$title = 'OSSM - serveur APK'

$node = Get-Command node.exe -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $node) { Write-Host 'Node.js introuvable (installe Node 18+).' -ForegroundColor Red; exit 1 }

function Test-Listening {
    try {
        $c = New-Object System.Net.Sockets.TcpClient
        $iar = $c.BeginConnect('127.0.0.1', $port, $null, $null)
        $ok = $iar.AsyncWaitHandle.WaitOne(500) -and $c.Connected
        $c.Close()
        return [bool]$ok
    } catch { return $false }
}

if (Test-Listening) {
    Write-Host "Le port $port est deja en ecoute : le serveur tourne deja." -ForegroundColor Yellow
} else {
    $q = '"'
    $cmdLine = "/k title $title & $q$($node.Source)$q $q$server$q"
    if ($Dossier) { $cmdLine += " --dir $q$((Resolve-Path $Dossier).Path)$q" }
    Start-Process -FilePath "$env:SystemRoot\System32\cmd.exe" -ArgumentList $cmdLine -WorkingDirectory $here -WindowStyle Minimized | Out-Null
    $up = $false
    for ($i = 0; $i -lt 20; $i++) { Start-Sleep -Milliseconds 500; if (Test-Listening) { $up = $true; break } }
    if (-not $up) {
        Write-Host "Le serveur n'ecoute pas apres 10 s : ouvre la fenetre '$title' pour voir l'erreur." -ForegroundColor Red
        exit 2
    }
    Write-Host "Serveur demarre (fenetre minimisee '$title')." -ForegroundColor Green
}

$ts = $null
foreach ($exe in @('tailscale', 'C:\Program Files\Tailscale\tailscale.exe')) {
    try {
        $ts = & $exe ip -4 2>$null | Where-Object { $_ -match '^100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\.\d{1,3}\.\d{1,3}$' } | Select-Object -First 1
        if ($ts) { break }
    } catch { }
}
Write-Host ''
if ($ts) {
    Write-Host "Adresse a saisir dans l'app (Reglages > Mise a jour depuis le PC) : http://$($ts):$port/" -ForegroundColor Cyan
} else {
    Write-Host 'Tailscale introuvable : le serveur n''ecoute que sur 127.0.0.1.' -ForegroundColor Yellow
}
Write-Host "Arret : Stop-OssmApkServer.cmd (ou Ctrl+C dans la fenetre '$title')."
