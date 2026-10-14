#!/usr/bin/env bash
set -Eeuo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
out=${1:?usage: run_target_latency_acceptance.sh OUTPUT PROBE_APK}
probe=${2:?probe APK required}
adb=/opt/homebrew/bin/adb
serial=${ANDROID_SERIAL:?run inside with_s25_emulator.sh}
[[ "$serial" == emulator-5580 ]]
[[ "$("$adb" -s "$serial" emu avd name | tr -d '\r' | head -n 1)" == ZaneBox_S25Plus_API35_Acceptance ]]
[[ -f "$root/app/build/outputs/apk/debug/app-debug.apk" && -f "$root/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" && -f "$probe" ]]
mkdir -p "$out"
# This app instance belongs to the disposable acceptance emulator.
if "$adb" -s "$serial" shell pm path com.zane.zanebox | grep -q '^package:'; then
    "$adb" -s "$serial" uninstall com.zane.zanebox >"$out/remove-old-test-install.txt"
fi
"$adb" -s "$serial" install "$root/app/build/outputs/apk/debug/app-debug.apk"
"$adb" -s "$serial" install -r "$root/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
"$adb" -s "$serial" install -r "$probe"
"$adb" -s "$serial" shell pm grant com.zane.zanebox android.permission.POST_NOTIFICATIONS
"$adb" -s "$serial" shell appops set com.zane.zanebox ACTIVATE_VPN allow
classes=${TARGET_LATENCY_CLASSES:-com.zane.zanebox.TargetLatencyRuntimeTest,com.zane.zanebox.TargetLatencyUiTest,com.zane.zanebox.LatencyBatchRegressionTest,com.zane.zanebox.SmartTargetPickerTest}
expected=${TARGET_LATENCY_EXPECTED:-9}
"$adb" -s "$serial" shell am instrument -w -r -e class "$classes" com.zane.zanebox.test/androidx.test.runner.AndroidJUnitRunner >"$out/instrumentation.txt" 2>&1
cat "$out/instrumentation.txt"
grep -Eq "OK \\($expected tests?\\)" "$out/instrumentation.txt"
! grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_RESULT: shortMsg=' "$out/instrumentation.txt"
for name in runtime offline isolation; do
    "$adb" -s "$serial" exec-out run-as com.zane.zanebox cat "cache/target-latency-$name.json" >"$out/$name.json"
done
"$adb" -s "$serial" shell am instrument -w -r -e class com.zane.zanebox.TargetLatencyRuntimeTest#seedForMaestro com.zane.zanebox.test/androidx.test.runner.AndroidJUnitRunner >"$out/seed.txt" 2>&1
grep -Eq 'OK \(1 test\)' "$out/seed.txt"
"$adb" -s "$serial" shell am force-stop com.zane.zanebox
mkdir -p "$out/seed"
"$adb" -s "$serial" pull /data/user/0/com.zane.zanebox/databases/zanebox.db "$out/seed/zanebox.db"
for suffix in -wal -shm; do
    if "$adb" -s "$serial" shell test -f "/data/user/0/com.zane.zanebox/databases/zanebox.db$suffix"; then
        "$adb" -s "$serial" pull "/data/user/0/com.zane.zanebox/databases/zanebox.db$suffix" "$out/seed/zanebox.db$suffix"
    fi
done
python3 - "$out/seed/zanebox.db" <<'PY'
import sqlite3,sys
with sqlite3.connect(sys.argv[1]) as db:
    db.execute('pragma wal_checkpoint(TRUNCATE)')
    assert db.execute('select count(*) from node_status').fetchone()[0]>=2
PY
printf '%s\n' TARGET_LATENCY_INSTRUMENTATION_PASS
