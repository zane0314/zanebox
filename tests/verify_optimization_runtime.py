#!/usr/bin/env python3
"""Public fixture checks for cadence, tail persistence, mode migration and same-key upgrade."""
import hashlib,json,os,pathlib,sqlite3,statistics,subprocess,sys,time,urllib.request
root=pathlib.Path(__file__).resolve().parents[1];out=pathlib.Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
adb=['/opt/homebrew/bin/adb','-s',os.environ['ANDROID_SERIAL']]
def shell(*args):return subprocess.check_output(adb+['shell',*args],text=True).strip()
def wait(label,condition,timeout=30):
    end=time.monotonic()+timeout
    while not condition():
        assert time.monotonic()<end,label;time.sleep(.1)
def connected():
    result=subprocess.run(adb+['shell','cat','/data/user/0/com.zane.zanebox/shared_prefs/runtime.xml'],text=True,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL)
    return 'name="connected" value="true"' in result.stdout
def database(label):
    p=out/(label+'.db');p.write_bytes(subprocess.check_output(adb+['exec-out','cat','/data/user/0/com.zane.zanebox/databases/zanebox.db']))
    wal='/data/user/0/com.zane.zanebox/databases/zanebox.db-wal'
    if subprocess.run(adb+['shell','test','-f',wal],stdout=subprocess.DEVNULL).returncode==0:pathlib.Path(str(p)+'-wal').write_bytes(subprocess.check_output(adb+['exec-out','cat',wal]))
    with sqlite3.connect(p) as c:
        data=json.loads(c.execute('select payload from state').fetchone()[0]);data['settings'].update(dict(c.execute('select key,value from kv where value is not null')))
    return data
def start():
    began=time.monotonic();shell('am','start-foreground-service','-n','com.zane.zanebox/.runtime.ZaneProxyService','-a','start');wait('connected',connected);return (time.monotonic()-began)*1000
def stop():shell('am','startservice','-n','com.zane.zanebox/.runtime.ZaneProxyService','-a','stop');wait('stopped',lambda:not connected())
old=root/'reports/background-1.0.15/Links-1.0.15.apk';new=pathlib.Path(sys.argv[2])
subprocess.check_call(adb+['install',str(old)],stdout=subprocess.DEVNULL);subprocess.check_call(adb+['root'],stdout=subprocess.DEVNULL);subprocess.check_call(adb+['wait-for-device']);subprocess.check_call(adb+['forward','tcp:12080','tcp:2080'])
fixture=out/'fixture.db'
with sqlite3.connect(fixture) as c:
    c.executescript('CREATE TABLE state(id INTEGER PRIMARY KEY,payload TEXT NOT NULL,revision INTEGER NOT NULL,status_revision INTEGER NOT NULL,kv_revision INTEGER NOT NULL);CREATE TABLE sequence(id INTEGER PRIMARY KEY,value INTEGER NOT NULL);CREATE TABLE kv(key TEXT PRIMARY KEY,value TEXT,revision INTEGER NOT NULL);CREATE TABLE node_status(node_id INTEGER PRIMARY KEY,ping INTEGER NOT NULL,status INTEGER NOT NULL,tx INTEGER NOT NULL,rx INTEGER NOT NULL);PRAGMA user_version=2;')
    d={'format':1,'nodes':[{'id':i,'groupId':1,'name':'fixture '+str(i),'outbound':{'type':'socks','server':'10.0.2.2','server_port':19081}} for i in range(1,1001)],'groups':[{'id':1,'name':'1000 public nodes','enabled':True}],'settings':{'factoryRouteDefaultsVersion':'1','appLanguage':'zh-CN','serviceMode':'proxy','selectedNodeId':'1','selectedGroupId':'1','dnsRemote':'local','fakeDns':'false','sniff':'false','rulesUpdateInterval':'off'}}
    c.execute('insert into state values(1,?,0,0,0)',(json.dumps(d),));c.execute('insert into sequence values(1,1000)')
