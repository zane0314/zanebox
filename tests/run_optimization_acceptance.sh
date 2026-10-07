#!/usr/bin/env bash
# Fresh S25+ wrapper only; all fixtures public and local.
set -Eeuo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
out="$1"
adb=(/opt/homebrew/bin/adb -s "$ANDROID_SERIAL")
mkdir -p "$out"
python3 -B "$root/tests/network_fixture.py" --delay-b-ms 250 --log "$out/fixture.jsonl" >"$out/fixture.stderr" 2>&1 &
fixture=$!
trap 'kill "$fixture" 2>/dev/null || true' EXIT
"${adb[@]}" install -r "$root/app/build/outputs/apk/debug/app-debug.apk" >"$out/install.txt"
"${adb[@]}" install -r "$root/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" >>"$out/install.txt"
"${adb[@]}" install -r "$root/tests/probe/build/outputs/apk/debug/ZaneProbe-debug.apk" >>"$out/install.txt"
"${adb[@]}" shell pm grant com.zane.zanebox android.permission.POST_NOTIFICATIONS
"${adb[@]}" shell settings put global captive_portal_mode 0
"${adb[@]}" shell settings put global private_dns_mode off
"${adb[@]}" reverse tcp:19082 tcp:19082
"${adb[@]}" shell find /sdcard/Android/data/com.zane.zanebox/files/ -type f >"$out/device-files-before.txt" 2>/dev/null || true
"${adb[@]}" logcat -c
classes="${ZANE_OPT_CLASSES:-OptimizationStoreTest,OptimizationRuntimeTest,ZaneStoreV2Test,ZaneStoreConcurrencyTest,NativeSchemaTest,AcceptanceTest,RuntimeRegressionTest,AutomaticApplyTest,SubscriptionBackgroundTest}"
classes=$(python3 -c 'import sys;print(",".join("com.zane.zanebox."+c for c in sys.argv[1].split(",")))' "$classes")
"${adb[@]}" shell am instrument -w -r -e class "$classes" com.zane.zanebox.test/androidx.test.runner.AndroidJUnitRunner >"$out/instrumentation.txt" 2>&1
kill "$fixture";wait "$fixture" 2>/dev/null || true
python3 -B "$root/tests/network_fixture.py" --delay-a-ms 250 --log "$out/selection-fixture.jsonl" >"$out/selection-fixture.stderr" 2>&1 &
fixture=$!
sleep 1
"${adb[@]}" shell am instrument -w -r -e class com.zane.zanebox.SelectionCacheTest com.zane.zanebox.test/androidx.test.runner.AndroidJUnitRunner >"$out/selection-instrumentation.txt" 2>&1
"${adb[@]}" logcat -d >"$out/logcat.txt"
"${adb[@]}" shell find /sdcard/Android/data/com.zane.zanebox/files/ -type f >"$out/device-files-after.txt"
python3 - "$out" <<'CREATED'
import json,pathlib,sys
p=pathlib.Path(sys.argv[1]);before=set((p/'device-files-before.txt').read_text().splitlines());after=set((p/'device-files-after.txt').read_text().splitlines());(p/'created-device-files.json').write_text(json.dumps(sorted(after-before)))
CREATED
"${adb[@]}" pull /sdcard/Android/data/com.zane.zanebox/files/ "$out/device-files" >"$out/pull.txt" 2>&1
python3 -B "$root/tests/verify_device_evidence.py" "$out"
python3 - "$out" <<'PY'
import pathlib,sys,json,re
p=pathlib.Path(sys.argv[1]);s=(p/'instrumentation.txt').read_text();print(s[-5000:])
assert 'OK (' in s and 'FAILURES!!!' not in s,'instrumentation failed'
extra=(p/'selection-instrumentation.txt').read_text();print(extra[-1200:]);assert 'OK (' in extra and 'FAILURES!!!' not in extra,'selection instrumentation failed'
crash=(p/'logcat.txt').read_text();assert not re.search(r'FATAL EXCEPTION[^\n]*\n(?:[^\n]*\n){0,6}[^\n]*com\.zane\.(zanebox|probe)',crash),'app crash'
(p/'result.json').write_text(json.dumps({'status':'PASS','instrumentationTests':int(re.search(r'OK \((\d+) tests?\)',s)[1])+int(re.search(r'OK \((\d+) tests?\)',extra)[1])},indent=2))
PY
python3 -B "$root/tests/run_maestro_ui.py" optimization version --output "$out/maestro.json"
echo OPTIMIZATION_DEVICE_PASS
