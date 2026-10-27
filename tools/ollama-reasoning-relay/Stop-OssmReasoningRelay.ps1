# Arrete uniquement le relais OSSM (processus node dont la ligne de commande contient
# relay.js de CE dossier) et sa fenetre cmd. Ne touche a aucun autre processus.
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$relay = (Join-Path $here 'relay.js')
$all = @(Get-CimInstance Win32_Process)
$procs = @($all | Where-Object {
    $_.Name -eq 'node.exe' -and $_.CommandLine -and $_.CommandLine.IndexOf($relay, [StringComparison]::OrdinalIgnoreCase) -ge 0
})
if ($procs.Count -eq 0) { Write-Host 'Aucun relais OSSM en cours.' -ForegroundColor Yellow; exit 0 }
foreach ($p in $procs) {
    $parent = $all | Where-Object { $_.ProcessId -eq $p.ParentProcessId -and $_.Name -eq 'cmd.exe' -and $_.CommandLine -like '*relais raisonnement*' }
    Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
    if ($parent) { Stop-Process -Id $parent.ProcessId -Force -ErrorAction SilentlyContinue }
    Write-Host "Relais arrete (PID $($p.ProcessId))." -ForegroundColor Green
}
