param([string]$OutputDirectory = '', [string]$Image = '')
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$repo = (Resolve-Path "$PSScriptRoot/../..").Path
if (!$OutputDirectory) { $OutputDirectory = Join-Path $repo 'build/android' }
$out = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force $out | Out-Null
$run = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8)
$log = Join-Path $out "build-$run.log"
function Invoke-Docker([string[]]$Arguments) {
    & docker @Arguments 2>&1 | Tee-Object -FilePath $log -Append | Out-Host
    $code = $LASTEXITCODE
    if ($code -ne 0) { exit $code }
}
if (!$Image) {
    $hash = (Get-FileHash "$PSScriptRoot/Containerfile" -Algorithm SHA256).Hash.ToLower().Substring(0,12)
    $Image = "zakuro-android-toolchain:$hash"
    Invoke-Docker @('build','--platform','linux/amd64','-f',"$PSScriptRoot/Containerfile",'-t',$Image,$PSScriptRoot)
}
$apk = "zakuro-android-test-$run.apk"
Invoke-Docker @('run','--rm','--platform','linux/amd64',
    '--mount',"type=bind,source=$repo,target=/src,readonly",
    '--mount',"type=bind,source=$out,target=/out",
    '--mount','type=volume,source=zakuro-android-work,target=/work',
    '--mount','type=volume,source=zakuro-android-test-key,target=/keys',
    '-e','HOME=/work','-e','CARGO_HOME=/work/cargo','-e','CARGO_TARGET_DIR=/work/target',
    $Image,'bash','/src/android/scripts/inside.sh',$apk)
Write-Host "APK: $(Join-Path $out $apk)"
Write-Host "Log: $log"
