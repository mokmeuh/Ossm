[CmdletBinding()]
param(
    [int]$Port = 8080,
    [string]$IpAddress = "0.0.0.0",
    [string]$ApkDir,
    [string]$ReleaseNotes = "Local build"
)

$ErrorActionPreference = "Stop"

# Racine du projet = 2 niveaux au-dessus de tools/update-server
if (-not $ApkDir) {
    $projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
    $ApkDir = Join-Path $projectRoot "app\build\outputs\named-apk"
}

$buildFile = Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) "app\build.gradle.kts"

function Get-BuildValue([string]$pattern, [string]$name) {
    $match = [regex]::Match([IO.File]::ReadAllText($buildFile), $pattern)
    if (-not $match.Success) {
        throw "Could not determine $name from $buildFile"
    }
    return $match.Groups[1].Value
}

function Get-LatestApk {
    $apks = @(Get-ChildItem -LiteralPath $ApkDir -Filter "*.apk" -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending)
    if ($apks.Count -eq 0) { return $null }
    return $apks[0]
}

function Get-LatestManifest {
    $apk = Get-LatestApk
    if (-not $apk) { return $null }
    $sha256 = (Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    $apkUrlPath = "/" + [Uri]::EscapeDataString($apk.Name)
    $versionCode = [int](Get-BuildValue 'versionCode\s*=\s*(\d+)' "VersionCode")
    $versionName = Get-BuildValue 'versionName\s*=\s*"([^"]+)"' "VersionName"
    return [ordered]@{
        versionCode = $versionCode
        versionName = $versionName
        apkPath = $apkUrlPath
        sha256 = $sha256
        releaseNotes = $ReleaseNotes
        apkLastWrite = $apk.LastWriteTime.ToString("yyyy-MM-dd HH:mm:ss")
    }
}

function Send-Response(
    [Net.Sockets.TcpClient]$client,
    [int]$status,
    [string]$reason,
    [string]$contentType,
    [byte[]]$body
) {
    $stream = $client.GetStream()
    $headers = "HTTP/1.1 $status $reason`r`nContent-Type: $contentType`r`nContent-Length: $($body.Length)`r`nConnection: close`r`n`r`n"
    $headerBytes = [Text.Encoding]::ASCII.GetBytes($headers)
    $stream.Write($headerBytes, 0, $headerBytes.Length)
    $stream.Write($body, 0, $body.Length)
    $stream.Flush()
}

if (-not (Test-Path -LiteralPath $ApkDir -PathType Container)) {
    throw "APK directory not found: $ApkDir`nBuild first with: ./gradlew assembleNamedDebugApk"
}

$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Parse($IpAddress), $Port)
$listener.Start()
Write-Host ""
Write-Host "=== Serveur d'update OSSM (mode simple) ==="
Write-Host "Dossier APK : $ApkDir"
Write-Host "Adresse     : http://$IpAddress`:$Port/"
Write-Host "Manifest    : http://$IpAddress`:$Port/update.json"
Write-Host "Le dernier APK build est servi automatiquement."
Write-Host "Appuyez sur Ctrl+C pour arrêter."
Write-Host ""

try {
    while ($true) {
        $client = $listener.AcceptTcpClient()
        try {
            $client.ReceiveTimeout = 5000
            $stream = $client.GetStream()
            $reader = [IO.StreamReader]::new($stream, [Text.Encoding]::ASCII, $false, 4096, $true)
            try {
                $requestLine = $reader.ReadLine()
                while ($reader.ReadLine()) { }
            } finally {
                $reader.Dispose()
            }
            $request = $requestLine -split ' '
            if ($request.Count -lt 2 -or $request[0] -ne "GET") {
                Send-Response $client 405 "Method Not Allowed" "text/plain; charset=utf-8" ([Text.Encoding]::UTF8.GetBytes("Only GET is supported.`n"))
                continue
            }

            $latest = Get-LatestApk
            if (-not $latest) {
                Send-Response $client 404 "No APK" "text/plain; charset=utf-8" ([Text.Encoding]::UTF8.GetBytes("No APK found in $ApkDir. Run a build first.`n"))
                continue
            }

            $apkUrlPath = "/" + [Uri]::EscapeDataString($latest.Name)

            if ($request[1] -eq "/update.json") {
                $manifest = Get-LatestManifest
                $json = $manifest | ConvertTo-Json -Compress
                Send-Response $client 200 "OK" "application/json; charset=utf-8" ([Text.Encoding]::UTF8.GetBytes($json))
            } elseif ($request[1] -eq $apkUrlPath) {
                Send-Response $client 200 "OK" "application/vnd.android.package-archive" ([IO.File]::ReadAllBytes($latest.FullName))
            } else {
                Send-Response $client 404 "Not Found" "text/plain; charset=utf-8" ([Text.Encoding]::UTF8.GetBytes("Not found. Endpoints: /update.json and $apkUrlPath`n"))
            }
        } catch {
            Write-Warning "Request failed: $($_.Exception.Message)"
        } finally {
            $client.Dispose()
        }
    }
} finally {
    $listener.Stop()
}
