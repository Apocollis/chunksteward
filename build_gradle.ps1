# Lazy Chunks 1.12 Gradle build + deploy script
$ErrorActionPreference = "Stop"
$scriptDir = (Split-Path -Parent $MyInvocation.MyCommand.Path).Replace("\", "/")

# Override with LAZYCHUNKS_DEVBOX_MODS / LAZYCHUNKS_JAVA_HOME when this machine differs.
$localModsDir = if ($env:LAZYCHUNKS_DEVBOX_MODS) { $env:LAZYCHUNKS_DEVBOX_MODS } else { "c:/Users/hughe/curseforge/minecraft/Instances/Arcana Quest DEVBOX/mods" }
$env:JAVA_HOME = if ($env:LAZYCHUNKS_JAVA_HOME) { $env:LAZYCHUNKS_JAVA_HOME } else { "C:\Program Files\Zulu\zulu-25" }
Write-Output "JAVA_HOME: $env:JAVA_HOME"

$oldPwd = pwd
cd $scriptDir
$priorGradleOpts = $env:GRADLE_OPTS
$env:GRADLE_OPTS = (($priorGradleOpts, "--enable-native-access=ALL-UNNAMED") | Where-Object { $_ }) -join " "
$priorErrorPreference = $ErrorActionPreference
$ErrorActionPreference = "Continue"
try {
    & .\gradlew.bat build
    $gradleExit = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $priorErrorPreference
    $env:GRADLE_OPTS = $priorGradleOpts
    cd $oldPwd
}
if ($gradleExit -ne 0) { throw "Gradle build failed with exit code $gradleExit" }

$buildVersion = $null
$versionLine = Select-String -Path (Join-Path $scriptDir "build.gradle") -Pattern "^version\s*=\s*'([^']+)'" | Select-Object -First 1
if ($versionLine) { $buildVersion = $versionLine.Matches[0].Groups[1].Value }
$jar = Get-ChildItem -Path (Join-Path $scriptDir "build/libs") -Filter "LazyChunks-$buildVersion.jar" | Select-Object -First 1
if (-not $jar) { Write-Error "Output jar LazyChunks-$buildVersion.jar not found in build/libs" }

if (!(Test-Path "$scriptDir/mods")) { New-Item -ItemType Directory -Path "$scriptDir/mods" | Out-Null }
Get-ChildItem -Path "$scriptDir/mods" -Filter "LazyChunks-*.jar" -ErrorAction SilentlyContinue | Remove-Item -Force
Get-ChildItem -Path $localModsDir -Filter "LazyChunks-*.jar" -ErrorAction SilentlyContinue | Remove-Item -Force
Copy-Item -Path $jar.FullName -Destination "$scriptDir/mods/$($jar.Name)" -Force
Copy-Item -Path $jar.FullName -Destination "$localModsDir/$($jar.Name)" -Force
Write-Output "=== Build and Deployment Succeeded: $($jar.Name) ==="
