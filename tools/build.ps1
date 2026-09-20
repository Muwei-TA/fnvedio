[CmdletBinding()]
param(
    [string[]]$Task = @('assembleDebug'),
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
$rootPath = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$sdkPath = Join-Path $rootPath '.tools\android-sdk'
$javaCandidates = @(@(
    $env:JAVA_HOME,
    'C:\Program Files\Android\openjdk\jdk-21.0.8',
    'C:\Program Files\Java\jdk-17'
) | Where-Object { $_ -and (Test-Path (Join-Path $_ 'bin\java.exe')) })

if ($javaCandidates.Count -eq 0) { throw 'A JDK 17 or newer is required.' }
if (-not (Test-Path (Join-Path $sdkPath 'platforms\android-35\android.jar'))) {
    throw "Android SDK platform 35 is missing at $sdkPath. Run tools\setup-android.ps1 first."
}

$env:JAVA_HOME = (Resolve-Path -LiteralPath $javaCandidates[0]).Path
$env:ANDROID_SDK_ROOT = $sdkPath
$env:ANDROID_HOME = $sdkPath
$localProperties = Join-Path $rootPath 'local.properties'
"sdk.dir=$($sdkPath.Replace('\', '/'))" | Set-Content -LiteralPath $localProperties -Encoding ascii

$gradlew = Join-Path $rootPath 'gradlew.bat'
if (-not (Test-Path -LiteralPath $gradlew)) { throw 'gradlew.bat is missing.' }
$arguments = @('--no-daemon', '--console=plain')
if ($Offline) { $arguments += '--offline' }
$arguments += $Task
Push-Location $rootPath
try {
    & $gradlew @arguments
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    if ($Task -contains 'assembleDebug') {
        $apkDirectory = Join-Path $rootPath 'app\build\outputs\apk\debug'
        $metadata = Get-Content -Raw -LiteralPath (Join-Path $apkDirectory 'output-metadata.json') | ConvertFrom-Json
        $artifacts = @($metadata.elements)
        if ($artifacts.Count -ne 1) { throw 'Expected one debug APK in output metadata.' }
        $artifact = $artifacts[0]
        $distDirectory = Join-Path $rootPath 'dist'
        New-Item -ItemType Directory -Force -Path $distDirectory | Out-Null
        $apkName = "fnvideo-v$($artifact.versionName)-$($artifact.versionCode)-debug.apk"
        Copy-Item -LiteralPath (Join-Path $apkDirectory $artifact.outputFile) -Destination (Join-Path $distDirectory $apkName)
        Write-Host "APK: dist/$apkName"
    }
    exit 0
}
finally {
    Pop-Location
}
