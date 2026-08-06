[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$Version,

    [switch]$Draft
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location -LiteralPath $ProjectRoot

function Stop-Publish([string]$Message) {
    Write-Host "[ERROR] $Message" -ForegroundColor Red
    exit 1
}

function Require-File([string]$Path, [string]$Description) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        Stop-Publish "$Description not found: $Path"
    }
}

function Invoke-Checked([string]$FilePath, [string[]]$Arguments) {
    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        Stop-Publish "Command failed ($LASTEXITCODE): $FilePath $($Arguments -join ' ')"
    }
}

Write-Host "Pulse Release Publisher" -ForegroundColor Cyan
Write-Host "Project: $ProjectRoot"

$GradleWrapper = Join-Path $ProjectRoot "gradlew.bat"
$AarPath = Join-Path $ProjectRoot "app\libs\libbox.aar"
$KeystorePath = Join-Path $ProjectRoot "app\release-key.jks"
$SigningPropertiesPath = Join-Path $ProjectRoot "keystore.properties"
$ApkPath = Join-Path $ProjectRoot "app\build\outputs\apk\release\app-release.apk"

Require-File $GradleWrapper "Gradle Wrapper"
Require-File $AarPath "libbox.aar"
Require-File $KeystorePath "Release keystore"
Require-File $SigningPropertiesPath "keystore.properties"

$GradleText = Get-Content -LiteralPath (Join-Path $ProjectRoot "app\build.gradle.kts") -Raw -Encoding UTF8
$VersionMatch = [regex]::Match($GradleText, '(?m)^\s*versionName\s*=\s*"([^"]+)"')
if (-not $VersionMatch.Success) {
    Stop-Publish "Could not read versionName from app/build.gradle.kts"
}

$DeclaredVersion = $VersionMatch.Groups[1].Value
if ([string]::IsNullOrWhiteSpace($Version)) {
    $Version = $DeclaredVersion
}
$Version = $Version.Trim().TrimStart("v")
if ($Version -notmatch '^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$') {
    Stop-Publish "Version must be SemVer, for example 1.1.0 or 1.1.0-rc1"
}
if ($Version -ne $DeclaredVersion) {
    Stop-Publish "Requested version $Version does not match app/build.gradle.kts versionName $DeclaredVersion"
}
$Tag = "v$Version"

$GitBranch = (& git branch --show-current).Trim()
if ([string]::IsNullOrWhiteSpace($GitBranch)) {
    Stop-Publish "Detached HEAD is not supported; check out the release branch first"
}
$GitStatus = (& git status --porcelain --untracked-files=all | Out-String).Trim()
if (-not [string]::IsNullOrWhiteSpace($GitStatus)) {
    Stop-Publish "Working tree is not clean. Commit the release changes first."
}

$ExistingLocalTag = (& git tag --list $Tag).Trim()
if (-not [string]::IsNullOrWhiteSpace($ExistingLocalTag)) {
    Stop-Publish "Local tag already exists: $Tag"
}
$ExistingRemoteTag = (& git ls-remote --tags origin "refs/tags/$Tag" | Out-String).Trim()
if ($LASTEXITCODE -ne 0) {
    Stop-Publish "Could not query tags from origin"
}
if (-not [string]::IsNullOrWhiteSpace($ExistingRemoteTag)) {
    Stop-Publish "Remote tag already exists: $Tag"
}

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    Stop-Publish "GitHub CLI (gh) is not installed. Install it and run 'gh auth login' first."
}
& gh auth status *> $null
if ($LASTEXITCODE -ne 0) {
    Stop-Publish "GitHub CLI is not authenticated. Run 'gh auth login' first."
}
$Repository = (& gh repo view --json nameWithOwner --jq .nameWithOwner).Trim()
if ([string]::IsNullOrWhiteSpace($Repository)) {
    Stop-Publish "Could not determine the GitHub repository from the current checkout"
}

$AarHash = (Get-FileHash -LiteralPath $AarPath -Algorithm SHA256).Hash
Write-Host "libbox.aar SHA-256: $AarHash"

