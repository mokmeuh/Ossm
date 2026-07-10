# Choisit l'appareil adb a utiliser et l'ecrit sur la sortie standard.
# Ordre : (1) USB filaire, (2) Wi-Fi deja connecte et repondant, (3) wifi_connect.ps1.
# Sortie : la cible adb (serial USB ou "<ip>:5555"), ou RIEN si aucun appareil.
$ErrorActionPreference = 'SilentlyContinue'
$adb = 'adb'

function Responds($addr){
    if(-not $addr){ return $false }
    $r = & $adb -s $addr shell echo ok 2>$null
    return ($r -match 'ok')
}
function Devices($wifi){
    (& $adb devices) -split "`n" |
        Where-Object { $_ -match '\tdevice' } |
        ForEach-Object { ($_ -split '\t')[0].Trim() } |
        Where-Object { $_ -and ($wifi -eq ($_ -match ':')) }
}

# 1) USB (serial sans ':')
$usb = Devices $false | Select-Object -First 1
if($usb -and (Responds $usb)){ Write-Host "[adb_target] USB : $usb"; Write-Output $usb; exit 0 }

# 2) Wi-Fi deja connecte
$wifi = Devices $true | Select-Object -First 1
if($wifi -and (Responds $wifi)){ Write-Host "[adb_target] Wi-Fi : $wifi"; Write-Output $wifi; exit 0 }

# 3) tenter wifi_connect
Write-Host "[adb_target] Aucun appareil pret -> wifi_connect..."
$addr = & (Join-Path $PSScriptRoot 'wifi_connect.ps1') -Quiet | Select-Object -Last 1
if($addr -and (Responds $addr)){ Write-Host "[adb_target] Wi-Fi (reconnecte) : $addr"; Write-Output $addr; exit 0 }

Write-Host "[adb_target] AUCUN appareil adb."
exit 1
