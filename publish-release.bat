@echo off
setlocal
cd /d "%~dp0"

echo.
echo ========================================
echo Pulse GitHub Release Publisher
echo ========================================
echo.

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0publish-release.ps1" %*
set "EXITCODE=%ERRORLEVEL%"

echo.
if not "%EXITCODE%"=="0" (
    echo [ERROR] Release publishing failed.
) else (
    echo [OK] Release publishing completed.
)
pause
exit /b %EXITCODE%
