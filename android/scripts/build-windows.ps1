param(
    [string]$Sdk = '',
    [string]$JavaHome = '',
    [string]$Ndk = '',
    [string]$OutputDirectory = '',
    [string]$BuildTools = '36.0.0',
    [string]$Platform = 'android-37.0'
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$repo = (Resolve-Path "$PSScriptRoot/../..").Path
if (!$Sdk) { $Sdk = $env:ANDROID_HOME }
if (!$Sdk) { $Sdk = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'Android/Sdk' }
if (!$JavaHome) { $JavaHome = 'C:/Program Files/Android/Android Studio/jbr' }
if (!(Test-Path "$JavaHome/bin/javac.exe")) { $JavaHome = $env:JAVA_HOME }
if (!$Ndk) { $Ndk = Join-Path $repo 'build/toolchain/android-ndk-r27c' }
if (!$OutputDirectory) { $OutputDirectory = Join-Path $repo 'build/android-windows' }
$out = [IO.Path]::GetFullPath($OutputDirectory)
$ndkBin = Join-Path $Ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin'
$tools = Join-Path $Sdk "build-tools/$BuildTools"
$androidJar = Join-Path $Sdk "platforms/$Platform/android.jar"
foreach ($file in @("$ndkBin/aarch64-linux-android30-clang.cmd", "$ndkBin/llvm-strip.exe",
    "$JavaHome/bin/javac.exe", "$JavaHome/bin/jar.exe", "$tools/aapt2.exe",
    "$tools/aapt.exe", "$tools/d8.bat", "$tools/zipalign.exe", "$tools/apksigner.bat", $androidJar)) {
    if (!(Test-Path -LiteralPath $file)) { throw "Missing tool: $file" }
}
$run = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8)
$work = Join-Path $out "work-$run"
New-Item -ItemType Directory -Force "$work/classes", "$work/dex", "$work/lib/arm64-v8a" | Out-Null
$log = Join-Path $out "build-$run.log"
$apk = Join-Path $out "zakuro-android-test-$run.apk"
function Invoke-Tool([string]$Exe, [string[]]$Arguments) {
    & $Exe @Arguments 2>&1 | Tee-Object -FilePath $log -Append | Out-Host
    if ($LASTEXITCODE -ne 0) {
        $failure = [Exception]::new("$Exe failed with exit $LASTEXITCODE; log: $log")
        $failure.Data['ExitCode'] = $LASTEXITCODE
        throw $failure
    }
}
$vars = @('JAVA_HOME','PATH','CARGO_TARGET_DIR','CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER',
    'CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS','CC_aarch64_linux_android','AR_aarch64_linux_android')
