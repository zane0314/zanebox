"""Shell-only repeated gestures and HWUI frames after deterministic ScrollFixtureTest seeding."""
import argparse
import csv
import json
import os
from pathlib import Path
import re
import subprocess
import time

parser=argparse.ArgumentParser()
parser.add_argument('label')
parser.add_argument('--out',default='reports/ui-1.0.6')
parser.add_argument('--nodes',type=int,default=200)
parser.add_argument('--hide-toolbar',action='store_true')
parser.add_argument('--fixture-apk',type=Path,help='Debug APK used only to seed identical data before measuring a release APK')
parser.add_argument('--app-apk',type=Path,help='APK installed after fixture seed, before all measured gestures')
args=parser.parse_args()
assert re.fullmatch(r'[a-z0-9-]+',args.label)
serial=os.environ.get('ANDROID_SERIAL','emulator-5580');assert serial.startswith('emulator-')
adb=['/opt/homebrew/bin/adb','-s',serial]
assert subprocess.check_output(adb+['emu','avd','name'],text=True).splitlines()[0]=='ZaneBox_S25Plus_API35_Acceptance'
def shell(*command):return subprocess.check_output(adb+['shell',*command],text=True)
out=Path(args.out);out.mkdir(parents=True,exist_ok=True)
shell('am','force-stop','com.zane.zanebox')
if args.fixture_apk:
 subprocess.run(adb+['install','-r',str(args.fixture_apk)],check=True,stdout=subprocess.DEVNULL)
seed=shell('am','instrument','-w','-r','-e','class','com.zane.zanebox.ScrollFixtureTest','-e','nodeCount',str(args.nodes),'-e','showBottomBar',str(not args.hide_toolbar).lower(),'com.zane.zanebox.test/androidx.test.runner.AndroidJUnitRunner')
assert 'OK (1 test)' in seed,seed
(out/(args.label+'-seed.txt')).write_text(seed)
if args.app_apk:
 subprocess.run(adb+['install','-r',str(args.app_apk)],check=True,stdout=subprocess.DEVNULL)
shell('am','start','-W','-n','com.zane.zanebox/.MainActivity')
time.sleep(4)
width,height=map(int,re.findall(r'(\d+)x(\d+)',shell('wm','size'))[-1])
def swipe(up):
 x=int(width*.45);a=int(height*.72);b=int(height*.30)
 shell('input','swipe',str(x),str(a if up else b),str(x),str(b if up else a),'500')
for up in [True,False,True,False]:swipe(up)
shell('dumpsys','gfxinfo','com.zane.zanebox','reset')
start=time.monotonic()
for i in range(24):swipe(i%8<4)
raw=shell('dumpsys','gfxinfo','com.zane.zanebox','framestats')
(out/(args.label+'-gfx.txt')).write_text(raw)
frames=[];header=None
for line in raw.splitlines():
 if line.startswith('Flags,'):header=next(csv.reader([line]));continue
 if header and re.match(r'^\d+,',line):
  values=next(csv.reader([line]));row=dict(zip(header,values))
  if row.get('Flags')!='0':continue
  done=int(row['FrameCompleted']);vsync=int(row['IntendedVsync'])
  if done<=vsync or done>=9223372036854775807:continue
  ms=(done-vsync)/1e6
  frames.append({'totalMs':ms,'layoutMs':(int(row['DrawStart'])-int(row['PerformTraversalsStart']))/1e6,'drawMs':(int(row['SyncQueued'])-int(row['DrawStart']))/1e6,'missedDeadline':done>int(row.get('FrameDeadline',str(vsync+16666667)))})
assert frames,'没有采集到HWUI帧'
def pct(values,p):
 values=sorted(values);return values[min(len(values)-1,int((len(values)-1)*p))]
result={'label':args.label,'nodes':args.nodes,'toolbarVisible':not args.hide_toolbar,'durationSeconds':time.monotonic()-start,'sampledFrames':len(frames),'totalP50Ms':pct([f['totalMs'] for f in frames],.5),'totalP95Ms':pct([f['totalMs'] for f in frames],.95),'layoutP95Ms':pct([f['layoutMs'] for f in frames],.95),'drawP95Ms':pct([f['drawMs'] for f in frames],.95),'sampleMissedDeadlinePercent':100*sum(f['missedDeadline'] for f in frames)/len(frames),'summary':raw.split('---PROFILEDATA---')[0],'frames':frames,'scope':'同一模拟器相对比较，不代表真机/耗电'}
(out/(args.label+'.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2))
print(json.dumps({k:v for k,v in result.items() if k not in ['frames','summary']},ensure_ascii=False))
