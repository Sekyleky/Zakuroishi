param(
    [Parameter(Mandatory)][string]$Serial,
    [string]$Package = 'io.github.zakuro.android',
    [string]$ProcessName = '',
    [ValidateRange(1,3600)][int]$Seconds = 60,
    [ValidateRange(0,3600)][int]$WarmupSeconds = 30,
    [string]$OutputDirectory = './benchmark-captures'
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
if (!$ProcessName) {
    $ProcessName = $Package
    if ($Package -eq 'io.github.zakuro.android') { $ProcessName += ':game' }
}
$run = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8)
$out = Join-Path ([IO.Path]::GetFullPath($OutputDirectory)) $run
New-Item -ItemType Directory -Force $out | Out-Null
function Save-Adb([string]$Name, [string[]]$Arguments) {
    & adb -s $Serial @Arguments 2>&1 | Out-File -Encoding utf8 (Join-Path $out $Name)
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $($Arguments -join ' ') (see $out)" }
}
Save-Adb 'state.txt' @('get-state')
Save-Adb 'device.txt' @('shell','getprop')
Save-Adb 'package.txt' @('shell','dumpsys','package',$Package)
Write-Host "Leave the game running at the agreed scene. Warmup: $WarmupSeconds s; capture: $Seconds s."
Start-Sleep -Seconds $WarmupSeconds
Save-Adb 'pid-start.txt' @('shell','pidof',$ProcessName)
Save-Adb 'battery-start.txt' @('shell','dumpsys','battery')
Save-Adb 'thermal-start.txt' @('shell','dumpsys','thermalservice')
Save-Adb 'gfx-reset.txt' @('shell','dumpsys','gfxinfo',$Package,'reset')
$start = [DateTimeOffset]::UtcNow
$timer = [Diagnostics.Stopwatch]::StartNew()
Start-Sleep -Seconds $Seconds
$timer.Stop()
$end = [DateTimeOffset]::UtcNow
Save-Adb 'pid-end.txt' @('shell','pidof',$ProcessName)
Save-Adb 'gfxinfo.txt' @('shell','dumpsys','gfxinfo',$Package,'framestats')
Save-Adb 'memory.txt' @('shell','dumpsys','meminfo',$ProcessName)
Save-Adb 'battery-end.txt' @('shell','dumpsys','battery')
Save-Adb 'thermal-end.txt' @('shell','dumpsys','thermalservice')
# Keep the ring buffer intact; timestamps bound the intended measurement window.
Save-Adb 'logcat.txt' @('logcat','-d','-v','epoch')
@{
    serial=$Serial; package=$Package; process=$ProcessName; warmupSeconds=$WarmupSeconds
    requestedSeconds=$Seconds; elapsedSeconds=$timer.Elapsed.TotalSeconds
    startUtc=$start.ToString('o'); endUtc=$end.ToString('o')
} | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $out 'capture.json')
Write-Host "Capture: $out"
