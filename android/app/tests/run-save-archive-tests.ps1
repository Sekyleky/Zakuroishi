param([string]$JdkHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$javac = if ($JdkHome) { Join-Path $JdkHome 'bin/javac.exe' } else { 'javac' }
$java = if ($JdkHome) { Join-Path $JdkHome 'bin/java.exe' } else { 'java' }
$classes = Join-Path ([IO.Path]::GetTempPath()) ('save-archive-tests-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $classes | Out-Null
try {
    & $javac --release 8 -d $classes (Join-Path $PSScriptRoot '../src/main/java/io/github/zakuro/android/SaveArchive.java') (Join-Path $PSScriptRoot 'SaveArchiveCheck.java')
    if ($LASTEXITCODE -ne 0) { throw 'SaveArchive test compilation failed' }
    & $java -cp $classes io.github.zakuro.android.SaveArchiveCheck
    if ($LASTEXITCODE -ne 0) { throw 'SaveArchive tests failed' }
} finally {
    $resolved = [IO.Path]::GetFullPath($classes)
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if (!$resolved.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected test directory' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
