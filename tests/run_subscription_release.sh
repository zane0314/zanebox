#!/usr/bin/env bash
# Disposable emulator only; verify real scheduled work in the signed R8 APK.
set -Eeuo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
out="$1"
adb=(/opt/homebrew/bin/adb -s "$ANDROID_SERIAL")
mkdir -p "$out"
python3 "$root/tests/network_fixture.py" --delay-b-ms 250 --log "$out/fixture.jsonl" >"$out/fixture.stderr" 2>&1 &
fixture=$!
trap 'kill "$fixture" 2>/dev/null || true' EXIT
"${adb[@]}" install "$root/reports/ui-1.0.13/Links-1.0.13.apk" >"$out/install.txt"
"${adb[@]}" root >>"$out/install.txt"
"${adb[@]}" wait-for-device
"${adb[@]}" reverse tcp:19082 tcp:19082
"${adb[@]}" forward tcp:12080 tcp:2080
python3 - "$out" <<'PY'
import json,pathlib,sqlite3,sys,time
p=pathlib.Path(sys.argv[1])/'fixture.db';c=sqlite3.connect(p)
c.executescript('CREATE TABLE state(id INTEGER PRIMARY KEY CHECK(id=1),payload TEXT NOT NULL,revision INTEGER NOT NULL,status_revision INTEGER NOT NULL DEFAULT 0,kv_revision INTEGER NOT NULL DEFAULT 0); CREATE TABLE sequence(id INTEGER PRIMARY KEY CHECK(id=1),value INTEGER NOT NULL); CREATE TABLE node_status(node_id INTEGER PRIMARY KEY,ping INTEGER NOT NULL,status INTEGER NOT NULL,tx INTEGER NOT NULL,rx INTEGER NOT NULL); CREATE TABLE kv(key TEXT PRIMARY KEY,value TEXT,revision INTEGER NOT NULL); PRAGMA user_version=2;')
d={'format':1,'nodes':[{'id':20,'groupId':1,'name':'旧地址','outbound':{'type':'socks','server':'127.0.0.1','server_port':19082}}],'groups':[{'id':1,'name':'后台自动选择','enabled':True,'subscriptionUrl':'http://10.0.2.2:19080/options-subscription','updatedAt':int(time.time()*1000),'options':{'autoUpdate':True,'autoUpdateDelay':1,'filterMode':1,'filterRegex':'fixture [AB]$'}}],'settings':{'factoryRouteDefaultsVersion':'1','appLanguage':'zh-CN','serviceMode':'proxy','selectedNodeId':'20','selectedGroupId':'1','homeAutoSelect':'true','statsEnabled':'false','dnsRemote':'local','testUrl':'http://203.0.113.9:19080/test','rulesUpdateInterval':'off'}}
c.execute('INSERT INTO state VALUES(1,?,0,0,0)',(json.dumps(d),));c.execute('INSERT INTO sequence VALUES(1,20)');c.commit();c.close()
PY
"${adb[@]}" push "$out/fixture.db" /data/local/tmp/links-subscription-1.0.14-fixture.db >>"$out/install.txt"
python3 - "$out" <<'PY'
import hashlib,os,pathlib,subprocess,sys
adb=['/opt/homebrew/bin/adb','-s',os.environ['ANDROID_SERIAL']];remote='/data/local/tmp/links-subscription-1.0.14-fixture.db'
assert subprocess.check_output(adb+['shell','sha256sum',remote],text=True).split()[0]==hashlib.sha256((pathlib.Path(sys.argv[1])/'fixture.db').read_bytes()).hexdigest()
uid=subprocess.check_output(adb+['shell','stat','-c','%u','/data/user/0/com.zane.zanebox'],text=True).strip();assert uid.isdigit() and int(uid)>=10000
for cmd in [('mkdir','-p','/data/user/0/com.zane.zanebox/databases'),('cp',remote,'/data/user/0/com.zane.zanebox/databases/zanebox.db'),('chown','-R',uid+':'+uid,'/data/user/0/com.zane.zanebox/databases'),('restorecon','-R','/data/user/0/com.zane.zanebox/databases'),('rm',remote)]:subprocess.check_call(adb+['shell',*cmd])
PY
"${adb[@]}" install -r "$root/app/build/outputs/apk/release/app-release.apk" >>"$out/install.txt"
"${adb[@]}" shell pm grant com.zane.zanebox android.permission.POST_NOTIFICATIONS
"${adb[@]}" shell settings put global captive_portal_mode 0
"${adb[@]}" shell settings put global private_dns_mode off
"${adb[@]}" logcat -c
python3 "$root/tests/run_maestro_ui.py" subscription-background-release --output "$out/start-maestro.json"
python3 - "$out" <<'PY'
import json,os,pathlib,subprocess,sys,time,urllib.request
out=pathlib.Path(sys.argv[1]);adb=['/opt/homebrew/bin/adb','-s',os.environ['ANDROID_SERIAL']]
opener=urllib.request.build_opener(urllib.request.ProxyHandler({'http':'http://127.0.0.1:12080'}))
def exit():
    with opener.open('http://203.0.113.9:19080/probe',timeout=5) as r:return r.read().decode().strip()
