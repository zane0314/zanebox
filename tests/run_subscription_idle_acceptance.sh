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
"${adb[@]}" install "${ZANE_SUB_PREVIOUS_APK:-$root/reports/subscription-1.0.14/Links-1.0.14.apk}" >"$out/install.txt"
"${adb[@]}" root >>"$out/install.txt"
"${adb[@]}" wait-for-device
"${adb[@]}" reverse tcp:19082 tcp:19082
"${adb[@]}" forward tcp:12080 tcp:2080
python3 - "$out" <<'PY'
import json,pathlib,sqlite3,sys,time,os
p=pathlib.Path(sys.argv[1])/'fixture.db';c=sqlite3.connect(p)
c.executescript('CREATE TABLE state(id INTEGER PRIMARY KEY CHECK(id=1),payload TEXT NOT NULL,revision INTEGER NOT NULL,status_revision INTEGER NOT NULL DEFAULT 0,kv_revision INTEGER NOT NULL DEFAULT 0); CREATE TABLE sequence(id INTEGER PRIMARY KEY CHECK(id=1),value INTEGER NOT NULL); CREATE TABLE node_status(node_id INTEGER PRIMARY KEY,ping INTEGER NOT NULL,status INTEGER NOT NULL,tx INTEGER NOT NULL,rx INTEGER NOT NULL); CREATE TABLE kv(key TEXT PRIMARY KEY,value TEXT,revision INTEGER NOT NULL); PRAGMA user_version=2;')
d={'format':1,'nodes':[{'id':20,'groupId':1,'name':'旧地址','outbound':{'type':'socks','server':'127.0.0.1','server_port':19082}}],'groups':[{'id':1,'name':'后台自动选择','enabled':True,'subscriptionUrl':'http://10.0.2.2:19080/options-subscription','updatedAt':int(time.time()*1000)+30000,'options':{'autoUpdate':True,'autoUpdateDelay':1,'filterMode':1,'filterRegex':'fixture [AB]$'}}],'settings':{'factoryRouteDefaultsVersion':'1','appLanguage':'zh-CN','serviceMode':os.environ.get('ZANE_SUB_SERVICE_MODE','proxy'),'selectedNodeId':'20','selectedGroupId':'1','homeAutoSelect':'true','statsEnabled':'false','dnsRemote':'local','testUrl':'http://203.0.113.9:19080/test','rulesUpdateInterval':'off'}}
c.execute('INSERT INTO state VALUES(1,?,0,0,0)',(json.dumps(d),));c.execute('INSERT INTO sequence VALUES(1,20)');c.commit();c.close()
PY
"${adb[@]}" push "$out/fixture.db" /data/local/tmp/links-subscription-idle-fixture.db >>"$out/install.txt"
python3 - "$out" <<'PY'
import hashlib,os,pathlib,subprocess,sys
adb=['/opt/homebrew/bin/adb','-s',os.environ['ANDROID_SERIAL']];remote='/data/local/tmp/links-subscription-idle-fixture.db'
assert subprocess.check_output(adb+['shell','sha256sum',remote],text=True).split()[0]==hashlib.sha256((pathlib.Path(sys.argv[1])/'fixture.db').read_bytes()).hexdigest()
uid=subprocess.check_output(adb+['shell','stat','-c','%u','/data/user/0/com.zane.zanebox'],text=True).strip();assert uid.isdigit() and int(uid)>=10000
for cmd in [('mkdir','-p','/data/user/0/com.zane.zanebox/databases'),('cp',remote,'/data/user/0/com.zane.zanebox/databases/zanebox.db'),('chown','-R',uid+':'+uid,'/data/user/0/com.zane.zanebox/databases'),('restorecon','-R','/data/user/0/com.zane.zanebox/databases'),('rm',remote)]:subprocess.check_call(adb+['shell',*cmd])
PY
"${adb[@]}" install -r "${ZANE_SUB_APK:-$root/app/build/outputs/apk/release/app-release.apk}" >>"$out/install.txt"
"${adb[@]}" shell pm grant com.zane.zanebox android.permission.POST_NOTIFICATIONS
"${adb[@]}" shell settings put global captive_portal_mode 0
"${adb[@]}" shell settings put global private_dns_mode off
"${adb[@]}" logcat -c
if [[ "${ZANE_SUB_POWER_EXEMPT:-1}" == 1 ]]; then
    "${adb[@]}" shell cmd deviceidle whitelist +com.zane.zanebox
