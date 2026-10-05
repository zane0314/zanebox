#!/usr/bin/env bash
# Invoke only inside this project's with_s25_emulator.sh.
# Runs IdleCpuProbeTest against BASELINE_APK and the current debug APK with the same current test APK.
# Emulator CPU ticks are relative evidence only; they do not represent real-device battery use.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
ADB=${ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}
OUT=${1:-"$ROOT/reports/perf-$(date +%Y%m%d-%H%M%S)"}
BASELINE_APK=${BASELINE_APK:?set BASELINE_APK}
CURRENT_APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
TEST="$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
IDLE_SECONDS=${IDLE_SECONDS:-60}
mkdir -p "$OUT"
for f in "$BASELINE_APK" "$CURRENT_APK" "$TEST"; do test -f "$f" || { echo "缺少构建产物 $f";exit 2; };done
python3 "$ROOT/tests/network_fixture.py" --log "$OUT/fixture.jsonl" >"$OUT/fixture.stderr" 2>&1 &
FIXTURE=$!
trap 'kill "$FIXTURE" 2>/dev/null || true' EXIT
sleep 1
kill -0 "$FIXTURE"
"$ADB" shell settings put global captive_portal_mode 0
"$ADB" shell settings put global private_dns_mode off
RC=0
for entry in "baseline:$BASELINE_APK" "current:$CURRENT_APK"; do
    label=${entry%%:*};apk=${entry#*:}
    "$ADB" uninstall com.zane.zanebox >/dev/null 2>&1 || true
    "$ADB" install "$apk" >"$OUT/install-$label.txt"
    "$ADB" install -r "$TEST" >>"$OUT/install-$label.txt"
    "$ADB" shell pm grant com.zane.zanebox android.permission.POST_NOTIFICATIONS
    "$ADB" logcat -c
    set +e
    "$ADB" shell am instrument -w -r -e class com.zane.zanebox.IdleCpuProbeTest -e perfLabel "$label" -e idleSeconds "$IDLE_SECONDS" com.zane.zanebox.test/androidx.test.runner.AndroidJUnitRunner >"$OUT/instrumentation-$label.txt" 2>&1
    code=$?
    set -e
    grep -q 'OK (1 test)' "$OUT/instrumentation-$label.txt" || RC=1
    [[ $code -eq 0 ]] || RC=1
    "$ADB" logcat -d >"$OUT/logcat-$label.txt"
    "$ADB" pull "/sdcard/Android/data/com.zane.zanebox/files/idle-cpu-$label.json" "$OUT/" >>"$OUT/pull.txt" 2>&1 || RC=1
done
shasum -a 256 "$BASELINE_APK" "$CURRENT_APK" "$TEST" >"$OUT/hashes.txt"
python3 - "$OUT" "$RC" <<'PY'
import json,pathlib,sys
p=pathlib.Path(sys.argv[1]);data={}
for label in ('baseline','current'):
    f=p/f'idle-cpu-{label}.json'
    data[label]=json.loads(f.read_text()) if f.exists() else None
result={'status':'PASS' if sys.argv[2]=='0' and all(data.values()) else 'FAIL','scope':'S25+布局标准Android模拟器相对比较；不代表真机耗电','order':'baseline先运行，current后运行',**data}
(p/'compare.json').write_text(json.dumps(result,ensure_ascii=False,indent=2));print(json.dumps(result,ensure_ascii=False))
sys.exit(0 if result['status']=='PASS' else 1)
PY
