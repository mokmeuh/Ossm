# Local APK Update Server

## Overview
- PowerShell tooling serves the named Android debug APK and an `update.json` manifest consumed by `app/.../updater/`.
- The safe server is `Start-OssmUpdateServer.ps1`: bind only to the validated ZeroTier interface/address, normally on port 8080.

## Commands
```powershell
Invoke-Pester .\Start-OssmUpdateServer.Tests.ps1
```
- Build the input APK from the repository root with `./gradlew assembleNamedDebugApk` before serving.
- The manifest carries version code/name, APK path, SHA-256 and release notes.

## Safety
- Do not replace the ZeroTier-bound server with a wildcard bind. `Start-LatestApkServer.ps1` defaults to `0.0.0.0` and is not the normal secure workflow.
- Preserve SHA-256 and Android signing verification in the app updater.
- Do not add mDNS/NSD discovery or silent installation; the current policy uses an explicit persisted manifest URL.
