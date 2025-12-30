@echo off
echo Watching logs...
echo.

REM Clear log buffer first
..\..\..\AppData\Local\Android\Sdk\platform-tools\adb logcat -c

REM Show logs from tunas
..\..\..\AppData\Local\Android\Sdk\platform-tools\adb logcat -v brief "Tunas:V AndroidRuntime:E *:S"
