# Demarre le relais de raisonnement OSSM (port 11435) dans une fenetre minimisee.
# Ecoute uniquement sur 127.0.0.1 et l'IPv4 Tailscale du PC (jamais 0.0.0.0).
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$relay = Join-Path $here 'relay.js'
$port = 11435
$title = 'OSSM - relais raisonnement'

$node = Get-Command node.exe -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $node) { Write-Host 'Node.js introuvable (installe Node 18+).' -ForegroundColor Red; exit 1 }

$ts = $null
foreach ($exe in @('tailscale', 'C:\Program Files\Tailscale\tailscale.exe')) {
    try {
        $out = & $exe ip -4 2>$null
        $ts = $out | Where-Object { $_ -match '^100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\.\d{1,3}\.\d{1,3}$' } | Select-Object -First 1
        if ($ts) { break }
    } catch { }
}

function Test-Listening {
    # Un listener 11435 sur 127.0.0.1 (ou toutes interfaces) : on s'appuie sur un test TCP reel.
    try {
        $c = New-Object System.Net.Sockets.TcpClient
        $iar = $c.BeginConnect('127.0.0.1', $port, $null, $null)
        $ok = $iar.AsyncWaitHandle.WaitOne(500) -and $c.Connected
        $c.Close()
        return [bool]$ok
    } catch { return $false }
}

if (Test-Listening) {
    Write-Host "Le port $port est deja en ecoute : le relais tourne deja (rien a faire)." -ForegroundColor Yellow
} else {
    # cmd /k : la fenetre reste ouverte meme si node plante (erreur visible).
    $q = '"'
    $cmdLine = "/k title $title & $q$($node.Source)$q $q$relay$q"
    Start-Process -FilePath "$env:SystemRoot\System32\cmd.exe" -ArgumentList $cmdLine -WorkingDirectory $here -WindowStyle Minimized | Out-Null
    $up = $false
    for ($i = 0; $i -lt 20; $i++) { Start-Sleep -Milliseconds 500; if (Test-Listening) { $up = $true; break } }
    if ($up) {
        Write-Host "Relais demarre (fenetre minimisee '$title')." -ForegroundColor Green
    } else {
        Write-Host "Le relais n'ecoute pas sur le port $port apres 10 s : ouvre la fenetre '$title' (barre des taches) pour voir l'erreur." -ForegroundColor Red
        exit 2
    }
}

Write-Host ''
if ($ts) {
    Write-Host "Adresse a saisir dans l'app : $($ts):$port" -ForegroundColor Cyan
    Write-Host "(ou simplement $ts avec l'option 'Passer par le relais de raisonnement (PC)')"
} else {
    Write-Host 'Tailscale introuvable : le relais n''ecoute que sur 127.0.0.1.' -ForegroundColor Yellow
}
Write-Host 'Voir le raisonnement : Voir-Raisonnement-Qwen3.cmd (a tout moment).'
Write-Host "Arret : Stop-OssmReasoningRelay.cmd (ou Ctrl+C dans la fenetre '$title')."
