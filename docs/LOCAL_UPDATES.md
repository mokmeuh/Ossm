# ZeroTier Debug APK Updates

`start_update_server.bat` builds the current named debug APK and serves it only on an explicit ZeroTier IPv4 address. It does not advertise through mDNS, bind to an ordinary LAN address, or create a router port-forwarding rule.

## Prerequisites

- The PC and phone are joined and authorized on the same real ZeroTier network.
- Windows shows an active ZeroTier virtual-port adapter with a preferred IPv4 address.
- The phone already has an OSSM version with the update URL field. Older versions may wait for their former discovery timeout before using the saved manual URL; version `1.28.23` uses the saved URL directly.

On the Windows PC, identify the actual adapter and address. Do not copy the placeholders below as an endpoint:

```powershell
Get-NetAdapter | Where-Object { $_.InterfaceDescription -match "ZeroTier" }
Get-NetIPAddress -InterfaceAlias "<ZeroTier interface alias>" -AddressFamily IPv4 |
    Where-Object { $_.AddressState -eq "Preferred" }
```

Private address ranges are not proof of ZeroTier membership. The server checks the selected adapter metadata and the address actually assigned to it before opening a port.

## Start The Server

Run the launcher from the repository root, passing the exact adapter alias and IPv4 address reported above:

```bat
start_update_server.bat "<ZeroTier interface alias>" "<ZeroTier IPv4 address>"
```

The launcher builds:

```text
app\build\outputs\named-apk\OSSM-Remote-v<version>-debug.apk
```

It then validates the selected ZeroTier binding and prints the only usable manifest URL:

```text
Manifest: http://<actual-ZeroTier-IPv4>:8080/update.json
```

There is no valid update URL until the validation succeeds and the listener starts. If the adapter is down, is not a ZeroTier virtual port, or does not own the requested preferred IPv4 address, the server exits without listening.

To serve a prebuilt APK or override metadata, invoke PowerShell directly while keeping the same required binding arguments:

```powershell
.\tools\update-server\Start-OssmUpdateServer.ps1 `
    -ZeroTierInterfaceAlias "<ZeroTier interface alias>" `
    -ZeroTierAddress "<ZeroTier IPv4 address>" `
    -ApkPath C:\builds\OSSM-Remote-v1.28.23-debug.apk `
    -VersionCode 115 `
    -VersionName 1.28.23 `
    -ReleaseNotes "Test build"
```

The server keeps port `8080` by default; pass `-Port` to change it. It exposes only `/update.json` and the APK named by the manifest's `apkPath`.

## Upgrade A Phone

1. Keep ZeroTier enabled on both the PC and the phone.
2. Copy the URL printed by the running server.
3. In the installed OSSM app, open Profiles, save that URL in the update field, then use Check for updates, Download, and Install.
4. Android may require permission to install packages from this app. Confirm the system installer prompt.
5. After installing `1.28.23`, the saved URL remains available and future checks no longer use service discovery.

## Verify The Boundary

On the PC, confirm the listener owns the selected virtual address rather than a wildcard or LAN address:

```powershell
Get-NetTCPConnection -LocalPort 8080 -State Listen |
    Select-Object LocalAddress, LocalPort, OwningProcess
```

From a phone joined to the same ZeroTier network, fetch the printed `/update.json` URL and verify that the APK SHA-256 matches the manifest. Disable ZeroTier on the phone and retry the exact URL: it must fail. An ordinary LAN address on the PC must not accept the update port.

## Security Boundary

ZeroTier encrypts the overlay path and controls network membership. The server additionally binds only to the selected validated ZeroTier address. The Android client deliberately uses only the saved explicit URL; URL syntax, generic VPN state, and private address ranges cannot authenticate a ZeroTier peer.

The HTTP manifest is not TLS-authenticated. SHA-256 verifies the downloaded APK against the manifest, while Android package-signing compatibility prevents a differently signed APK from replacing an installed OSSM package. Keep ZeroTier membership restricted to trusted devices and do not treat this workflow as a substitute for ZeroTier authorization or flow rules.

## Automated Checks

Run the PowerShell tests on Windows:

```powershell
Invoke-Pester .\tools\update-server\Start-OssmUpdateServer.Tests.ps1
```

They verify explicit adapter/address validation, rejection of ordinary adapters, no wildcard listener, and removal of mDNS/UDP discovery.
