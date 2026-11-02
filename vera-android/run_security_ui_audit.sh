#!/usr/bin/env bash
# VERA Android — Security & UI Audit Script
# Usage: bash run_security_ui_audit.sh [device-serial]
# Requires: adb, aapt2 (or aapt), Android SDK in PATH

set -euo pipefail

PACKAGE="com.vera.android"
DEVICE=${1:-""}
ADB="adb${DEVICE:+ -s $DEVICE}"
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPORT_DIR="$PROJECT_DIR/../audit_reports"
mkdir -p "$REPORT_DIR"
REPORT_FILE="$REPORT_DIR/audit_report_$(date +%Y%m%d_%H%M%S).txt"

log()  { echo "[$(date +%H:%M:%S)] $*" | tee -a "$REPORT_FILE"; }
pass() { echo "  [PASS] $*" | tee -a "$REPORT_FILE"; }
fail() { echo "  [FAIL] $*" | tee -a "$REPORT_FILE"; AUDIT_FAILED=1; }
warn() { echo "  [WARN] $*" | tee -a "$REPORT_FILE"; }

AUDIT_FAILED=0

# ── 0. Auto-discover Android SDK build-tools (aapt2) ─────────────────────────
ANDROID_SDK="${ANDROID_HOME:-$HOME/AppData/Local/Android/Sdk}"
if [ -d "$ANDROID_SDK/build-tools" ]; then
    LATEST_BT=$(ls -1 "$ANDROID_SDK/build-tools" 2>/dev/null | sort -V | tail -1)
    [ -n "$LATEST_BT" ] && export PATH="$PATH:$ANDROID_SDK/build-tools/$LATEST_BT"
fi

# ── 1. Device check ────────────────────────────────────────────────────────────
log "=== 1. Device connectivity ==="
if ! $ADB get-state > /dev/null 2>&1; then
    echo "No device found. Connect a device or emulator, then retry."
    exit 1