$OldGradleOpts = $env:GRADLE_OPTS
$env:GRADLE_OPTS = "-Xmx4g -XX:MaxMetaspaceSize=512m"
try {
    Write-Host "Building signed Release APK..." -ForegroundColor Yellow
    $GradleCommand = "`"$GradleWrapper`" clean assembleRelease --no-daemon --no-build-cache"
    & cmd.exe /d /c $GradleCommand
    if ($LASTEXITCODE -ne 0) {
        Stop-Publish "Release build failed ($LASTEXITCODE)"
    }
}
finally {
    if ($null -eq $OldGradleOpts) {
        Remove-Item Env:GRADLE_OPTS -ErrorAction SilentlyContinue
    }
    else {
        $env:GRADLE_OPTS = $OldGradleOpts
    }
}

Require-File $ApkPath "Release APK"
$ApkHash = (Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash
$ApkSizeMb = [math]::Round((Get-Item -LiteralPath $ApkPath).Length / 1MB, 2)
Write-Host "APK: $ApkPath ($ApkSizeMb MB)"
Write-Host "APK SHA-256: $ApkHash"

$SdkRoot = $env:ANDROID_SDK_ROOT
if ([string]::IsNullOrWhiteSpace($SdkRoot)) { $SdkRoot = $env:ANDROID_HOME }
if ([string]::IsNullOrWhiteSpace($SdkRoot)) {
    $LocalProperties = Join-Path $ProjectRoot "local.properties"
    if (Test-Path -LiteralPath $LocalProperties -PathType Leaf) {
        $SdkLine = Get-Content -LiteralPath $LocalProperties -Encoding UTF8 | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($SdkLine) {
            $SdkRoot = ($SdkLine -replace '^sdk\.dir=', '').Replace('\:', ':').Replace('\\', '\')
        }
    }
}
if ([string]::IsNullOrWhiteSpace($SdkRoot) -or -not (Test-Path -LiteralPath $SdkRoot)) {
    Stop-Publish "Android SDK path could not be resolved; set ANDROID_SDK_ROOT or create local.properties"
}

$Apksigner = Get-ChildItem -LiteralPath (Join-Path $SdkRoot "build-tools") -Directory -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending |
    ForEach-Object { Join-Path $_.FullName "apksigner.bat" } |
    Where-Object { Test-Path -LiteralPath $_ } |
    Select-Object -First 1
if ($null -eq $Apksigner) {
    Stop-Publish "apksigner.bat was not found under Android SDK build-tools"
}
Write-Host "Verifying APK signature..." -ForegroundColor Yellow
& $Apksigner verify --verbose $ApkPath
if ($LASTEXITCODE -ne 0) {
    Stop-Publish "APK signature verification failed"
}

$JarCommand = Get-Command jar -ErrorAction SilentlyContinue
if ($null -eq $JarCommand -and $env:JAVA_HOME) {
    $JavaJar = Join-Path $env:JAVA_HOME "bin\jar.exe"
    if (Test-Path -LiteralPath $JavaJar) { $JarCommand = Get-Item -LiteralPath $JavaJar }
}
if ($null -eq $JarCommand) {
    Stop-Publish "jar was not found; JDK 17+ is required to verify APK ABI contents"
}
$JarPath = if ($JarCommand.PSObject.Properties.Name -contains "Source") { $JarCommand.Source } else { $JarCommand.FullName }
$NativeEntries = (& $JarPath tf $ApkPath | Select-String '^lib/.+\.so$' | ForEach-Object { $_.Line.Trim() })
if (-not $NativeEntries -or ($NativeEntries | Where-Object { $_ -notmatch '^lib/arm64-v8a/' })) {
    Stop-Publish "Release APK does not contain only arm64-v8a native libraries"
}
if (-not ($NativeEntries | Where-Object { $_ -eq 'lib/arm64-v8a/libbox.so' })) {
    Stop-Publish "Release APK is missing lib/arm64-v8a/libbox.so"
}

Write-Host "Pushing $GitBranch to origin..." -ForegroundColor Yellow
Invoke-Checked "git" @("push", "--set-upstream", "origin", $GitBranch)

$ReleaseTitle = "Pulse $Version"
$GhArgs = @("release", "create", $Tag, $ApkPath, "--repo", $Repository, "--target", $GitBranch, "--title", $ReleaseTitle, "--generate-notes")
if ($Draft) { $GhArgs += "--draft" }
Write-Host "Creating GitHub Release $Tag in $Repository..." -ForegroundColor Yellow
Invoke-Checked "gh" $GhArgs

$ReleaseUrl = (& gh release view $Tag --repo $Repository --json url --jq .url).Trim()
Write-Host "Release published successfully: $ReleaseUrl" -ForegroundColor Green
Write-Host "Asset SHA-256: $ApkHash"
