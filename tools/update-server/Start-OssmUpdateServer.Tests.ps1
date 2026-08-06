$server = Join-Path $PSScriptRoot "Start-OssmUpdateServer.ps1"

Describe "Start-OssmUpdateServer" {
    BeforeAll {
        if (-not (Get-Command Get-NetAdapter -ErrorAction SilentlyContinue)) {
            function Get-NetAdapter { }
        }
        if (-not (Get-Command Get-NetIPAddress -ErrorAction SilentlyContinue)) {
            function Get-NetIPAddress { }
        }

        function Assert-Equal($actual, $expected, [string]$label) {
            if ($actual -ne $expected) {
                throw "$label expected '$expected' but got '$actual'."
            }
        }

        function Assert-ThrowsLike([scriptblock]$operation, [string]$pattern) {
            try {
                & $operation
            } catch {
                if ($_.Exception.Message -like $pattern) {
                    return
                }
                throw "Expected error '$pattern' but got '$($_.Exception.Message)'."
            }
            throw "Expected error '$pattern' but no error was thrown."
        }
    }

    BeforeEach {
        $apk = Join-Path $TestDrive "OSSM-Remote-v9.9.9-debug.apk"
        [IO.File]::WriteAllBytes($apk, [Text.Encoding]::UTF8.GetBytes("test apk"))

        Mock Get-NetAdapter {
            [pscustomobject]@{
                Name = "ZeroTier One [0123456789abcdef]"
                InterfaceAlias = "ZeroTier One [0123456789abcdef]"
                InterfaceDescription = "ZeroTier Virtual Port"
                Status = "Up"
                ifIndex = 42
            }
        }
        Mock Get-NetIPAddress {
            [pscustomobject]@{
                InterfaceAlias = "ZeroTier One [0123456789abcdef]"
                AddressFamily = "IPv4"
                IPAddress = "10.147.17.3"
                AddressState = "Preferred"
            }
        }
    }

    It "emits a typed manifest after validating an explicit ZeroTier binding" {
        $manifest = (& $server -ApkPath $apk -VersionCode 999 -VersionName "9.9.9" -ReleaseNotes "Test" `
            -ZeroTierInterfaceAlias "ZeroTier One [0123456789abcdef]" -ZeroTierAddress "10.147.17.3" `
            -ValidateOnly | ConvertFrom-Json)

        Assert-Equal $manifest.versionCode 999 "versionCode"
        Assert-Equal $manifest.versionName "9.9.9" "versionName"
        Assert-Equal $manifest.apkPath "/OSSM-Remote-v9.9.9-debug.apk" "apkPath"
        Assert-Equal $manifest.sha256 ((Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()) "sha256"
        Assert-Equal $manifest.releaseNotes "Test" "releaseNotes"
    }

    It "rejects an ordinary Ethernet adapter even when it owns a private address" {
        Mock Get-NetAdapter {
            [pscustomobject]@{
                Name = "Ethernet"
                InterfaceAlias = "Ethernet"
                InterfaceDescription = "Intel(R) Ethernet Controller"
                Status = "Up"
            }
        }
        Mock Get-NetIPAddress {
            [pscustomobject]@{
                InterfaceAlias = "Ethernet"
                AddressFamily = "IPv4"
                IPAddress = "192.168.1.20"
                AddressState = "Preferred"
            }
        }

        Assert-ThrowsLike {
            & $server -ApkPath $apk -VersionCode 1 -VersionName "1.0" `
                -ZeroTierInterfaceAlias "Ethernet" -ZeroTierAddress "192.168.1.20" -ValidateOnly
        } "*not a ZeroTier virtual port*"
    }

    It "rejects a requested address that is not owned by the ZeroTier adapter" {
        Assert-ThrowsLike {
            & $server -ApkPath $apk -VersionCode 1 -VersionName "1.0" `
                -ZeroTierInterfaceAlias "ZeroTier One [0123456789abcdef]" -ZeroTierAddress "10.147.17.4" `
                -ValidateOnly
        } "*does not own preferred IPv4 address*"
    }

    It "resolves a ZeroTier alias with brackets through its interface index" {
        Mock Get-NetIPAddress {
            param($InterfaceIndex, $InterfaceAlias)

            if ($PSBoundParameters.ContainsKey("InterfaceAlias")) {
                throw "Address lookup must use the adapter interface index."
            }
            if ($InterfaceIndex -ne 42) {
                throw "Expected interface index 42 but got '$InterfaceIndex'."
            }
            [pscustomobject]@{
                InterfaceAlias = "ZeroTier One [0123456789abcdef]"
                AddressFamily = "IPv4"
                IPAddress = "10.147.17.3"
                AddressState = "Preferred"
            }
        }

        $manifest = (& $server -ApkPath $apk -VersionCode 999 -VersionName "9.9.9" -ReleaseNotes "Test" `
            -ZeroTierInterfaceAlias "ZeroTier One [0123456789abcdef]" -ZeroTierAddress "10.147.17.3" `
            -ValidateOnly | ConvertFrom-Json)

        Assert-Equal $manifest.versionCode 999 "versionCode"
    }

    It "rejects a missing requested address on the ZeroTier adapter" {
        Mock Get-NetIPAddress { @() }

        Assert-ThrowsLike {
            & $server -ApkPath $apk -VersionCode 1 -VersionName "1.0" `
                -ZeroTierInterfaceAlias "ZeroTier One [0123456789abcdef]" -ZeroTierAddress "10.147.17.3" `
                -ValidateOnly
        } "*does not own preferred IPv4 address*"
    }

    It "fails clearly before serving when the APK is missing" {
        Assert-ThrowsLike {
            & $server -ApkPath (Join-Path $TestDrive "missing.apk") -VersionCode 1 -VersionName "1.0" `
                -ZeroTierInterfaceAlias "ZeroTier One [0123456789abcdef]" -ZeroTierAddress "10.147.17.3" `
                -ValidateOnly -ErrorAction Stop
        } "*APK not found*"
    }

    It "binds only the validated address and contains no discovery transport" {
        $source = [IO.File]::ReadAllText($server)

        if ($source -notmatch 'TcpListener\]::new\(\$validatedAddress,\s*\$Port\)') {
            throw "Listener does not bind to the validated address."
        }
        if ($source -match 'IPAddress\]::Any|IPAddress\.Any') {
            throw "Server contains a wildcard bind."
        }
        if ($source -match '(?i)mdns|udp|224\.0\.0\.251|5353|_ossm-update\._tcp') {
            throw "Server contains a discovery transport token."
        }
    }
}
