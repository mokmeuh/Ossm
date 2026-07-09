# Incremente versionCode (+1) et le patch de versionName (x.y.Z -> x.y.Z+1)
# dans app/build.gradle.kts, puis affiche la nouvelle versionName.
$ErrorActionPreference = 'Stop'
$f = Join-Path $PSScriptRoot 'app/build.gradle.kts'
$c = Get-Content $f -Raw

$c = [regex]::Replace($c, 'versionCode\s*=\s*(\d+)', {
    param($m) 'versionCode = ' + ([int]$m.Groups[1].Value + 1)
})
$c = [regex]::Replace($c, 'versionName\s*=\s*"(\d+)\.(\d+)\.(\d+)"', {
    param($m) 'versionName = "' + $m.Groups[1].Value + '.' + $m.Groups[2].Value + '.' + ([int]$m.Groups[3].Value + 1) + '"'
})

Set-Content -Path $f -Value $c -NoNewline
$v = [regex]::Match($c, 'versionName\s*=\s*"(.*?)"').Groups[1].Value
Write-Output $v
