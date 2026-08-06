# Pulse

Pulse 是一个基于 [sing-box](https://github.com/SagerNet/sing-box) `libbox` 内核的 Android 透明代理客户端。它通过 Android `VpnService` 创建 TUN 虚拟网卡，将设备流量交给 sing-box 进行 DNS 处理、分流和代理出站，无需另外安装代理客户端。

当前应用标识为 `com.andrew.proxyapp`，最低支持 Android 8.0（API 26），目标 SDK 为 36。项目只产出 `arm64-v8a` APK。

> `libbox.aar` 是本地获取的二进制内核，故意不提交到 GitHub。首次克隆仓库后，必须先完成下方“获取 libbox.aar”步骤，项目才能编译。

## 功能概览

- 基于 `VpnService` + TUN + sing-box 的设备级透明代理。
- 支持规则模式与全局模式；可配置局域网直连、自定义域名/IP 规则、规则组和本地规则集。
- 支持 VLESS、VMess、Hysteria2、TUIC、AnyTLS、Shadowsocks、Trojan、SOCKS5 与 HTTP 出站。
- 支持单链接手动导入，以及 Base64 URI 列表、Clash YAML、sing-box JSON 订阅。
- 订阅更新会尝试多种 User-Agent，并清理同一订阅的旧节点、合并稳定 ID 重复节点。
- 支持 DNS 策略、代理 DNS / 直连 DNS、按应用包含或排除、为指定应用固定节点。
- 支持运行中切换当前节点、连接列表、日志与诊断、延迟测试和电池优化检查。
- 支持简体中文、English、Русский、فارسی、Azərbaycan dili、العربية，以及浅色、深色和跟随系统主题。
- UI 的颜色、间距、圆角、文本层级和常用控件样式已集中到资源设计系统，便于统一维护。

`ssr://` 可以被识别为导入项，但 sing-box 1.13.14 不支持 ShadowsocksR；应用会在启动前提示该节点不可用。

## 工作原理

```text
设备应用流量
    |
    v
Android VpnService / TUN (gVisor stack)
    |
    v
TunnelService -> sing-box libbox
    |                 |
    |                 +-> DNS 劫持、嗅探、规则匹配、按应用路由
    v
selector: 当前节点 / 指定节点 / direct-out / block-out
    |
    v
代理服务器或直连目标
```

默认情况下，除 Pulse 自身外的设备应用会进入 VPN。可在“按应用分流”中改为只包含选中应用，或排除选中应用；也可以为某个应用明确绑定独立节点。

`TunnelService` 会将代理出站 socket 交给 `VpnService.protect()` 保护，避免代理连接再次回流进 TUN 造成路由环路。

## 技术栈

| 项目 | 当前配置 |
| --- | --- |
| 语言 | Kotlin，JVM target 17 |
| Android Gradle Plugin | 8.13.2 |
| Gradle Wrapper | 8.13 |
| 最低 / 目标 SDK | 26 / 36 |
| 内核 | sing-box libbox 1.13.14 |
| 打包 ABI | `arm64-v8a` |
| UI | Android Views、Material Components、ViewBinding |
| 持久化 | SharedPreferences + Gson |

## 快速开始

### 1. 准备环境

安装以下工具：

- Android Studio（推荐最新稳定版）及 Android SDK Platform 36。
- JDK 17 或更高版本。Gradle Wrapper 会自动下载项目所需的 Gradle 版本。
- 用于真机安装的 Android Platform Tools（可选）。

克隆仓库后，先进入项目根目录：

```powershell
git clone <你的仓库地址> Pulse
Set-Location Pulse
```

Android Studio 首次打开项目后会生成本机专用的 `local.properties`；它已被 Git 忽略，不需要提交。

### 2. 获取 `libbox.aar`

本项目依赖 `singbox-android/libbox` 的 **1.13.14** 构件。请使用与代码匹配的版本，不要随意替换成新版内核；不同 libbox 版本的 Java/Kotlin API 和 sing-box 配置字段可能不兼容。

内核的固定下载地址：

```text
https://jitpack.io/com/github/singbox-android/libbox/1.13.14/libbox-1.13.14.aar
```

在项目根目录执行以下命令，创建目录并下载为项目期待的文件名。

Windows PowerShell：

```powershell
New-Item -ItemType Directory -Force .\app\libs | Out-Null
Invoke-WebRequest `
  -Uri "https://jitpack.io/com/github/singbox-android/libbox/1.13.14/libbox-1.13.14.aar" `
  -OutFile .\app\libs\libbox.aar
```

macOS / Linux：

```bash
mkdir -p app/libs
curl -fL \
  https://jitpack.io/com/github/singbox-android/libbox/1.13.14/libbox-1.13.14.aar \
  -o app/libs/libbox.aar
```

最终目录必须是：

```text
Pulse/
└── app/
    └── libs/
        └── libbox.aar
```

`app/libs/.gitkeep` 仅用于在仓库中保留目录；`libbox.aar` 以及其解包目录已被 `.gitignore` 忽略，绝不能提交到 GitHub。

#### 校验下载结果

当前 1.13.14 构件的 SHA-256 为：

```text
D8EE7620047E4485199A9CF8DB30E67D1497534117F1774A93C0696068B7B012
```

Windows PowerShell：

```powershell
(Get-FileHash .\app\libs\libbox.aar -Algorithm SHA256).Hash
jar tf .\app\libs\libbox.aar | Select-String "jni/arm64-v8a/libbox.so"
```

macOS / Linux：

```bash
shasum -a 256 app/libs/libbox.aar
jar tf app/libs/libbox.aar | grep 'jni/arm64-v8a/libbox.so'
```

输出应包含 `jni/arm64-v8a/libbox.so`。该 AAR 本身可能包含多个 ABI；项目的 `app/build.gradle.kts` 已将最终 APK 锁定为 `arm64-v8a`，不要把它改为多架构。

不要下载并改名使用 sing-box Release 中的 Android `tar.gz` 可执行文件，也不要从 SFA APK 中提取文件替代 AAR；它们不是本项目所需的 Java/JNI 库封装。

### 3. 同步并构建

Windows 下建议使用仓库自带脚本。它会清理旧产物，并关闭 Gradle Build Cache，避免修改后的 Kotlin 代码被旧缓存覆盖。

```powershell
cmd /c "build-arm64.bat"
```

等价的手动命令：

```powershell
cmd /c ".\gradlew clean assembleDebug --no-daemon --no-build-cache"
```

调试 APK 输出路径：

```text
app/build/outputs/apk/debug/app-debug.apk
```

### 4. 安装到设备

连接 Android 设备并开启 USB 或无线调试后：

```powershell
adb install -r -t app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.andrew.proxyapp/.MainActivity
```

若 `adb` 不在系统 `PATH`，请替换为 Android SDK 的 `platform-tools/adb` 完整路径。

### 5. 首次使用

1. 打开 Pulse，进入“节点”添加单个节点，或进入“订阅管理”添加订阅链接。
2. 选择一个可用节点，按需设置规则 / 全局模式、DNS 和按应用分流。
3. 点击主页连接按钮，并在系统 VPN 授权对话框中确认。
4. 在连接列表和诊断页面查看运行状态、流量、日志及错误分类。

示例仅用于说明 URI 格式，不包含可用凭据：

```text
hysteria2://password@example.com:443?sni=example.com&alpn=h3#Example
```

不要将真实订阅地址、节点密码、私钥或证书跳过设置写入源码、README、Issue 或日志。

## 构建 Release

Release 签名是本机配置，不随仓库分发。需要签名 Release 时，在项目根目录创建被忽略的 `keystore.properties`：

```properties
storeFile=app/release-key.jks
storePassword=<keystore-password>
keyAlias=<key-alias>
keyPassword=<key-password>
```

将 keystore 放在 `app/release-key.jks`，然后执行：

```powershell
cmd /c ".\gradlew clean assembleRelease --no-daemon --no-build-cache"
```

Release APK 输出到：

```text
app/build/outputs/apk/release/app-release.apk
```

在公开发布前，应使用独立 keystore 和 CI Secret 管理签名信息；不要复用本地开发凭据。

## 发布到 GitHub Release

仓库提供了 `publish-release.bat` 一键发布入口。它会依次执行：

1. 检查工作区、版本号、`libbox.aar` 和本地签名文件。
2. 使用 `clean testDebugUnitTest lintDebug assembleRelease --no-build-cache` 运行单元测试、完整 Lint 并构建签名 APK。
3. 验证 APK 签名，并确认只包含 `arm64-v8a` 和 `libbox.so`。
4. 推送当前分支到 `origin`。
5. 创建对应的 GitHub Release，并将 APK 作为附件上传。

首次使用需要安装 [GitHub CLI](https://cli.github.com/) 并登录：

```powershell
winget install --id GitHub.cli
gh auth login
```

选择 `GitHub.com`、SSH 或 HTTPS，并按提示完成认证。确认登录状态：

```powershell
gh auth status
```

发布前请先提交要发布的源码和文档变更，确保工作区干净；脚本会从 `app/build.gradle.kts` 的 `versionName` 读取版本，并创建同名的 `v<version>` tag。例如当前版本为 `1.1.0` 时：

```powershell
cmd /c "publish-release.bat"
```

也可以显式传入相同版本号，或创建 Draft Release 供检查：

```powershell
cmd /c "publish-release.bat 1.1.0"
powershell -NoProfile -ExecutionPolicy Bypass -File .\publish-release.ps1 -Draft
```

脚本要求以下文件已在本地准备好，但不会上传它们：

- `app/libs/libbox.aar`
- `app/release-key.jks`
- `keystore.properties`

发布完成后，GitHub Release 页面会显示 APK 下载地址和 SHA-256。若 tag 已存在、版本号与 Gradle 不一致、签名校验失败或工作区有未提交改动，脚本会停止，不会覆盖已有 Release。

### 手动运行 GitHub Actions

GitHub Actions 不会在普通 push 或 pull request 时自动运行，避免重复远程构建和通知邮件。需要验证仓库能否在全新 Linux 环境从零构建时：

1. 打开 GitHub 仓库的 `Actions` 页面。
2. 选择 `Android checks`。
3. 点击 `Run workflow`，选择要验证的分支。
4. 再次点击 `Run workflow` 确认。

该手动任务会在 GitHub 临时环境中下载并校验 `libbox.aar`，然后运行单元测试、Lint 和 Debug 构建；它不会接触本地 Release keystore，也不会发布 APK。

## 项目结构

```text
Pulse/
├── app/
│   ├── libs/
│   │   ├── .gitkeep
│   │   └── libbox.aar              # 本地下载，Git 忽略
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
├── publish-release.bat
├── publish-release.ps1
├── README.md
└── AGENTS.md                       # 本地维护上下文，Git 忽略
```

## 关键模块

| 模块 | 职责 |
| --- | --- |
| `TunnelService` | 实现 `VpnService` 和 libbox `PlatformInterface`，创建 TUN、保护出站 socket、启动和恢复内核。 |
| `SingBoxConfigBuilder` | 将节点和应用设置生成符合 sing-box 1.13 的 JSON 配置。 |
| `ProxyManager` | 请求 VPN 权限，协调服务启动、停止和重启。 |
| `RuntimeController` | 读取运行状态、连接、日志并在运行中切换 selector 节点。 |
| `ConfigStore` | 持久化节点、订阅、DNS、规则、主题和按应用设置。 |
| `SubscriptionManager` | 下载订阅、尝试多 User-Agent、识别 URI / Clash / sing-box 格式并导入节点。 |
| `RuleSetManager` | 管理可用的本地规则集文件。 |
| `ui/` | 主界面、节点、订阅、DNS、路由、按应用分流、诊断和通用设置页面。 |

## UI 设计系统

页面样式不能通过零散硬编码维持。新增或调整 UI 时，请优先复用以下资源：

| 文件 | 用途 |
| --- | --- |
| `app/src/main/res/values/colors.xml` | 语义颜色令牌；深色主题覆盖位于 `values-night/`。 |
| `app/src/main/res/values/dimens.xml` | 间距、圆角、字号、Toolbar、触控区和列表行尺寸。 |
| `app/src/main/res/values/styles.xml` | 卡片、按钮、输入框、搜索框、设置行、开关、文本和弹窗主题。 |
| `app/src/main/kotlin/com/andrew/proxyapp/ui/ChoiceSheet.kt` | DNS 与通用设置共用的底部单选组件。 |

常用页面应使用 `Widget.Pulse.*` 组件样式，颜色使用 `@color/...` 语义资源，尺寸使用 `@dimen/...`。不要在布局或 Kotlin 中重复写通用颜色、圆角、字号和边距。

## 测试与质量检查

在提交前执行：

```powershell
cmd /c ".\gradlew testDebugUnitTest lintDebug --no-daemon --no-build-cache"
```

需要产出可安装包时再执行完整构建：

```powershell
cmd /c ".\gradlew clean assembleDebug --no-daemon --no-build-cache"
```

请在至少一台 `arm64-v8a` 真机上检查：VPN 授权、启动/停止、切换节点、订阅导入、DNS 编辑、按应用分流、深浅主题和弹窗圆角背景。

## 常见问题

### 编译时报找不到 `io.nekohasekai.libbox`

通常是没有下载 AAR，或文件不在 `app/libs/libbox.aar`。重新执行“获取 `libbox.aar`”步骤后执行 Gradle Sync / 重新构建。

### 修改代码后 APK 看起来仍是旧版本

本项目要求使用 `--no-build-cache`。优先使用 `build-arm64.bat`，它已包含 `clean` 和该参数。

### VPN 已连接但所有流量无法访问

先查看诊断日志。常见原因包括节点不可用、网络未授权、DNS 配置不通或 libbox 无法识别物理网卡。`TunnelService.getInterfaces()`、`autoDetectInterfaceControl()` 和 `protect()` 的返回行为不能随意简化，否则可能出现 `no available network interface` 或出站回环。

### 订阅更新后显示 0 个节点

服务商可能根据 User-Agent 返回不同格式。应用会按 URI 列表、Clash YAML、sing-box JSON 自动识别；仍失败时请在诊断日志中检查响应格式、订阅可访问性和节点协议是否受当前内核支持。

### 更新 libbox 后无法编译或启动

不要只替换 AAR。先核对新版本的 `PlatformInterface` 方法签名、sing-box JSON 配置迁移说明和所需 feature tags，再同步更新 `TunnelService`、`SingBoxConfigBuilder` 与测试。

## 安全与仓库卫生

- `.gitignore` 会忽略 `libbox.aar`、keystore、签名属性、本机 SDK、构建输出、日志、截图以及本地维护文档。
- `AGENTS.md` 是本地维护上下文，可能含开发环境或签名信息，不能提交到公开仓库。
- 从前曾经提交过敏感文件时，仅修改 `.gitignore` 不会移除 Git 历史；应立即轮换凭据，并使用 `git rm --cached <文件>` 停止后续跟踪。
- 使用 sing-box / libbox 时，请遵守其上游许可证及所在地适用的法律、网络和服务条款。
