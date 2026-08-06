@echo off
chcp 65001 >nul
setlocal

rem =====================================================
rem 一键构建 arm64-v8a 调试版 APK
rem =====================================================

set "PROJECT_DIR=%~dp0"
cd /d "%PROJECT_DIR%"

rem 给 Gradle 分配 4GB 内存，避免 libbox 构建时 OOM
set GRADLE_OPTS=-Xmx4g -XX:MaxMetaspaceSize=512m

rem 清理并构建仅 arm64-v8a 的 debug APK
rem --no-build-cache 必须加：否则 Kotlin 编译被缓存，改了代码也不生效
.\gradlew clean assembleDebug --no-daemon --no-build-cache

if %ERRORLEVEL% neq 0 (
    echo.
    echo [ERROR] 构建失败，请查看上方日志。
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo [OK] 构建成功！APK 输出路径：
echo   %PROJECT_DIR%app\build\outputs\apk\debug\app-debug.apk

pause
endlocal