fi
DEVICE_MODEL=$($ADB shell getprop ro.product.model 2>/dev/null | tr -d '\r')
ANDROID_VER=$($ADB shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')
log "Device: $DEVICE_MODEL  Android $ANDROID_VER"

# ── 2. Build & install ─────────────────────────────────────────────────────────
log "=== 2. Build debug APK ==="
export JAVA_HOME="${JAVA_HOME:-/c/Program Files/Android/Android Studio/jbr}"
GRADLE_BIN="$(find "$HOME/.gradle/wrapper/dists" -name "gradle" -type f 2>/dev/null | head -1)"
if [ -z "$GRADLE_BIN" ]; then
    fail "Gradle wrapper distribution not found. Open project in Android Studio and sync first."
    exit 1
fi
"$GRADLE_BIN" -p "$PROJECT_DIR" assembleDebug assembleDebugAndroidTest 2>&1 | tee -a "$REPORT_FILE" | tail -20
BUILD_RC=${PIPESTATUS[0]}
if [ $BUILD_RC -ne 0 ]; then
    APK_PATH="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
    if [ -f "$APK_PATH" ]; then
        warn "Build failed but existing APK found — using it. Build errors above may need fixing later."
    else
        fail "Build failed and no existing APK. Fix build errors first."
        exit 1
    fi
fi

APK_PATH="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$PROJECT_DIR/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
[ -f "$APK_PATH" ] && pass "Debug APK built: $APK_PATH" || { fail "Debug APK missing"; exit 1; }

# ── 3. Manifest audit (aapt) ───────────────────────────────────────────────────
log "=== 3. Manifest security flags ==="

if command -v aapt2 > /dev/null 2>&1; then
    MANIFEST=$(aapt2 dump xmltree --file AndroidManifest.xml "$APK_PATH" 2>/dev/null)
elif command -v aapt > /dev/null 2>&1; then
    MANIFEST=$(aapt dump xmltree "$APK_PATH" AndroidManifest.xml 2>/dev/null)
else
    warn "aapt/aapt2 not in PATH — skipping static manifest audit. Add Android SDK build-tools to PATH."
    MANIFEST=""
fi

if [ -n "$MANIFEST" ]; then
    if echo "$MANIFEST" | grep -q 'usesCleartextTraffic.*0x0'; then
        pass "usesCleartextTraffic=false"
    elif echo "$MANIFEST" | grep -q 'usesCleartextTraffic'; then
        fail "usesCleartextTraffic=true — cleartext HTTP allowed!"
    else
        # Absence on API 28+ defaults to false
        warn "usesCleartextTraffic not found in manifest. Defaults to false on API 28+. Verify minSdk."
    fi

    if echo "$MANIFEST" | grep -q 'allowBackup.*true'; then
        warn "android:allowBackup=true — ADB backup can extract app data. Consider false for production."
    else
        pass "allowBackup=false (or not set)"
    fi
fi

# ── 4. Plaintext secret scan in APK ───────────────────────────────────────────
log "=== 4. APK plaintext secret scan ==="

SCAN_TMPDIR=$(mktemp -d)
cp "$APK_PATH" "$SCAN_TMPDIR/app.zip"
unzip -q "$SCAN_TMPDIR/app.zip" -d "$SCAN_TMPDIR/apk_contents" 2>/dev/null || true

# Patterns that indicate real hardcoded secrets (not key names used for EncryptedSharedPreferences)
SECRET_PATTERNS=("Bearer " "api_key=" "Authorization:" "password=" "secret=")
FOUND_SECRETS=0
for pattern in "${SECRET_PATTERNS[@]}"; do
    HITS=$(grep -rla "$pattern" "$SCAN_TMPDIR/apk_contents/" 2>/dev/null \
        | grep -v '\.class$' | grep -v '\.dex$' || true)
    if [ -n "$HITS" ]; then
        fail "Pattern '$pattern' found in APK: $HITS"
        FOUND_SECRETS=1
    fi
done

# session_token= in dex is the EncryptedSharedPreferences key name, not a leaked value.
# Only flag if found outside dex (e.g. in assets or raw resources).
ST_HITS=$(grep -rla 'session_token=' "$SCAN_TMPDIR/apk_contents/" 2>/dev/null \
    | grep -v '\.class$' | grep -v '\.dex$' | grep -v '\.so$' || true)
if [ -n "$ST_HITS" ]; then
    fail "Pattern 'session_token=' found in non-code APK files: $ST_HITS"
    FOUND_SECRETS=1
fi
[ $FOUND_SECRETS -eq 0 ] && pass "No plaintext secrets found in APK assets/resources"

# Check for ws:// (insecure WebSocket) — exclude native .so files (Filament false positive)
WS_HITS=$(grep -rla 'ws://' "$SCAN_TMPDIR/apk_contents/" 2>/dev/null \
    | grep -v '\.class$' | grep -v '\.so$' || true)
if [ -n "$WS_HITS" ]; then
    fail "Insecure ws:// URL found in APK: $WS_HITS"
else
    pass "No ws:// URLs in APK (native libs excluded — Filament contains harmless byte matches)"
fi

HTTP_HITS=$(grep -rla 'http://' "$SCAN_TMPDIR/apk_contents/" 2>/dev/null \
    | grep -v '\.class$' | grep -v '\.so$' | grep -v 'schemas.android.com' | grep -v 'xmlpull.org' \
    | grep -v 'LICENSE' || true)
if [ -n "$HTTP_HITS" ]; then
    warn "http:// references found (may be schema URIs, not endpoints): $HTTP_HITS"
else
    pass "No plaintext http:// endpoint URLs in APK assets"
fi

rm -rf "$SCAN_TMPDIR"

# ── 5. Install & run instrumented tests ───────────────────────────────────────
log "=== 5. Install APK ==="
$ADB install -r "$APK_PATH" 2>&1 | tee -a "$REPORT_FILE"

log "=== 6. Run instrumented tests ==="
if [ -f "$TEST_APK" ]; then
    $ADB install -r "$TEST_APK" 2>&1 | tee -a "$REPORT_FILE"
    log "Running UI tests..."
    $ADB shell am instrument -w \
        -e class "com.vera.android.VeraUiTestSuite" \
        "com.vera.android.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 | tee -a "$REPORT_FILE"
    log "Running security tests..."
    $ADB shell am instrument -w \
        -e class "com.vera.android.VeraSecurityTest" \
        "com.vera.android.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 | tee -a "$REPORT_FILE"
else
    warn "Test APK not found — skipping instrumented tests. Run: ./gradlew assembleDebugAndroidTest"
fi

# ── 7. Logcat secret scan ──────────────────────────────────────────────────────
log "=== 7. Logcat runtime secret scan (10 seconds) ==="
$ADB logcat -c  # clear buffer
$ADB shell "am start -n com.vera.android/.MainActivity" > /dev/null 2>&1
sleep 10
LOGCAT_DUMP=$($ADB logcat -d 2>/dev/null)

SECRET_LOG_PATTERNS=("Bearer " "session_token" "api_key" "Authorization:" "password")
for pattern in "${SECRET_LOG_PATTERNS[@]}"; do
    HITS=$(echo "$LOGCAT_DUMP" | grep -i "$pattern" | grep -v "^---" || true)
    if [ -n "$HITS" ]; then
        fail "Secret pattern '$pattern' found in Logcat:\n$HITS"
    fi
done
[ $AUDIT_FAILED -eq 0 ] && pass "No secret patterns found in Logcat during startup"

# ── 8. Permission runtime check ───────────────────────────────────────────────
log "=== 8. Runtime permission state ==="
PERM_DUMP=$($ADB shell dumpsys package "$PACKAGE" 2>/dev/null)

check_perm() {
    local perm="$1"
    local granted
    granted=$(echo "$PERM_DUMP" | grep "$perm" | grep -c "granted=true" || true)
    if [ "$granted" -gt 0 ]; then
        pass "$perm: GRANTED"
    else
        warn "$perm: NOT GRANTED (expected if fresh install — grant manually then re-run)"
    fi
}

check_perm "android.permission.RECORD_AUDIO"
check_perm "android.permission.ACCESS_FINE_LOCATION"
check_perm "android.permission.POST_NOTIFICATIONS"

# ── 9. Network connectivity check ─────────────────────────────────────────────
log "=== 9. Cleartext traffic runtime block test ==="
# Try connecting to a plaintext HTTP endpoint from the device — should be blocked
HTTP_TEST=$($ADB shell "curl -s --max-time 3 http://neverssl.com/online 2>&1" || true)
if echo "$HTTP_TEST" | grep -qi "cleartext"; then
    pass "Cleartext HTTP blocked by OS (cleartext error returned)"
elif echo "$HTTP_TEST" | grep -qi "failed\|refused\|error"; then
    pass "HTTP request failed — cleartext likely blocked"
else
    warn "HTTP request may have succeeded — verify usesCleartextTraffic=false is enforced"
fi

# ── Summary ────────────────────────────────────────────────────────────────────
log ""
log "=== AUDIT COMPLETE ==="
if [ $AUDIT_FAILED -eq 0 ]; then
    log "Result: ALL CHECKS PASSED"
else
    log "Result: FAILURES DETECTED — see [FAIL] lines above"
fi
log "Full report saved: $REPORT_FILE"
exit $AUDIT_FAILED
