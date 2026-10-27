# Affiche le journal du relais : ~80 dernieres lignes puis le direct.
# Fermer cette fenetre n'a aucun effet sur le relais ni sur l'app.
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$logDir = Join-Path $here 'logs'
$log = Join-Path $logDir 'raisonnement.log'
try { $Host.UI.RawUI.WindowTitle = 'OSSM - raisonnement Qwen3' } catch { }
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }
if (-not (Test-Path $log)) { New-Item -ItemType File -Path $log | Out-Null }
Write-Host "Journal : $log  (Ctrl+C ou fermer la fenetre pour quitter)" -ForegroundColor DarkGray
Get-Content -Path $log -Tail 80 -Wait -Encoding UTF8