fi
if [[ "${ZANE_SUB_SERVICE_MODE:-proxy}" == vpn ]]; then
    "${adb[@]}" shell appops set com.zane.zanebox ACTIVATE_VPN allow
fi
python3 "$root/tests/run_maestro_ui.py" subscription-background-release --output "$out/start-maestro.json"
python3 - "$out" <<'IDLE_ASSERT'
import hashlib,json,os,pathlib,sqlite3,subprocess,sys,time,urllib.request
out=pathlib.Path(sys.argv[1]);adb=['/opt/homebrew/bin/adb','-s',os.environ['ANDROID_SERIAL']]
def shell(*args):return subprocess.check_output(adb+['shell',*args],text=True).strip()
def snapshot(name):
    p=out/(name+'.db');p.write_bytes(subprocess.check_output(adb+['exec-out','cat','/data/user/0/com.zane.zanebox/databases/zanebox.db']))
    if subprocess.run(adb+['shell','test','-f','/data/user/0/com.zane.zanebox/databases/zanebox.db-wal']).returncode==0:
        pathlib.Path(str(p)+'-wal').write_bytes(subprocess.check_output(adb+['exec-out','cat','/data/user/0/com.zane.zanebox/databases/zanebox.db-wal']))
    c=sqlite3.connect(p);d=json.loads(c.execute('select payload from state where id=1').fetchone()[0]);d['settings'].update(dict(c.execute('select key,value from kv where value is not null').fetchall()));status=c.execute('select ping,status from node_status').fetchall();c.close();return d,status
opener=urllib.request.build_opener(urllib.request.ProxyHandler({'http':'http://127.0.0.1:12080'}))
def exit():
    with opener.open('http://203.0.113.9:19080/probe',timeout=5) as r:return r.read().decode().strip()
assert exit()=='EXIT_B'
main=shell('pidof','com.zane.zanebox');bg=shell('pidof','com.zane.zanebox:bg');assert main.isdigit() and bg.isdigit() and main!=bg
before,_=snapshot('before');due=before['groups'][0]['updatedAt']/1000+60000/1000
assert time.time()<due
if os.environ.get('ZANE_SUB_ISOLATE_RUNTIME','1')=='1':print(shell('cmd','jobscheduler','cancel','-u','0','com.zane.zanebox'),flush=True)
shell('kill','-9',main);shell('dumpsys','battery','unplug');shell('input','keyevent','223');print(shell('dumpsys','deviceidle','force-idle'),flush=True)
(out/'alarms-before.txt').write_text(shell('dumpsys','alarm'))
print(json.dumps({'mainKilled':main,'bgRetained':bg,'dueInSeconds':round(due-time.time()),'serviceMode':before['settings']['serviceMode']}),flush=True)
completed=False
try:
    while time.time()<due+55:
        time.sleep(5)
        assert 'mState=IDLE' in shell('dumpsys','deviceidle')
        if os.environ.get('ZANE_SUB_ISOLATE_RUNTIME','1')=='1':assert subprocess.run(adb+['shell','pidof','com.zane.zanebox'],stdout=subprocess.DEVNULL).returncode!=0, 'main process was needed/restarted'
        data,status=snapshot('sample-'+str(int(time.time())))
        fresh=data['groups'][0]['updatedAt']>before['groups'][0]['updatedAt']
        state=data['groups'][0]['options'].get('subscriptionRuntime',{}).get('state','idle')
        print(json.dumps({'pastDueSeconds':round(time.time()-due),'fresh':fresh,'state':state}),flush=True)
        if fresh and state=='success' and len(status)==2 and all(p>0 and s==3 for p,s in status):completed=True;break
    assert completed, 'subscription did not finish in :bg while main was absent and device stayed idle'
    assert data['settings']['homeAutoSelect']=='true'
    assert all(n['outbound']['server']=='10.0.2.2' for n in data['nodes']) and exit()=='EXIT_A'
    result={'status':'PASS','workManagerJobsSuppressed':os.environ.get('ZANE_SUB_ISOLATE_RUNTIME','1')=='1','mainProcessAbsent':subprocess.run(adb+['shell','pidof','com.zane.zanebox'],stdout=subprocess.DEVNULL).returncode!=0,'deviceStayedIdle':True,'serviceMode':before['settings']['serviceMode'],'powerExempt':os.environ.get('ZANE_SUB_POWER_EXEMPT','1')=='1','oldServer':'127.0.0.1','newServer':'10.0.2.2','actualExitBefore':'EXIT_B','actualExitAfter':'EXIT_A','bothLatenciesMeasured':True,'autoSelectionPreserved':True,'latenessSeconds':round(time.time()-due)}
    (out/'result.json').write_text(json.dumps(result,indent=2));print(json.dumps(result),flush=True)
