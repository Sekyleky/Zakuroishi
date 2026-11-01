param([string]$Sdk = "$env:LOCALAPPDATA/Android/Sdk", [string]$Serial, [int]$Port = 5037,
      [string]$JavaHome = 'C:/Program Files/Android/Android Studio/jbr')
$ErrorActionPreference = 'Stop'
if (!$Serial) { throw 'Select an explicit test-device serial' }
$repo = (Resolve-Path "$PSScriptRoot/../../..").Path
$work = Join-Path $repo 'build/frontend-devicecheck'
New-Item -ItemType Directory -Force $work | Out-Null
& "$JavaHome/bin/jar.exe" --create --file "$work/checks.jar" -C "$repo/build/settingshosttests/classes" .
if ($LASTEXITCODE) { throw 'Compile settingshosttests/run.ps1 first' }
& "$JavaHome/bin/java.exe" -cp "$Sdk/build-tools/36.0.0/lib/d8.jar" com.android.tools.r8.D8 --min-api 30 --lib "$Sdk/platforms/android-37.0/android.jar" --output $work "$work/checks.jar"
if ($LASTEXITCODE) { throw 'Cannot dex frontend checks' }
$adb = "$Sdk/platform-tools/adb.exe"
$remote = '/data/local/tmp/zakuro-frontend-check.dex'
try {
    & $adb -P $Port -s $Serial push "$work/classes.dex" $remote
    if ($LASTEXITCODE) { throw 'Cannot transfer frontend checks' }
    & $adb -P $Port -s $Serial shell "CLASSPATH=$remote app_process /system/bin io.github.zakuro.android.IisuConfigCheck"
    if ($LASTEXITCODE) { throw 'iiSU/Cocoon checks failed' }
    & $adb -P $Port -s $Serial shell "CLASSPATH=$remote app_process /system/bin io.github.zakuro.android.FrontendReferenceCheck /data/local/tmp"
    if ($LASTEXITCODE) { throw 'Android reference checks failed' }
} finally { & $adb -P $Port -s $Serial shell rm -f $remote }
