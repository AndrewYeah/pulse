# Pulse

[English](README.md) | [简体中文](README_CN.md)

Pulse is an Android transparent proxy client built on the [sing-box](https://github.com/SagerNet/sing-box) `libbox` core. It creates a TUN virtual interface through Android `VpnService`, then lets sing-box handle DNS, routing, and proxy egress for device traffic without requiring a separate proxy client.

The application ID is `com.andrew.proxyapp`. It supports Android 8.0 (API 26) and later, targets SDK 36, and currently produces `arm64-v8a` APKs only.

> `libbox.aar` is a locally obtained binary core and is intentionally excluded from GitHub. After cloning the repository, complete the “Get `libbox.aar`” section below before building.

## Features

- Device-wide transparent proxying through `VpnService`, TUN, and sing-box.
- Rule and global modes, LAN direct access, custom domain/IP rules, routing groups, and local rule sets.
- VLESS, VMess, Hysteria2, TUIC, AnyTLS, Shadowsocks, Trojan, SOCKS5, and HTTP outbounds.
- Manual single-link import plus Base64 URI lists, Clash YAML, and sing-box JSON subscriptions.
- Subscription updates with multiple User-Agents, old-node cleanup, and stable-ID duplicate merging.
- DNS strategy, proxy/direct DNS, per-app include or exclude mode, and fixed nodes for selected apps.
- Runtime node switching, connection list, logs and diagnostics, latency tests, and battery-optimization checks.
- Simplified Chinese, English, Russian, Persian, Azerbaijani, and Arabic, plus light, dark, and system themes.
- A first-run English language-selection page; the language can be changed later in General settings.
- A centralized UI design system for colors, spacing, corner radii, typography, and common controls.

`ssr://` links can be recognized as import items, but sing-box 1.13.14 does not support ShadowsocksR. The app reports that the profile is unavailable before starting the tunnel.

## How It Works

```text
Device application traffic
    |
    v
Android VpnService / TUN (gVisor stack)
    |
    v
TunnelService -> sing-box libbox
    |                 |
    |                 +-> DNS hijacking, sniffing, rules, per-app routing
    v
selector: current profile / fixed profile / direct-out / block-out
    |
    v
Proxy server or direct destination
```

By default, applications other than Pulse enter the VPN. In Per-app routing, you can include only selected applications or exclude selected applications. A specific application can also be assigned its own profile.

`TunnelService` protects proxy outbound sockets with `VpnService.protect()` so that proxy connections do not re-enter the TUN and create a routing loop.

## Technology Stack

| Component | Current configuration |
| --- | --- |
| Language | Kotlin, JVM target 17 |
| Android Gradle Plugin | 8.13.2 |
| Gradle Wrapper | 8.13 |
| Minimum / target SDK | 26 / 36 |
| Core | sing-box libbox 1.13.14 |
| Packaged ABI | `arm64-v8a` |
| UI | Android Views, Material Components, ViewBinding |
| Persistence | SharedPreferences + Gson |

## Quick Start

### 1. Prepare the environment

Install:

- Android Studio (latest stable is recommended) and Android SDK Platform 36.
- JDK 17 or later. The Gradle Wrapper downloads the required Gradle version.
- Android Platform Tools for device installation (optional).

Clone the repository and enter its root directory:

```powershell
git clone <repository-url> Pulse
Set-Location Pulse
```

Android Studio creates the machine-specific `local.properties` on first open. It is Git-ignored and must not be committed.

### 2. Get `libbox.aar`

This project depends on the **1.13.14** artifact from `singbox-android/libbox`. Use the version that matches the source code. Replacing it with an arbitrary newer core may break Java/Kotlin APIs or sing-box configuration fields.

Fixed download URL:

```text
https://jitpack.io/com/github/singbox-android/libbox/1.13.14/libbox-1.13.14.aar
```

From the project root, create the expected directory and download the expected filename.

Windows PowerShell:

```powershell
New-Item -ItemType Directory -Force .\app\libs | Out-Null
Invoke-WebRequest -Uri "https://jitpack.io/com/github/singbox-android/libbox/1.13.14/libbox-1.13.14.aar" -OutFile .\app\libs\libbox.aar
```

macOS / Linux:

```bash
mkdir -p app/libs
curl -fL https://jitpack.io/com/github/singbox-android/libbox/1.13.14/libbox-1.13.14.aar -o app/libs/libbox.aar
```

The final path must be:

```text
Pulse/
└── app/
    └── libs/
        └── libbox.aar
```

`app/libs/.gitkeep` only preserves the empty directory in the repository. `libbox.aar` and extracted AAR directories are ignored by `.gitignore` and must never be committed to GitHub.

#### Verify the download

SHA-256 for the current 1.13.14 artifact:

```text
D8EE7620047E4485199A9CF8DB30E67D1497534117F1774A93C0696068B7B012
```

Windows PowerShell:

```powershell
(Get-FileHash .\app\libs\libbox.aar -Algorithm SHA256).Hash
jar tf .\app\libs\libbox.aar | Select-String "jni/arm64-v8a/libbox.so"
```

macOS / Linux:

```bash
shasum -a 256 app/libs/libbox.aar
jar tf app/libs/libbox.aar | grep 'jni/arm64-v8a/libbox.so'
```

The archive must contain `jni/arm64-v8a/libbox.so`. The AAR may contain multiple ABIs, but `app/build.gradle.kts` locks the final APK to `arm64-v8a`; do not change it to a multi-ABI build.

Do not download and rename the Android `tar.gz` executable from a sing-box release, or extract a replacement from an SFA APK. Those files are not the Java/JNI library wrapper required by this project.

### 3. Sync and build

On Windows, the repository build helper is recommended. It cleans old outputs and disables the Gradle Build Cache so modified Kotlin code cannot be replaced by stale cached results.

```powershell
cmd /c "build-arm64.bat"
```

Equivalent manual command:

```powershell
cmd /c ".\gradlew clean assembleDebug --no-daemon --no-build-cache"
```

Debug APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### 4. Install on a device

Enable USB or wireless debugging on an Android device, then run:

```powershell
adb install -r -t app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.andrew.proxyapp/.MainActivity
```

If `adb` is not on `PATH`, replace it with the full path to Android SDK `platform-tools/adb`.

### 5. First use

1. On the first launch, use the English language-selection page to choose a preferred language and tap Continue.
2. Open Profiles to add a single proxy profile, or open Subscription manager to add a subscription URL.
3. Select an available profile and configure rule/global mode, DNS, and per-app routing as needed.
4. Tap the connection button and approve the system VPN permission dialog.
5. Review runtime state, traffic, logs, and categorized errors in Connections and Logs & diagnostics.

Example URI for format illustration only; it contains no usable credentials:

```text
hysteria2://password@example.com:443?sni=example.com&alpn=h3#Example
```

Do not put real subscription URLs, node passwords, private keys, or certificate-skip settings in source code, README files, issues, or logs.

## Building a Release

Release signing is local configuration and is not distributed with this repository. To build a signed Release, create the ignored `keystore.properties` in the project root:

```properties
storeFile=app/release-key.jks
storePassword=<keystore-password>
keyAlias=<key-alias>
keyPassword=<key-password>
```

Place the keystore at `app/release-key.jks`, then run:

```powershell
cmd /c ".\gradlew clean assembleRelease --no-daemon --no-build-cache"
```

Release APK output:

```text
app/build/outputs/apk/release/app-release.apk
```

For public releases, use a dedicated keystore and CI Secrets. Do not reuse local development credentials.

## Publishing to GitHub Releases

The project maintainer may use local `publish-release.bat` / `publish-release.ps1` helpers. These scripts are local release tools, are Git-ignored, and are not distributed in the public repository. The current local scripts:

1. Check the worktree, version, `libbox.aar`, and local signing files.
2. Run unit tests, full Lint, and a signed Release build with `clean testDebugUnitTest lintDebug assembleRelease --no-build-cache`.
3. Verify the APK signature and confirm that only `arm64-v8a` and `libbox.so` are packaged.
4. Push the current branch to `origin`.
5. Create the matching GitHub Release and upload the APK as an asset.

The first use requires [GitHub CLI](https://cli.github.com/) and authentication:

```powershell
winget install --id GitHub.cli
gh auth login
```

Choose `GitHub.com`, SSH or HTTPS, and complete the prompts. Check the session with:

```powershell
gh auth status
```

Commit the source and documentation changes before publishing so the worktree is clean. The local helper reads `versionName` from `app/build.gradle.kts` and creates a matching `v<version>` tag. For version `1.1.0`:

```powershell
cmd /c "publish-release.bat"
```

You can also pass the same version explicitly or create a Draft Release for review:

```powershell
cmd /c "publish-release.bat 1.1.0"
powershell -NoProfile -ExecutionPolicy Bypass -File .\publish-release.ps1 -Draft
```

The following files must be prepared locally and are never uploaded:

- `app/libs/libbox.aar`
- `app/release-key.jks`
- `keystore.properties`

After publishing, the GitHub Release page contains the APK download and SHA-256. The helper stops without overwriting an existing Release if the tag already exists, the version does not match Gradle, signature validation fails, or the worktree is dirty.

### Manually run GitHub Actions

GitHub Actions does not run on ordinary pushes or pull requests, avoiding duplicate remote builds and notification emails. To verify that a clean Linux environment can build the project from scratch:

1. Open the repository's `Actions` page on GitHub.
2. Select `Android checks`.
3. Click `Run workflow` and select the branch to verify.
4. Click `Run workflow` again to confirm.

The manual job downloads and verifies `libbox.aar` in a temporary GitHub environment, then runs unit tests, Lint, and a Debug build. It does not access the local Release keystore or publish an APK.

## Project Structure

```text
Pulse/
├── app/
│   ├── libs/
│   │   ├── .gitkeep
│   │   └── libbox.aar              # downloaded locally, Git-ignored
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── kotlin/com/andrew/proxyapp/
│   │   │   ├── MainActivity.kt
│   │   │   ├── config/
│   │   │   │   └── SingBoxConfigBuilder.kt
│   │   │   ├── data/
│   │   │   │   ├── ConfigStore.kt
│   │   │   │   ├── SubscriptionManager.kt
│   │   │   │   └── UriParser.kt
│   │   │   ├── manager/
│   │   │   │   ├── ProxyManager.kt
│   │   │   │   └── RuntimeController.kt
│   │   │   ├── service/
│   │   │   │   └── TunnelService.kt
│   │   │   └── ui/
│   │   └── res/
│   │       ├── layout/
│   │       └── values/
│   │           ├── colors.xml
│   │           ├── dimens.xml
│   │           └── styles.xml
│   └── build.gradle.kts
├── gradle/
├── build-arm64.bat
├── README.md
├── README_CN.md
└── AGENTS.md                       # local maintenance context, Git-ignored
```

## Key Modules

| Module | Responsibility |
| --- | --- |
| `TunnelService` | Implements Android `VpnService` and libbox `PlatformInterface`, creates TUN, protects outbound sockets, and starts or restores the core. |
| `SingBoxConfigBuilder` | Generates sing-box 1.13-compatible JSON from profiles and app settings. |
| `ProxyManager` | Requests VPN permission and coordinates service start, stop, and restart. |
| `RuntimeController` | Reads runtime state, connections, and logs, and switches selector profiles while running. |
| `ConfigStore` | Persists profiles, subscriptions, DNS, rules, themes, language, and per-app settings. |
| `SubscriptionManager` | Downloads subscriptions, tries multiple User-Agents, identifies URI / Clash / sing-box formats, and imports profiles. |
| `RuleSetManager` | Manages available local rule-set files. |
| `ui/` | Main screen, profiles, subscriptions, DNS, routing, per-app routing, diagnostics, and general settings. |

## UI Design System

Do not maintain page styling with scattered hard-coded values. Prefer these shared resources when adding or adjusting UI:

| File | Purpose |
| --- | --- |
| `app/src/main/res/values/colors.xml` | Semantic color tokens; dark-theme overrides are in `values-night/`. |
| `app/src/main/res/values/dimens.xml` | Spacing, corner radii, typography, toolbar, touch targets, and list-row dimensions. |
| `app/src/main/res/values/styles.xml` | Card, button, input, search, settings-row, switch, text, and dialog themes. |
| `app/src/main/kotlin/com/andrew/proxyapp/ui/ChoiceSheet.kt` | Shared bottom single-choice component for DNS and general settings. |

Use `Widget.Pulse.*` styles, `@color/...` semantic resources, and `@dimen/...` dimensions on regular pages. Do not duplicate common colors, corner radii, text sizes, or spacing in layouts or Kotlin code.

## Tests and Quality Checks

Before committing:

```powershell
cmd /c ".\gradlew testDebugUnitTest lintDebug --no-daemon --no-build-cache"
```

For an installable package, run the complete build:

```powershell
cmd /c ".\gradlew clean assembleDebug --no-daemon --no-build-cache"
```

On at least one real `arm64-v8a` device, check VPN authorization, start/stop, profile switching, subscription import, DNS editing, per-app routing, light/dark themes, and the rounded dialog background.

## Troubleshooting

### `io.nekohasekai.libbox` cannot be found during compilation

The AAR is usually missing or is not at `app/libs/libbox.aar`. Repeat the “Get `libbox.aar`” section, then sync Gradle or rebuild.

### The APK still contains old code after a change

This project requires `--no-build-cache`. Prefer `build-arm64.bat`, which includes `clean` and this flag.

### VPN is connected but no traffic can reach the network

Start with the diagnostic logs. Common causes include an unavailable profile, missing network permission, invalid DNS configuration, or libbox failing to identify the physical network interface. Do not simplify the return behavior of `TunnelService.getInterfaces()`, `autoDetectInterfaceControl()`, or `protect()`, or you may get `no available network interface` or an outbound loop.

### Subscription update reports zero profiles

A provider may return different formats for different User-Agents. The app automatically identifies URI lists, Clash YAML, and sing-box JSON. If it still fails, inspect the response format, subscription reachability, and whether the profile protocol is supported by the current core in the diagnostic log.

### The app cannot compile or start after updating libbox

Do not only replace the AAR. First check the new `PlatformInterface` method signatures, sing-box JSON migration notes, and required feature tags. Then update `TunnelService`, `SingBoxConfigBuilder`, and tests together.

## Security and Repository Hygiene

- `.gitignore` excludes `libbox.aar`, keystores, signing properties, local SDKs, build outputs, logs, screenshots, and local maintenance documents.
- `AGENTS.md` is local maintenance context and may contain development-environment or signing information; it must not be committed to the public repository.
- If a sensitive file was previously committed, changing `.gitignore` does not remove it from Git history. Rotate the credential immediately and use `git rm --cached <file>` to stop future tracking.
- When using sing-box / libbox, follow the upstream license and all laws, network policies, and service terms applicable to your location.
