#!/usr/bin/env bash
# Use only through with_s25_emulator.sh; controlled exits, no personal subscriptions.
set -Eeuo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
out="$1"
adb=(/opt/homebrew/bin/adb -s "$ANDROID_SERIAL")
mkdir -p "$out"
python3 "$root/tests/network_fixture.py" --delay-b-ms 250 --log "$out/fixture.jsonl" >"$out/fixture.stderr" 2>&1 &
fixture=$!
trap 'kill "$fixture" 2>/dev/null || true' EXIT
"${adb[@]}" install -r "${ZANE_SUB_APK:-$root/app/build/outputs/apk/debug/app-debug.apk}" >"$out/install.txt"
"${adb[@]}" install -r "$root/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" >>"$out/install.txt"
"${adb[@]}" shell pm grant com.zane.zanebox android.permission.POST_NOTIFICATIONS
"${adb[@]}" shell settings put global captive_portal_mode 0
"${adb[@]}" shell settings put global private_dns_mode off
"${adb[@]}" reverse tcp:19082 tcp:19082
"${adb[@]}" logcat -c
"${adb[@]}" shell am instrument -w -r -e class "${ZANE_SUB_CLASSES:-com.zane.zanebox.SubscriptionBackgroundTest}" com.zane.zanebox.test/androidx.test.runner.AndroidJUnitRunner >"$out/instrumentation.txt" 2>&1
"${adb[@]}" logcat -d >"$out/logcat.txt"
python3 - "$out/instrumentation.txt" <<'PY'
import pathlib,sys
text=pathlib.Path(sys.argv[1]).read_text()
print(text[-2500:])
assert 'OK (' in text and 'FAILURES!!!' not in text, 'instrumentation failed'
PY
"${adb[@]}" pull /sdcard/Android/data/com.zane.zanebox/files/ "$out/device-files" >"$out/pull.txt" 2>&1
python3 - "$out" <<'PY'
import hashlib,json,os,pathlib,subprocess,sys
out=pathlib.Path(sys.argv[1]);adb=['/opt/homebrew/bin/adb','-s',os.environ['ANDROID_SERIAL']]
created=['subscription-background-live-result.json','subscription-background-auto-result.json','subscription-repeat-false-result.json','subscription-repeat-true-result.json','subscription-parallel-result.json','subscription-background-result.json']
evidence=[]
for name in created:
    local=out/'device-files'/name
    if not local.exists():continue
    device='/sdcard/Android/data/com.zane.zanebox/files/'+name
    sha=hashlib.sha256(local.read_bytes()).hexdigest()
    assert subprocess.check_output(adb+['shell','sha256sum',device],text=True).split()[0]==sha
    evidence.append({'device':device,'host':str(local),'sha256':sha})
(out/'evidence-transfers.json').write_text(json.dumps(evidence,indent=2))
for item in evidence:subprocess.check_call(adb+['shell','rm',item['device']])
PY
if [[ "${ZANE_SUB_MAESTRO:-0}" == 1 ]]; then
    python3 "$root/tests/run_maestro_ui.py" subscription-connect version --output "$out/maestro-result.json"
    python3 "$root/tests/capture_power_feedback.py" "$out/power-feedback"
fi
