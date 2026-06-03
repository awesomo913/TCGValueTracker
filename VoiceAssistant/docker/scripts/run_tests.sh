#!/usr/bin/env bash
# VoiceAssistant docker test runner.
#
# Two modes (set via env):
#   TCP direct  — DEVICE_IP=<phone-ip>  (phone has adb TCP enabled, no USB needed)
#   Host relay  — DEVICE_IP=            (relay through Windows host's adb server on host.docker.internal:5037)
#
# Test levels:
#   smoke       — install + no crash + service started   (no API keys needed)
#   integration — + STT connected + speaker filter active (needs Deepgram + Groq keys on device)
#
set -euo pipefail

APK_PATH="${APK_PATH:-/apk/app-debug.apk}"
DEVICE_IP="${DEVICE_IP:-}"
ADB_SERVER_HOST="${ADB_SERVER_HOST:-host.docker.internal}"
ADB_SERVER_PORT="${ADB_SERVER_PORT:-5037}"
LOG_SECONDS="${LOG_SECONDS:-20}"
TEST_LEVEL="${TEST_LEVEL:-smoke}"
PACKAGE="com.owner.assist"
ACTIVITY=".MainActivity"
LOGCAT_FILE="/tmp/va_logcat.txt"

echo "======================================="
echo "VoiceAssistant Docker Test Runner"
echo "======================================="
echo "APK       : $APK_PATH"
echo "Test level: $TEST_LEVEL"
echo "Log secs  : $LOG_SECONDS"
echo ""

# --- Connect to device ---

if [[ -n "$DEVICE_IP" ]]; then
    # TCP direct: connect straight to the phone on LAN
    echo "Mode: TCP direct -> $DEVICE_IP:5555"
    adb connect "$DEVICE_IP:5555"
    sleep 2
    ADB="adb -s $DEVICE_IP:5555"
else
    # Host relay: use the adb server running on the Windows host
    echo "Mode: host relay -> $ADB_SERVER_HOST:$ADB_SERVER_PORT"
    ADB="adb -H $ADB_SERVER_HOST -P $ADB_SERVER_PORT"
fi

# --- Wait for a device to be ready ---

echo ""
echo "Waiting for device (60s timeout)..."
timeout 60 $ADB wait-for-device || {
    echo "ERROR: no device appeared within 60 seconds"
    exit 1
}
DEVICE_MODEL=$($ADB shell getprop ro.product.model 2>/dev/null || echo "unknown")
ANDROID_VER=$($ADB shell getprop ro.build.version.release 2>/dev/null || echo "?")
echo "Device  : $DEVICE_MODEL (Android $ANDROID_VER)"

# --- Install APK ---

echo ""
echo "Installing APK..."
$ADB install -r "$APK_PATH"
echo "Install  : OK"

# --- Start logcat collection BEFORE launching app so we catch early startup logs ---

$ADB logcat -c   # clear stale entries

echo ""
echo "Starting logcat collection (${LOG_SECONDS}s)..."
timeout "$LOG_SECONDS" $ADB logcat -v time \
    Orchestrator:I \
    SpeakerFilter:I \
    TriageClient:D \
    DeepgramSttClient:I \
    AssistantService:I \
    BluetoothScoManager:I \
    AndroidRuntime:E \
    '*:S' \
    > "$LOGCAT_FILE" &
LOGCAT_PID=$!

echo "Launching $PACKAGE/$ACTIVITY..."
$ADB shell am start -n "$PACKAGE/$ACTIVITY"

# Wait for logcat collection to finish
wait $LOGCAT_PID || true   # exit 124 from timeout is expected

LINE_COUNT=$(wc -l < "$LOGCAT_FILE")
echo "Collected: $LINE_COUNT lines"
echo ""

# --- Verify ---

python3 /workspace/scripts/verify_logcat.py "$LOGCAT_FILE" "$TEST_LEVEL"
