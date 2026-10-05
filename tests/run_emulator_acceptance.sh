#!/usr/bin/env bash
# Invoke only inside this project's with_s25_emulator.sh.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
ADB=${ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}
OUT=${1:-"$ROOT/reports/emulator-$(date +%Y%m%d-%H%M%S)"}
mkdir -p "$OUT"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
TEST="$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
PROBE=$(find "$ROOT/tests/probe/build/outputs/apk/debug" -maxdepth 1 -name "*-debug.apk" -print -quit 2>/dev/null || true)
for f in "$APK" "$TEST" "$PROBE"; do test -f "$f" || { echo "缺少构建产物 $f";exit 2; };done
python3 "$ROOT/tests/network_fixture.py" --log "$OUT/fixture.jsonl" >"$OUT/fixture.stderr" 2>&1 &
FIXTURE=$!
trap 'kill "$FIXTURE" 2>/dev/null || true' EXIT
sleep 1
kill -0 "$FIXTURE"
"$ADB" install -r "$APK" >"$OUT/install.txt"
"$ADB" install -r "$TEST" >>"$OUT/install.txt"
"$ADB" install -r "$PROBE" >>"$OUT/install.txt"
"$ADB" shell pm clear com.zane.zanebox >>"$OUT/install.txt"
"$ADB" shell pm grant com.zane.zanebox android.permission.POST_NOTIFICATIONS
# Fixtures have controlled local exits; public reachability probes must not change network scores.
"$ADB" shell settings put global captive_portal_mode 0
"$ADB" shell settings put global private_dns_mode off
"$ADB" logcat -c
set +e
"$ADB" shell am instrument -w -r -e class "${ZANE_TEST_CLASSES:-com.zane.zanebox.AcceptanceTest,com.zane.zanebox.NativeSchemaTest,com.zane.zanebox.LegacyParcelInstrumentedTest,com.zane.zanebox.RuntimeRegressionTest,com.zane.zanebox.SubscriptionBackgroundTest,com.zane.zanebox.ZaneStoreConcurrencyTest,com.zane.zanebox.IncomingIntentTest,com.zane.zanebox.SelectionCacheTest,com.zane.zanebox.DocumentQrTest,com.zane.zanebox.ZaneStoreV2Test}" com.zane.zanebox.test/androidx.test.runner.AndroidJUnitRunner >"$OUT/instrumentation.txt" 2>&1
RC=$?
set -e
"$ADB" logcat -d >"$OUT/logcat.txt"
"$ADB" shell dumpsys package com.zane.zanebox >"$OUT/package.txt"
"$ADB" shell dumpsys connectivity >"$OUT/connectivity.txt"
"$ADB" pull /sdcard/Android/data/com.zane.zanebox/files/ "$OUT/device-files" >"$OUT/pull.txt" 2>&1 || true
shasum -a 256 "$APK" "$TEST" "$PROBE" "$ROOT/native/OwnBoxForAndroid/libcore/.build/libcore.aar" >"$OUT/hashes.txt"
cp "$ROOT/version.properties" "$OUT/version.properties"
"$ADB" shell run-as com.zane.zanebox cat cache/neko.log >"$OUT/native-runtime.log" 2>&1 || true
python3 - "$OUT" "$RC" "${ZANE_TEST_COUNT:-12}" <<'PY'
import pathlib,re,sys,json
p=pathlib.Path(sys.argv[1]);text=(p/'instrumentation.txt').read_text();crash=(p/'logcat.txt').read_text()
reasons=[]
if sys.argv[2]!='0' or not re.search(r'OK \('+re.escape(sys.argv[3])+r' tests?\)',text):reasons.append('instrumentation没有完整通过')
if re.search(r'FATAL EXCEPTION[^\n]*\n(?:[^\n]*\n){0,6}[^\n]*com\.zane\.(zanebox|probe)',crash):reasons.append('发现应用/Probe崩溃')
if not list((p/'device-files').rglob('acceptance-large-font.png')):reasons.append('缺少大字号截图')
result={'status':'FAIL' if reasons else 'PASS','reasons':reasons,'scope':'AcceptanceTest断言范围；未覆盖项见emulator-acceptance-design.md'}
(p/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2));print(json.dumps(result,ensure_ascii=False));sys.exit(bool(reasons))
PY
