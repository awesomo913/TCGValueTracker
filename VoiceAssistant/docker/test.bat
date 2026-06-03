@echo off
:: VoiceAssistant Docker test launcher (Windows)
::
:: Usage (from this directory):
::   test.bat                        — smoke test, host emulator or USB device
::   test.bat integration            — integration test (needs API keys on device)
::   test.bat smoke 192.168.1.42     — smoke via TCP to phone at that IP
::   test.bat integration 192.168.1.42
::
:: Prerequisites on Windows host:
::   1. Docker Desktop running
::   2. adb.exe on PATH (from C:\Android\Sdk\platform-tools)
::   3. adb start-server  (auto-starts on first adb command)
::   4. Phone USB-connected, or emulator running, or TCP enabled on phone

setlocal

set TEST_LEVEL=%1
if "%TEST_LEVEL%"=="" set TEST_LEVEL=smoke

set DEVICE_IP=%2

if not "%DEVICE_IP%"=="" (
    echo TCP mode: connecting to %DEVICE_IP%:5555
    set DEVICE_IP=%DEVICE_IP%
) else (
    echo Host relay mode: using host adb server
)

:: Ensure the host adb server is running so the container can reach it
adb start-server

:: Build image and run
docker-compose -f "%~dp0docker-compose.yml" up --build --abort-on-container-exit

endlocal
