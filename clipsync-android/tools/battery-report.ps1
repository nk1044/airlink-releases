# Shows how much battery ClipSync used, from Android's own battery statistics (estimated mAh).
#
# Needs: the phone plugged into this PC with USB debugging on (Settings > Additional settings > Developer options).
#
#   1. Charge the phone, unplug it from the charger, then run:   .\battery-report.ps1 -Reset
#      (connect USB just for this command, then disconnect and use the phone normally)
#   2. A few hours later (or the next day), connect again and run: .\battery-report.ps1
param([switch]$Reset)

$ErrorActionPreference = 'Stop'
$package = 'dev.clipsync'

$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
foreach ($candidate in @("$env:ANDROID_HOME\platform-tools\adb.exe", 'C:\Program Files\platform-tools\adb.exe',
                         "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe")) {
    if (-not $adb -and (Test-Path $candidate)) { $adb = $candidate }
}
if (-not $adb) { Write-Host 'adb not found. Install Android platform-tools.'; exit 1 }

$state = (& $adb get-state 2>$null)
if ($state -ne 'device') {
    Write-Host 'No phone found. Plug it in, turn on USB debugging, and allow this PC on the phone.'
    exit 1
}

if ($Reset) {
    & $adb shell dumpsys batterystats --reset | Out-Null
    Write-Host 'Battery statistics reset. Unplug the phone and use it normally for a few hours,'
    Write-Host 'then plug it in and run this script again without -Reset.'
    exit 0
}

# App UID (e.g. 10234) -> batterystats name (u0a234)
$uidLine = (& $adb shell cmd package list packages -U $package) | Select-Object -First 1
if ($uidLine -notmatch 'uid:(\d+)') { Write-Host "ClipSync ($package) isn't installed on this phone."; exit 1 }
$appId = [int]$Matches[1] % 100000
$uidName = 'u0a' + ($appId - 10000)

$dump = & $adb shell dumpsys batterystats
$lines = $dump -split "`r?`n"

$onBattery = ($lines | Select-String -Pattern 'Time on battery: (.+?) \(' | Select-Object -First 1)
$capacity = ($lines | Select-String -Pattern 'Capacity: ([\d.]+)' | Select-Object -First 1)
$computed = ($lines | Select-String -Pattern 'Computed drain: ([\d.]+)' | Select-Object -First 1)
$appLine = ($lines | Select-String -Pattern ("^\s*UID $uidName" + ':\s*([\d.]+)') | Select-Object -First 1)

Write-Host ''
Write-Host 'ClipSync battery use (Android''s estimate)'
Write-Host '------------------------------------------'
if ($onBattery) { Write-Host ('Time on battery:   ' + $onBattery.Matches[0].Groups[1].Value) }
if ($appLine) {
    $mah = [double]$appLine.Matches[0].Groups[1].Value
    Write-Host ('ClipSync used:     {0:N2} mAh' -f $mah)
    if ($capacity) {
        $cap = [double]$capacity.Matches[0].Groups[1].Value
        if ($cap -gt 0) { Write-Host ('                   {0:N3} % of the battery ({1:N0} mAh)' -f ($mah * 100 / $cap), $cap) }
    }
    if ($computed) {
        $all = [double]$computed.Matches[0].Groups[1].Value
        if ($all -gt 0) { Write-Host ('                   {0:N2} % of everything the phone used' -f ($mah * 100 / $all)) }
    }
    Write-Host ''
    Write-Host 'Breakdown:'
    Write-Host ('  ' + $appLine.Line.Trim())
} else {
    Write-Host "No battery entry for ClipSync ($uidName) yet: it has used too little to be listed,"
    Write-Host 'or the statistics were just reset. Try again after a few hours on battery.'
}