finally:
    (out/'jobs-final.txt').write_text(shell('dumpsys','jobscheduler'))
    (out/'alarms-final.txt').write_text(shell('dumpsys','alarm'))
    (out/'logcat.txt').write_text(shell('logcat','-d'))
    shell('dumpsys','deviceidle','unforce');shell('dumpsys','battery','reset')
    shell('input','keyevent','224')
if completed:
    target='com.zane.zanebox/.runtime.'+('ZaneVpnService' if before['settings']['serviceMode']=='vpn' else 'ZaneProxyService')
    shell('am','startservice','-n',target,'-a','stop')
    for i in range(20):
        time.sleep(.25)
        prefs=shell('cat','/data/user/0/com.zane.zanebox/shared_prefs/runtime.xml')
        if 'name="connected" value="false"' in prefs:break
    assert 'name="connected" value="false"' in prefs
    dump=shell('dumpsys','alarm');(out/'alarms-after-stop.txt').write_text(dump)
    assert 'com.zane.zanebox.SUBSCRIPTION_UPDATE' not in dump.split('Pending alarms per uid:')[0], 'disconnect left a subscription alarm'
    result['disconnectCanceledAlarm']=True;(out/'result.json').write_text(json.dumps(result,indent=2))
IDLE_ASSERT
python3 "$root/tests/run_maestro_ui.py" version subscription-connect --output "$out/final-maestro.json"
python3 "$root/tests/capture_power_feedback.py" "$out/power-feedback"
"${adb[@]}" logcat -d >"$out/final-logcat.txt"
python3 - "$out" <<'FINAL_APK_CHECK'
import hashlib,json,os,pathlib,re,subprocess,sys
out=pathlib.Path(sys.argv[1]);adb=['/opt/homebrew/bin/adb','-s',os.environ['ANDROID_SERIAL']]
identity=dict(line.split('=',1) for line in pathlib.Path('version.properties').read_text().splitlines() if '=' in line)
pkg=subprocess.check_output(adb+['shell','dumpsys','package','com.zane.zanebox'],text=True)
assert 'versionName='+identity['versionName'] in pkg and 'versionCode='+identity['versionCode']+' ' in pkg
remote=subprocess.check_output(adb+['shell','pm','path','com.zane.zanebox'],text=True).strip().removeprefix('package:')
apk=pathlib.Path(os.environ.get('ZANE_SUB_APK','app/build/outputs/apk/release/app-release.apk'));sha=hashlib.sha256(apk.read_bytes()).hexdigest()
assert subprocess.check_output(adb+['shell','sha256sum',remote],text=True).split()[0]==sha
assert not re.search(r'FATAL EXCEPTION[^\n]*\n(?:[^\n]*\n){0,6}[^\n]*com\.zane\.zanebox',(out/'final-logcat.txt').read_text())
(out/'installed-apk.json').write_text(json.dumps({'status':'PASS','versionName':identity['versionName'],'versionCode':identity['versionCode'],'sha256':sha,'sameKeyUpgradeFrom':'1.0.14'},indent=2))
FINAL_APK_CHECK
