@echo off
chcp 65001 >nul
rem ---------------------------------------------------------------
rem  启动 Android 模拟器 (AVD: Pixel_Tablet)
rem
rem  关键点:
rem  1) ANDROID_EMULATOR_FEATURES=-WiFiPacketStream
rem     必须禁用 WiFi 报文流特性。否则 netsimd 反复崩溃重启,
rem     会让 QEMU 主循环与 4 个 vCPU 线程全部死锁, guest 永远
rem     停在 "offline", 表现就是"点了启动没反应"。
rem  2) -gpu swiftshader
rem     宿主是 NVIDIA RTX 5060 (Blackwell), 走 host GPU 会在
rem     libgfxstream_backend.dll 里触发空指针写崩溃。
rem  3) -no-snapshot-load / -no-snapshot-save
rem     AVD 里那份 4GB 脏快照与 2GB 配置不匹配, 会撑爆内存。
rem ---------------------------------------------------------------
setlocal

set "ANDROID_EMULATOR_FEATURES=-WiFiPacketStream"
set "QT_OPENGL=software"
set "QT_QUICK_BACKEND=software"

set "EMU=%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe"
if not exist "%EMU%" (
    echo [ERROR] 找不到模拟器: %EMU%
    pause
    exit /b 1
)

start "Android Emulator" "%EMU%" -avd Pixel_Tablet -gpu swiftshader -no-boot-anim -no-audio -no-snapshot-load -no-snapshot-save
echo 模拟器启动中, 等待约 1-2 分钟进入系统...
endlocal
