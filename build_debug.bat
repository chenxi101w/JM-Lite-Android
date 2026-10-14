@echo off
chcp 65001 >nul
setlocal
REM ============================================================
REM  JM 轻量版 一键调试构建（双击即跑，免设环境变量）
REM  本机无独立 JDK，统一用 Android Studio 自带 JBR。
REM  注意：JBR 路径随 D:\code 目录重排迁移到 D:\code\APP\Studio\jbr（原
REM        D:\code\Android\APP\android-studio\jbr 已不存在）。
REM  Gradle 缓存统一走系统环境变量 GRADLE_USER_HOME = D:\code\cache\.gradle。
REM ============================================================
set "JAVA_HOME=D:\code\APP\Studio\jbr"

REM 切到本批处理所在目录（即项目根），无论从哪双击都能正确定位
cd /d "%~dp0"

echo [build] 使用 JDK : %JAVA_HOME%
echo [build] 工程目录 : %CD%
echo [build] 开始 assembleDebug（单 ABI arm64-v8a，不编测试 APK）...
echo.

call gradlew.bat :app:assembleDebug %*
if %errorlevel% neq 0 (
    echo.
    echo [build] !! 构建失败，错误码 %errorlevel%
    echo [build] 请把上面的日志发给我（W先生）诊断。
    pause
    exit /b %errorlevel%
)

echo.
echo [build] 构建成功！APK 输出位置：
echo   %CD%app\build\outputs\apk\debug\app-debug.apk
echo.
echo [build] 想同时拷一份到共享 APK 目录（D:\code\CODE\AI-Android\apk）：
echo         gradlew.bat copyApkToD
echo.
echo [build] 用数据线连上真机后，可直接把上面 APK 拖进手机安装，
echo        或在 Android Studio 里点 Run 安装调试。
pause
