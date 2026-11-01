param([string]$Sdk = '', [string]$JavaHome = 'C:/Program Files/Android/Android Studio/jbr')
$ErrorActionPreference = 'Stop'
if (!$Sdk) { $Sdk = $env:ANDROID_HOME }
if (!$Sdk) { $Sdk = Join-Path $env:USERPROFILE 'AppData/Local/Android/Sdk' }
$repo = (Resolve-Path "$PSScriptRoot/../../..").Path
$classes = Join-Path $repo 'build/settingshosttests/classes'
New-Item -ItemType Directory -Force $classes | Out-Null
$jar = Join-Path $Sdk 'platforms/android-37.0/android.jar'
if (!(Test-Path -LiteralPath $jar)) { throw "Android SDK platform missing: $jar" }
$sources = @(Get-ChildItem "$repo/android/app/src/main/java" -Recurse -Filter '*.java' | ForEach-Object FullName)
$sources += "$PSScriptRoot/FrontendReferenceCheck.java"
$sources += "$PSScriptRoot/IisuConfigCheck.java"
$sources += "$repo/android/app/tests/NativeFixture.java"
& "$JavaHome/bin/javac.exe" --release 11 -classpath $jar -d $classes @sources "$PSScriptRoot/GameSettingsCheck.java" "$PSScriptRoot/SettingsBridgeCheck.java" "$PSScriptRoot/DriverPackageCheck.java"
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed' }
& "$JavaHome/bin/java.exe" -cp "$classes;$jar" io.github.zakuro.android.GameSettingsCheck
if ($LASTEXITCODE -ne 0) { throw 'Settings checks failed' }


& "$JavaHome/bin/java.exe" -cp "$classes;$jar" io.github.zakuro.android.SettingsBridgeCheck $repo
if ($LASTEXITCODE -ne 0) { throw 'Java/native contract checks failed' }
& "$JavaHome/bin/java.exe" -cp "$classes;$jar" io.github.zakuro.android.DriverPackageCheck
if ($LASTEXITCODE -ne 0) { throw 'Driver package checks failed' }
& "$JavaHome/bin/java.exe" -cp "$classes;$jar" io.github.zakuro.android.FrontendReferenceCheck
if ($LASTEXITCODE -ne 0) { throw 'Frontend reference checks failed' }
