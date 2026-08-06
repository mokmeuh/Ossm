[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateNotNullOrEmpty()]
    [string]$ZeroTierInterfaceAlias,
    [Parameter(Mandatory)]
    [Net.IPAddress]$ZeroTierAddress,
    [string]$ApkPath,
    [int]$VersionCode = 0,
    [string]$VersionName,
    [string]$ReleaseNotes = "Local debug build",
    [ValidateRange(1024, 65535)]
    [int]$Port = 8080,
    [switch]$ValidateOnly
)

$ErrorActionPreference = "Stop"

function Resolve-ZeroTierBinding(
    [string]$interfaceAlias,
    [Net.IPAddress]$address
) {
    if ($address.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork) {
        throw "ZeroTier address must be an IPv4 address."
    }

    $octets = $address.GetAddressBytes()
    if ($octets[0] -eq 0 -or
        $octets[0] -eq 127 -or
        ($octets[0] -eq 169 -and $octets[1] -eq 254) -or
        ($octets[0] -ge 224 -and $octets[0] -le 239)) {
        throw "ZeroTier address must be a unicast, non-loopback, non-link-local IPv4 address."
    }

    $adapters = @(Get-NetAdapter -ErrorAction Stop | Where-Object {
        $_.Name -eq $interfaceAlias -or $_.InterfaceAlias -eq $interfaceAlias
    })
    if ($adapters.Count -ne 1) {
        throw "ZeroTier adapter '$interfaceAlias' was not found."
    }

    $adapter = $adapters[0]
    if ($adapter.Status -ne "Up") {
        throw "ZeroTier adapter '$interfaceAlias' is not Up."
    }

    $adapterMetadata = @($adapter.InterfaceDescription, $adapter.DriverDescription) -join " "
    if ($adapterMetadata -notmatch '(?i)\bZeroTier\b.*\bVirtual\s+Port\b') {
        throw "Adapter '$interfaceAlias' is not a ZeroTier virtual port."
    }

    $requestedAddress = $address.IPAddressToString
    $ownedAddress = @(Get-NetIPAddress -InterfaceIndex $adapter.ifIndex -AddressFamily IPv4 -ErrorAction Stop |
        Where-Object {
            $_.AddressState -eq "Preferred" -and $_.IPAddress -eq $requestedAddress
        })
    if ($ownedAddress.Count -eq 0) {
        throw "ZeroTier adapter '$interfaceAlias' does not own preferred IPv4 address '$requestedAddress'."
    }

    return $address
}

$validatedAddress = Resolve-ZeroTierBinding $ZeroTierInterfaceAlias $ZeroTierAddress
$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$buildFile = Join-Path $projectRoot "app\build.gradle.kts"

function Get-BuildValue([string]$pattern, [string]$name) {
    $match = [regex]::Match([IO.File]::ReadAllText($buildFile), $pattern)
    if (-not $match.Success) {
        throw "Could not determine $name from $buildFile. Pass -$name explicitly."
    }
    return $match.Groups[1].Value
}

if ($VersionCode -eq 0) {
    $VersionCode = [int](Get-BuildValue 'versionCode\s*=\s*(\d+)' "VersionCode")
}
if ([string]::IsNullOrWhiteSpace($VersionName)) {
    $VersionName = Get-BuildValue 'versionName\s*=\s*"([^"]+)"' "VersionName"
}
if ([string]::IsNullOrWhiteSpace($ApkPath)) {
    $ApkPath = Join-Path $projectRoot "app\build\outputs\named-apk\OSSM-Remote-v$VersionName-debug.apk"
}

if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) {
    throw "APK not found: $ApkPath`nBuild it first with: .\gradlew.bat assembleNamedDebugApk"
}
$apk = Get-Item -LiteralPath $ApkPath -ErrorAction Stop

$apkName = $apk.Name
$apkUrlPath = "/" + [Uri]::EscapeDataString($apkName)
$sha256 = (Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
$manifest = [ordered]@{
    versionCode = $VersionCode
    versionName = $VersionName
    apkPath = $apkUrlPath
    sha256 = $sha256
    releaseNotes = $ReleaseNotes
}
$manifestJson = $manifest | ConvertTo-Json -Compress

if ($ValidateOnly) {
    $manifestJson
    return
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

$bindingAddressText = $validatedAddress.IPAddressToString
$listener = [Net.Sockets.TcpListener]::new($validatedAddress, $Port)
$listener.Start()
Write-Host "Serving $apkName on ZeroTier interface '$ZeroTierInterfaceAlias' at http://$bindingAddressText`:$Port/"
Write-Host "Manifest: http://$bindingAddressText`:$Port/update.json"
Write-Host "Press Ctrl+C to stop."

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
            } elseif ($request[1] -eq "/update.json") {
                Send-Response $client 200 "OK" "application/json; charset=utf-8" ([Text.Encoding]::UTF8.GetBytes($manifestJson))
            } elseif ($request[1] -eq $apkUrlPath) {
                Send-Response $client 200 "OK" "application/vnd.android.package-archive" ([IO.File]::ReadAllBytes($apk.FullName))
            } else {
                Send-Response $client 404 "Not Found" "text/plain; charset=utf-8" ([Text.Encoding]::UTF8.GetBytes("Not found.`n"))
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
