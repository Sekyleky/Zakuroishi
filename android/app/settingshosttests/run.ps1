param([string]$Sdk = '', [string]$JavaHome = 'C:/Program Files/Android/Android Studio/jbr')
$ErrorActionPreference = 'Stop'
if (!$Sdk) { $Sdk = $env:ANDROID_HOME }
if (!$Sdk) { $Sdk = Join-Path $env:USERPROFILE 'AppData/Local/Android/Sdk' }
# Also runs on Linux under pwsh (CI): no .exe suffix, ':' classpath separator, JAVA_HOME when the Windows default is absent.
$exe = if ([IO.Path]::PathSeparator -eq ';') { '.exe' } else { '' }
if (!(Test-Path "$JavaHome/bin/javac$exe") -and $env:JAVA_HOME) { $JavaHome = $env:JAVA_HOME }
$repo = (Resolve-Path "$PSScriptRoot/../../..").Path
$classes = Join-Path $repo 'build/settingshosttests/classes'
New-Item -ItemType Directory -Force $classes | Out-Null
$jar = Join-Path $Sdk 'platforms/android-37.0/android.jar'
if (!(Test-Path -LiteralPath $jar)) { throw "Android SDK platform missing: $jar" }
$cp = "$classes$([IO.Path]::PathSeparator)$jar"
$sources = @(Get-ChildItem "$repo/android/app/src/main/java" -Recurse -Filter '*.java' | ForEach-Object FullName)
$sources += "$PSScriptRoot/FrontendReferenceCheck.java"
$sources += "$PSScriptRoot/IisuConfigCheck.java"
$sources += "$PSScriptRoot/DebugSettingsCheck.java"
$sources += "$repo/android/app/tests/NativeFixture.java"
$sources += "$repo/android/app/tests/GameImportCheck.java"
$sources += "$repo/android/app/tests/ImportPlanCheck.java"
$sources += "$repo/android/app/tests/NativeLibraryCheck.java"
& "$JavaHome/bin/javac$exe" --release 11 -classpath $jar -d $classes @sources "$PSScriptRoot/GameSettingsCheck.java" "$PSScriptRoot/SettingsBridgeCheck.java" "$PSScriptRoot/DriverPackageCheck.java"
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed' }
# class name, extra argument, failure text
$checks = @(
    @('GameSettingsCheck', $null, 'Settings checks failed'),
    @('SettingsBridgeCheck', $repo, 'Java/native contract checks failed'),
    @('DriverPackageCheck', $null, 'Driver package checks failed'),
    @('FrontendReferenceCheck', $null, 'Frontend reference checks failed'),
    @('DebugSettingsCheck', $null, 'Debug settings checks failed'),
    @('GameImportCheck', $null, 'Game import checks failed'),
    @('ImportPlanCheck', $null, 'Import plan checks failed'),
    @('NativeLibraryCheck', $null, 'Native library checks failed')
)
foreach ($c in $checks) {
    & "$JavaHome/bin/java$exe" -cp $cp "io.github.zakuro.android.$($c[0])" @(if ($c[1]) { $c[1] })
    if ($LASTEXITCODE -ne 0) { throw $c[2] }
}