$previous = @{}
foreach ($name in $vars) { $previous[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
$oldLocation = Get-Location
$buildLock = $null
try {
    Set-Location $repo
    $env:JAVA_HOME = $JavaHome
    $env:PATH = "$JavaHome/bin;$ndkBin;$env:PATH"
    # GNU dlltool does not quote its temporary prefix when the target path has spaces.
    $env:CARGO_TARGET_DIR = Join-Path ([IO.Path]::GetTempPath()) 'zakuro-android-target'
    if ($env:CARGO_TARGET_DIR -match '\s') { throw 'Windows GNU build requires a TEMP path without spaces' }
    Write-Host "Cargo cache: $env:CARGO_TARGET_DIR"
    New-Item -ItemType Directory -Force $env:CARGO_TARGET_DIR | Out-Null
    try {
        $buildLock = [IO.FileStream]::new((Join-Path $env:CARGO_TARGET_DIR '.build.lock'),
            [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    } catch [IO.IOException] {
        throw "Cannot acquire Windows build lock at $env:CARGO_TARGET_DIR/.build.lock; another build may be running."
    }
    $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = "$ndkBin/aarch64-linux-android30-clang.cmd"
    $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS = '-C link-arg=-Wl,-z,max-page-size=16384'
    $env:CC_aarch64_linux_android = $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER
    $env:AR_aarch64_linux_android = "$ndkBin/llvm-ar.exe"
    Invoke-Tool 'rustup' @('target','add','--toolchain','1.95.0','aarch64-linux-android')
    Invoke-Tool 'cargo' @('+1.95.0','build','--release','--locked','--target','aarch64-linux-android',
        '-p','zakuro-android','--lib','--features','zakuro-gpu/vulkan')
    Invoke-Tool "$ndkBin/llvm-strip.exe" @('-o',"$work/lib/arm64-v8a/libzakuro_android.so",
        "$env:CARGO_TARGET_DIR/aarch64-linux-android/release/libzakuro_android.so")
    $manifest = Join-Path $repo 'android/app/src/main/AndroidManifest.xml'
    $sources = @(Get-ChildItem "$repo/android/app/src/main/java" -Filter '*.java' -Recurse | Sort-Object FullName | ForEach-Object FullName)
    if (!$sources.Count) { throw 'No Java sources' }
    Invoke-Tool "$tools/aapt2.exe" @('link','-o',"$work/unsigned.apk",'--manifest',$manifest,'-I',$androidJar)
    Invoke-Tool "$JavaHome/bin/javac.exe" (@('-nowarn','--release','11','-classpath',$androidJar,'-d',"$work/classes") + $sources)
    $classes = @(Get-ChildItem "$work/classes" -Filter '*.class' -Recurse | ForEach-Object FullName)
    Invoke-Tool "$tools/d8.bat" (@('--min-api','30','--lib',$androidJar,'--output',"$work/dex") + $classes)
    Invoke-Tool "$JavaHome/bin/jar.exe" @('--update','--file',"$work/unsigned.apk",'--no-manifest',
        '--no-compress','-C',"$work/dex",'classes.dex','-C',$work,'lib')
    Invoke-Tool "$tools/zipalign.exe" @('-P','16','-f','4',"$work/unsigned.apk","$work/aligned.apk")
    # The Windows fallback has its own local test identity; no Docker key is exported.
    $private = Join-Path $out 'private-signing'
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
    if (!(Test-Path $private)) {
        New-Item -ItemType Directory $private | Out-Null
        $acl = [Security.AccessControl.DirectorySecurity]::new()
        $acl.SetOwner($sid)
        $acl.SetAccessRuleProtection($true,$false)
        $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow'))
        Set-Acl -LiteralPath $private -AclObject $acl
    } else {
        $acl = Get-Acl -LiteralPath $private
        $otherUsers = @($acl.Access | Where-Object {
            $_.AccessControlType -eq 'Allow' -and
            $_.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -ne $sid.Value
        })
        if (!$acl.AreAccessRulesProtected -or $otherUsers.Count) { throw 'Existing signing directory is not private to the current user' }
    }
    $key = Join-Path $private 'test.p12'
    if (!(Test-Path $key)) {
        Invoke-Tool "$JavaHome/bin/keytool.exe" @('-genkeypair','-storetype','PKCS12','-keystore',$key,
            '-storepass','android','-keypass','android','-alias','zakuro-android-test','-keyalg','RSA',
            '-keysize','4096','-validity','10000','-dname','CN=Zakuro Android Windows Test')
    }
    Invoke-Tool "$tools/apksigner.bat" @('sign','--ks',$key,'--ks-pass','pass:android',
        '--ks-key-alias','zakuro-android-test','--out',$apk,"$work/aligned.apk")
    Invoke-Tool "$tools/apksigner.bat" @('verify','--verbose','--print-certs',$apk)
    Invoke-Tool "$tools/zipalign.exe" @('-c','-P','16','4',$apk)
    $badging = & "$tools/aapt.exe" dump badging $apk
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect APK' }
    $badging | Add-Content $log
    if (!($badging -match "package: name='io.github.zakuro.android'") -or
        !($badging -match "launchable-activity: name='io.github.zakuro.android.Launcher'")) { throw 'APK identity mismatch' }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($apk)
    try {
        $entries = @($archive.Entries | ForEach-Object FullName)
        if ('lib/arm64-v8a/libzakuro_android.so' -notin $entries) { throw 'Native library missing' }
        if ($entries -match '^assets/') { throw 'Unexpected bundled assets' }
    } finally { $archive.Dispose() }
    $hash = (Get-FileHash $apk -Algorithm SHA256).Hash.ToLower()
    "$hash  $([IO.Path]::GetFileName($apk))" | Set-Content -Encoding ascii "$apk.sha256"
    Write-Host "APK: $apk"
    Write-Host "Log: $log"
} catch {
    Write-Host $_ -ForegroundColor Red
    $code = $_.Exception.Data['ExitCode']
    if (!$code) { $code = 1 }
    exit $code
} finally {
    if ($buildLock) { $buildLock.Dispose() }
    Set-Location $oldLocation
    foreach ($name in $vars) { [Environment]::SetEnvironmentVariable($name,$previous[$name],'Process') }
}