assert exit()=='EXIT_B';print('R8_BACKGROUND_OLD_EXIT_B',flush=True)
until=time.monotonic()+100
while True:
    focus=subprocess.check_output(adb+['shell','dumpsys','window'],text=True)
    current=next(x for x in focus.splitlines() if 'mCurrentFocus=' in x)
    assert 'com.zane.zanebox' not in current, 'App was reopened during background verification'
    try:
        if exit()=='EXIT_A':break
    except OSError:pass
    assert time.monotonic()<until,'old endpoint still active'
    time.sleep(2)
time.sleep(3)
(out/'background-result.json').write_text(json.dumps({'status':'PASS','signedRelease':True,'autoSelection':True,'oldServer':'127.0.0.1','newServer':'10.0.2.2','actualExitBefore':'EXIT_B','actualExitAfter':'EXIT_A','appRemainedInBackground':True},indent=2));print('R8_BACKGROUND_NEW_EXIT_A',flush=True)
PY
"${adb[@]}" shell am force-stop com.zane.zanebox
"${adb[@]}" exec-out cat /data/user/0/com.zane.zanebox/databases/zanebox.db >"$out/final.db"
if "${adb[@]}" shell test -f /data/user/0/com.zane.zanebox/databases/zanebox.db-wal; then
    "${adb[@]}" exec-out cat /data/user/0/com.zane.zanebox/databases/zanebox.db-wal >"$out/final.db-wal"
fi
python3 - "$out" <<'PY'
import json,pathlib,sqlite3,sys
out=pathlib.Path(sys.argv[1]);c=sqlite3.connect(out/'final.db');d=json.loads(c.execute('SELECT payload FROM state WHERE id=1').fetchone()[0]);s=c.execute('SELECT ping,status FROM node_status').fetchall()
assert len(d['nodes'])==2 and all(n['outbound']['server']=='10.0.2.2' for n in d['nodes'])
assert d['groups'][0]['options']['subscriptionRuntime']['state']=='success'
assert len(s)==2 and all(p>0 and status==3 for p,status in s)
assert d['settings']['homeAutoSelect']=='true';c.close()
p=out/'background-result.json';r=json.loads(p.read_text());r.update({'bothNewCandidatesMeasured':True,'workerCompleted':True,'sameKeyUpgradeFrom':'1.0.13'});p.write_text(json.dumps(r,indent=2))
PY
python3 "$root/tests/run_maestro_ui.py" version subscription-connect --output "$out/final-maestro.json"
python3 "$root/tests/capture_power_feedback.py" "$out/power-feedback"
"${adb[@]}" logcat -d >"$out/logcat.txt"
python3 - "$out" <<'PY'
import hashlib,json,os,pathlib,re,subprocess,sys
out=pathlib.Path(sys.argv[1]);adb=['/opt/homebrew/bin/adb','-s',os.environ['ANDROID_SERIAL']]
pkg=subprocess.check_output(adb+['shell','dumpsys','package','com.zane.zanebox'],text=True)
assert 'versionName=1.0.14' in pkg and 'versionCode=15 ' in pkg
remote=subprocess.check_output(adb+['shell','pm','path','com.zane.zanebox'],text=True).strip().removeprefix('package:')
expected=hashlib.sha256(pathlib.Path('app/build/outputs/apk/release/app-release.apk').read_bytes()).hexdigest()
assert subprocess.check_output(adb+['shell','sha256sum',remote],text=True).split()[0]==expected
assert not re.search(r'FATAL EXCEPTION[^\n]*\n(?:[^\n]*\n){0,6}[^\n]*com\.zane\.zanebox',(out/'logcat.txt').read_text())
(out/'installed-apk.json').write_text(json.dumps({'status':'PASS','version':'1.0.14','versionCode':15,'sha256':expected,'sameKeyUpgradeFrom':'1.0.13'},indent=2))
PY
