#!/usr/bin/env bash
# Invoke only inside this project's with_s25_emulator.sh.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
ADB=${ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}
OUT=${1:-"$ROOT/reports/emulator-$(date +%Y%m%d-%H%M%S)"}
classes="${ZANE_TEST_CLASSES:-com.zane.zanebox.AcceptanceTest,com.zane.zanebox.NativeSchemaTest,com.zane.zanebox.LegacyParcelInstrumentedTest,com.zane.zanebox.RuntimeRegressionTest,com.zane.zanebox.SubscriptionBackgroundTest,com.zane.zanebox.ZaneStoreConcurrencyTest,com.zane.zanebox.IncomingIntentTest,com.zane.zanebox.SelectionCacheTest,com.zane.zanebox.DocumentQrTest,com.zane.zanebox.ZaneStoreV2Test,com.zane.zanebox.FunctionFeedbackTest,com.zane.zanebox.PreferenceActionTest,com.zane.zanebox.NodeSwitchLatencyTest,com.zane.zanebox.SelectedNodeDelayUiTest}"
mkdir -p "$OUT"
rm -f "$OUT/result.json"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
TEST="$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
PROBE=$(find "$ROOT/tests/probe/build/outputs/apk/debug" -maxdepth 1 -name "*-debug.apk" -print -quit 2>/dev/null || true)
for f in "$APK" "$TEST" "$PROBE"; do test -f "$f" || { echo "缺少构建产物 $f";exit 2; };done
fixtures=()
cleanup() {
    [[ ${fixtures[*]-} ]] || return 0
    for pid in "${fixtures[@]}"; do kill "$pid" 2>/dev/null || true; done
    for pid in "${fixtures[@]}"; do wait "$pid" 2>/dev/null || true; done
}
trap cleanup EXIT
start_fixture() {
    local name="$1" ports="$2" pid
    shift 2
    python3 - "$ports" <<'FREE'
import socket, sys
for port in map(int,sys.argv[1].split(',')):
    with socket.socket() as sock:
        sock.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
        try: sock.bind(('127.0.0.1',port))
        except OSError as error: sys.exit(f'夹具端口 {port} 不可用，未运行测试：{error}')
FREE
    python3 "$ROOT/tests/$name.py" "$@" --log "$OUT/$name.jsonl" >"$OUT/$name.stderr" 2>&1 &
    pid=$!; fixtures+=("$pid")
    if ! python3 - "$pid" "$ports" <<'READY'
import os, socket, sys, time
pid=int(sys.argv[1]); ports=[int(port) for port in sys.argv[2].split(',')]
deadline=time.monotonic()+10
while time.monotonic()<deadline:
    try: os.kill(pid,0)
    except ProcessLookupError: sys.exit('夹具提前退出')
    ready=True
    for port in ports:
        try:
            with socket.create_connection(('127.0.0.1',port),timeout=.1): pass
        except OSError: ready=False; break
    if ready: sys.exit(0)
    time.sleep(.05)
sys.exit('夹具未在10秒内监听全部所需端口')
READY
    then
        cat "$OUT/$name.stderr" >&2
        echo "夹具未就绪：${name}，未运行仪器测试" >&2
        return 2
    fi
    kill -0 "$pid"
}
network_args=(--delay-a-ms "${ZANE_FIXTURE_DELAY_A_MS:-0}" --delay-b-ms "${ZANE_FIXTURE_DELAY_B_MS:-0}")
if [[ -n "${ZANE_FIXTURE_GEOIP:-}" ]]; then network_args+=(--geoip-fixture "$ZANE_FIXTURE_GEOIP"); fi
start_fixture network_fixture '19080,19081,19082,19085,19086,19087,19088,19089' "${network_args[@]}"
case "$classes" in
    *SelectedNodeDelayUiTest*|*NodeSwitchLatencyTest*|*XhttpCompatibilityTest*) start_fixture selected_delay_fixture '19092,19093' ;;
esac
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
"$ADB" shell am instrument -w -r -e class "$classes" com.zane.zanebox.test/androidx.test.runner.AndroidJUnitRunner >"$OUT/instrumentation.txt" 2>&1
RC=$?
set -e
"$ADB" logcat -d >"$OUT/logcat.txt"
"$ADB" shell dumpsys package com.zane.zanebox >"$OUT/package.txt"
"$ADB" shell dumpsys connectivity >"$OUT/connectivity.txt"
"$ADB" pull /sdcard/Android/data/com.zane.zanebox/files/ "$OUT/device-files" >"$OUT/pull.txt" 2>&1 || true
cp "$ROOT/version.properties" "$OUT/version.properties"
"$ADB" shell run-as com.zane.zanebox cat cache/neko.log >"$OUT/native-runtime.log" 2>&1 || true
python3 - "$OUT" "$RC" "${ZANE_TEST_COUNT:-}" "$classes" <<'PY'
import pathlib,re,sys,json,hashlib,subprocess,os
p=pathlib.Path(sys.argv[1]);text=(p/'instrumentation.txt').read_text();crash=(p/'logcat.txt').read_text()
reasons=[]
adb=[os.environ.get('ADB','/opt/homebrew/bin/adb')]
photos=[]
for photo in (p/'device-files').rglob('*.png'):
    device='/sdcard/Android/data/com.zane.zanebox/files/'+photo.relative_to(p/'device-files').as_posix()
    local=hashlib.sha256(photo.read_bytes()).hexdigest()
    remote=subprocess.check_output(adb+['shell','sha256sum',device],text=True).split()[0]
    photos.append({'device':device,'host':str(photo.resolve()),'sha256':local,'verified':local==remote})
    if local!=remote:reasons.append('设备截图回传校验失败：'+photo.name)
(p/'device-photos.json').write_text(json.dumps(photos,ensure_ascii=False,indent=2))
match=re.search(r'OK \((\d+) tests?\)',text)
failed=re.search(r'Tests run:\s*(\d+),\s*Failures:\s*(\d+)',text)
count=int(match[1]) if match else int(failed[1]) if failed else None
failures=int(failed[2]) if failed else 0 if match else None
if sys.argv[2]!='0' or not match or int(match[1])==0 or 'FAILURES!!!' in text or 'INSTRUMENTATION_FAILED' in text:reasons.append('instrumentation没有完整通过')
expected=sys.argv[3]
if expected and (not expected.isdecimal() or int(expected)==0):reasons.append('声明的测试数量必须为正整数')
elif expected and count is not None and count!=int(expected):reasons.append('实际测试数量与声明范围不符')
if re.search(r'FATAL EXCEPTION[^\n]*\n(?:[^\n]*\n){0,6}[^\n]*com\.zane\.(zanebox|probe)',crash):reasons.append('发现应用/Probe崩溃')
# Selected test results define the scope; legacy screenshot names are optional evidence.
result={'status':'FAIL' if reasons else 'PASS','reasons':reasons,'scope':sys.argv[4],'instrumentationTests':count,'instrumentationFailures':failures}
(p/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2));print(json.dumps(result,ensure_ascii=False));sys.exit(bool(reasons))
PY
