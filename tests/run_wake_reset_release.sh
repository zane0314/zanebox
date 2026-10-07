#!/usr/bin/env bash
# Fresh S25+ only; reuse the signed upgrade and cadence checks before LIVE wake toggles.
set -Eeuo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
out="$1"
[[ "${ANDROID_SERIAL:-}" == emulator-* && -d "${S25_RUN_DIR:-}" ]]
[[ "$(/opt/homebrew/bin/adb -s "$ANDROID_SERIAL" emu avd name | head -1 | tr -d '\r')" == ZaneBox_S25Plus_API35_Acceptance ]]
ZANE_OPT_PREVIOUS_APK="$root/reports/optimization-1.0.16/Links-1.0.16.apk" bash "$root/tests/run_optimization_release.sh" "$out"
python3 -B "$root/tests/network_fixture.py" --log "$out/wake-fixture.jsonl" >"$out/wake-fixture.stderr" 2>&1 &
fixture=$!
trap 'kill "$fixture" 2>/dev/null || true' EXIT
python3 - "$root" "$out" <<'PY'
import json,os,pathlib,sqlite3,subprocess,sys,time,urllib.request
root=pathlib.Path(sys.argv[1]);out=pathlib.Path(sys.argv[2]);adb=['/opt/homebrew/bin/adb','-s',os.environ['ANDROID_SERIAL']]
def shell(*args):return subprocess.check_output(adb+['shell',*args],text=True).strip()
def setting(label):
    p=out/(label+'.db');remote='/data/user/0/com.zane.zanebox/databases/zanebox.db'
    p.write_bytes(subprocess.check_output(adb+['exec-out','cat',remote]))
    pathlib.Path(str(p)+'-wal').write_bytes(subprocess.check_output(adb+['exec-out','cat',remote+'-wal']))
    with sqlite3.connect(p) as c:return c.execute("select value from kv where key='wakeResetConnections'").fetchone()[0]
shell('am','start-foreground-service','-n','com.zane.zanebox/.runtime.ZaneProxyService','-a','start');time.sleep(2)
opener=urllib.request.build_opener(urllib.request.ProxyHandler({'http':'http://127.0.0.1:12080'}))
for enabled in [True,False]:
    label='enabled' if enabled else 'disabled'
    shell('logcat','-c')
    subprocess.check_call([sys.executable,str(root/'tests/run_maestro_ui.py'),'wake-reset-toggle','--output',str(out/(label+'-maestro.json'))])
    assert setting(label)==str(enabled).lower(),'UI did not save live setting'
    shell('input','keyevent','223');time.sleep(.5);shell('input','keyevent','224');shell('wm','dismiss-keyguard');time.sleep(2)
    logs=shell('logcat','-d','-s','LinksRuntime:D','LinksStore:D');(out/(label+'-logcat.txt')).write_text(logs)
    assert ('wakeReset applied=true thread=DefaultDispatcher' in logs)==enabled,logs
    assert not any('command='+command+' ' in logs for command in ['autoReload','reload','start','stop']),logs
    assert not any('LinksStore:' in line and 'thread=main' in line for line in logs.splitlines())
    with opener.open('http://203.0.113.9:19080/probe',timeout=5) as r:assert r.read().decode().strip()=='EXIT_A'
shell('am','startservice','-n','com.zane.zanebox/.runtime.ZaneProxyService','-a','stop');time.sleep(1)
(out/'wake-reset-result.json').write_text(json.dumps({'status':'PASS','signedR8':True,'liveEnableResetOnIo':True,'liveDisableSkipsReset':True,'exitPreservedBothTimes':True,'maestroMcp':True},indent=2))
PY
echo WAKE_RESET_RELEASE_PASS
