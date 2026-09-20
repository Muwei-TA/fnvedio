[CmdletBinding()]
param(
    [string]$Root = (Join-Path $PSScriptRoot '..'),
    [string]$GradleVersion = '8.9',
    [string]$CommandLineToolsVersion = '13114758',
    [switch]$SkipSdkPackages
)

$ErrorActionPreference = 'Stop'
$rootPath = (Resolve-Path -LiteralPath $Root).Path
$toolsPath = Join-Path $rootPath '.tools'
$downloadsPath = Join-Path $toolsPath 'downloads'
$sdkPath = Join-Path $toolsPath 'android-sdk'
$gradlePath = Join-Path $toolsPath 'gradle'
$javaCandidates = @(@(
    $env:JAVA_HOME,
    'C:\Program Files\Android\openjdk\jdk-21.0.8',
    'C:\Program Files\Java\jdk-17'
) | Where-Object { $_ -and (Test-Path (Join-Path $_ 'bin\java.exe')) })

if ($javaCandidates.Count -eq 0) {
    throw 'A JDK 17 or newer is required. Install one locally and set JAVA_HOME for this process.'
}

$env:JAVA_HOME = (Resolve-Path -LiteralPath $javaCandidates[0]).Path
$env:ANDROID_SDK_ROOT = $sdkPath
New-Item -ItemType Directory -Force -Path $downloadsPath, $sdkPath, $gradlePath | Out-Null

function Download-IfMissing([string]$Uri, [string]$Destination, [int64]$MinimumBytes) {
    if ((Test-Path -LiteralPath $Destination) -and ((Get-Item -LiteralPath $Destination).Length -ge $MinimumBytes)) {
        return
    }
    Write-Host "Downloading $Uri"
    & curl.exe -L --fail --retry 3 --retry-delay 2 -o $Destination $Uri
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $Destination)) {
        throw "Download failed: $Uri"
    }
}

$gradleZip = Join-Path $downloadsPath "gradle-$GradleVersion-bin.zip"
Download-IfMissing "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip" $gradleZip 100000000
if ($GradleVersion -eq '8.9') {
    $gradleShaFile = Join-Path $downloadsPath "gradle-$GradleVersion-bin.zip.sha256"
    Download-IfMissing "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip.sha256" $gradleShaFile 64
    $gradleSha = (Get-Content -LiteralPath $gradleShaFile -Raw).Trim().Split()[0]
    $actualGradleSha = (Get-FileHash -LiteralPath $gradleZip -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualGradleSha -ne $gradleSha.ToLowerInvariant()) { throw 'Gradle archive SHA-256 verification failed.' }
}
$gradleHome = Join-Path $gradlePath "gradle-$GradleVersion"
if (-not (Test-Path (Join-Path $gradleHome 'bin\gradle.bat'))) {
    & tar.exe -xf $gradleZip -C $gradlePath
    if ($LASTEXITCODE -ne 0) { throw 'Gradle archive extraction failed.' }
}

$sdkZip = Join-Path $downloadsPath "commandlinetools-win-$CommandLineToolsVersion`_latest.zip"
Download-IfMissing "https://redirector.gvt1.com/edgedl/android/repository/commandlinetools-win-${CommandLineToolsVersion}_latest.zip" $sdkZip 100000000
if ($CommandLineToolsVersion -eq '13114758') {
    $actualSdkSha = (Get-FileHash -LiteralPath $sdkZip -Algorithm SHA1).Hash.ToLowerInvariant()
    if ($actualSdkSha -ne '54a582f3bf73e04253602f2d1c80bd5868aac115') { throw 'Android command-line tools SHA-1 verification failed.' }
}
$cmdlineHome = Join-Path $sdkPath 'cmdline-tools\latest'
if (-not (Test-Path (Join-Path $cmdlineHome 'bin\sdkmanager.bat'))) {
    New-Item -ItemType Directory -Force -Path $cmdlineHome | Out-Null
    & tar.exe -xf $sdkZip -C $cmdlineHome --strip-components=1
    if ($LASTEXITCODE -ne 0) { throw 'Android command-line tools extraction failed.' }
}

if (-not $SkipSdkPackages) {
    $sdkmanager = Join-Path $cmdlineHome 'bin\sdkmanager.bat'
    1..20 | ForEach-Object { 'y' } | & $sdkmanager "--sdk_root=$sdkPath" '--licenses' | Out-Host
    & $sdkmanager "--sdk_root=$sdkPath" 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0'
    if ($LASTEXITCODE -ne 0) { throw 'Android SDK package installation failed.' }
}

$localProperties = Join-Path $rootPath 'local.properties'
"sdk.dir=$($sdkPath.Replace('\', '/'))" | Set-Content -LiteralPath $localProperties -Encoding ascii
Write-Host "JDK: $env:JAVA_HOME"
Write-Host "SDK: $sdkPath"
Write-Host "Gradle: $gradleHome"
