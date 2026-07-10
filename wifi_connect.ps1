# Reconnecte adb en Wi-Fi.
# 1) essaie la derniere IP connue (wifi_adb_ip.txt, defaut 192.168.1.121)
# 2) si echec ET qu'un appareil USB est present : detecte l'IP wlan0 du telephone,
#    bascule adb en tcpip 5555, reconnecte, et memorise l'IP (gere le DHCP dynamique).
# En cas de succes : ecrit l'adresse "<ip>:5555" sur la sortie standard (Write-Output).
# Les messages humains passent par Write-Host (n'encombrent pas la capture).
param([switch]$Quiet)
$ErrorActionPreference = 'SilentlyContinue'
$adb = 'adb'
$ipFile = Join-Path $PSScriptRoot 'wifi_adb_ip.txt'

function Log($m){ if(-not $Quiet){ Write-Host $m } }
function Responds($addr){
    if(-not $addr){ return $false }
    $r = & $adb -s $addr shell echo ok 2>$null
    return ($r -match 'ok')
}

# --- 1) derniere IP connue ---
$lastIp = ''
if(Test-Path $ipFile){ $lastIp = (Get-Content $ipFile -Raw).Trim() }
if(-not $lastIp){ $lastIp = '192.168.1.121' }
$addr = "${lastIp}:5555"
& $adb connect $addr | Out-Null
Start-Sleep -Milliseconds 600
if(Responds $addr){
    Log "[wifi_connect] Connecte (IP memorisee) : $addr"
    $lastIp | Out-File -Encoding ASCII $ipFile
    Write-Output $addr
    exit 0
}

# --- 2) bootstrap via USB ---
$usb = (& $adb devices) -split "`n" |
        Where-Object { $_ -match '\tdevice' -and $_ -notmatch ':' } |
        ForEach-Object { ($_ -split '\t')[0].Trim() } |
        Where-Object { $_ } |
        Select-Object -First 1
if($usb){
    Log "[wifi_connect] USB detecte ($usb) -> detection de l'IP Wi-Fi du telephone..."
    $wlan = (& $adb -s $usb shell ip -f inet addr show wlan0 2>$null) | Out-String
    $ip = ([regex]::Match($wlan,'inet (\d+\.\d+\.\d+\.\d+)')).Groups[1].Value
    if(-not $ip){
        $rt = (& $adb -s $usb shell ip route 2>$null) | Out-String
        $ip = ([regex]::Match($rt,'src (\d+\.\d+\.\d+\.\d+)')).Groups[1].Value
    }
    if($ip){
        Log "[wifi_connect] IP = $ip -> passage en tcpip 5555..."
        & $adb -s $usb tcpip 5555 | Out-Null
        Start-Sleep -Seconds 2
        $addr = "${ip}:5555"
        & $adb connect $addr | Out-Null
        Start-Sleep -Milliseconds 800
        if(Responds $addr){
            $ip | Out-File -Encoding ASCII $ipFile
            Log "[wifi_connect] Connecte via USB->Wi-Fi : $addr (IP memorisee)"
            Write-Output $addr
            exit 0
        }
        Log "[wifi_connect] Echec de la connexion Wi-Fi apres tcpip (IP=$ip)."
    } else {
        Log "[wifi_connect] Impossible de detecter l'IP Wi-Fi du telephone (Wi-Fi active sur le tel ?)."
    }
    exit 1
}

Log "[wifi_connect] Aucun appareil Wi-Fi joignable et aucun USB. Branche le cable UNE fois pour amorcer."
exit 1