remote='/data/local/tmp/links-optimization-fixture.db';subprocess.check_call(adb+['push',str(fixture),remote],stdout=subprocess.DEVNULL)
assert shell('sha256sum',remote).split()[0]==hashlib.sha256(fixture.read_bytes()).hexdigest()
uid=shell('stat','-c','%u','/data/user/0/com.zane.zanebox');assert uid.isdigit()
for args in [('mkdir','-p','/data/user/0/com.zane.zanebox/databases'),('cp',remote,'/data/user/0/com.zane.zanebox/databases/zanebox.db'),('chown','-R',uid+':'+uid,'/data/user/0/com.zane.zanebox/databases'),('restorecon','-R','/data/user/0/com.zane.zanebox/databases'),('rm',remote)]:shell(*args)
old_times=[]
for i in range(4):old_times.append(start());stop()
before=database('before-upgrade');shell('am','force-stop','com.zane.zanebox')
subprocess.check_call(adb+['install','-r',str(new)],stdout=subprocess.DEVNULL)
new_times=[]
for i in range(4):new_times.append(start());stop()
after=database('after-upgrade');assert before['nodes']==after['nodes'] and before['groups']==after['groups']
assert {k:v for k,v in before['settings'].items() if k!='trafficData'}=={k:v for k,v in after['settings'].items() if k!='trafficData'}
# Runtime gets a visible UI through Maestro, then screen off: use runtime logs and SQL revisions as evidence.
shell('logcat','-c');start();shell('am','start','-n','com.zane.zanebox/.MainActivity');time.sleep(16)
work_path='/data/user/0/com.zane.zanebox/no_backup/androidx.work.workdb'
work_count=0
if subprocess.run(adb+['shell','test','-f',work_path],stdout=subprocess.DEVNULL).returncode==0:
    wp=out/'work-without-subscriptions.db';wp.write_bytes(subprocess.check_output(adb+['exec-out','cat',work_path]))
    if subprocess.run(adb+['shell','test','-f',work_path+'-wal'],stdout=subprocess.DEVNULL).returncode==0:pathlib.Path(str(wp)+'-wal').write_bytes(subprocess.check_output(adb+['exec-out','cat',work_path+'-wal']))
    with sqlite3.connect(wp) as wc:work_count=wc.execute("select count(*) from workspec where worker_class_name='com.zane.zanebox.subscription.SubscriptionReconcileWorker' and state not in (2,3,5)").fetchone()[0]
assert work_count==0,'periodic subscription reconciliation registered without scheduled subscriptions'
shell('input','keyevent','223')
prior=database('before-idle-sampling')['settings'].get('trafficData','')
# Traffic continues during slow sampling: a 32s query hang is deliberately avoided, each fixture request is short.
opener=urllib.request.build_opener(urllib.request.ProxyHandler({'http':'http://127.0.0.1:12080'}))
for i in range(13):
    with opener.open('http://203.0.113.9:19080/probe',timeout=5) as r:assert r.read().decode().strip()=='EXIT_A'
    time.sleep(5)
logs=shell('logcat','-d','-s','LinksRuntime:D','LinksStore:D');(out/'cadence-logcat.txt').write_text(logs)
assert 'sample interval=1000' in logs and 'sample interval=10000' in logs
assert 'persisted=true' in logs
for line in logs.splitlines():assert not('LinksStore:' in line and 'thread=main' in line),line
saved=database('after-idle-sampling')['settings'].get('trafficData','');assert saved!=prior,'traffic did not persist at 60s'
stop();tail=database('after-stop')['settings']['trafficData'];assert json.loads(tail)['nodes'],'tail traffic missing'
shell('input','keyevent','224');shell('wm','dismiss-keyguard')
result={'status':'PASS','nodes':1000,'oldStartMs':old_times,'newStartMs':new_times,'oldWarmMedianMs':statistics.median(old_times[1:]),'newWarmMedianMs':statistics.median(new_times[1:]),'sameKeyUpgradeFrom':'1.0.15/16','nodesGroupsAndSettingsPreserved':True,'visibleIntervalMs':1000,'screenOffIntervalMs':10000,'persistIntervalMs':60000,'tailTrafficSaved':True,'noMainThreadSnapshotDuringUiStartup':True,'periodicReconcileWorkWithoutTimedSubscriptions':work_count}
(out/'runtime-performance.json').write_text(json.dumps(result,indent=2));print(json.dumps(result),flush=True)
