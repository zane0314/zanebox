"""Capture a held press with adb; pixels outside the circle must stay unchanged."""
import json, os, pathlib, re, subprocess, sys, time, xml.etree.ElementTree as ET
from PIL import Image, ImageChops

out=pathlib.Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
serial=os.environ['ANDROID_SERIAL'];assert serial=='emulator-5580'
adb=['/opt/homebrew/bin/adb','-s',serial]
xml=subprocess.check_output(adb+['exec-out','uiautomator','dump','/dev/tty'],text=True)
tree=ET.fromstring(xml[xml.index('<?xml'):xml.index('</hierarchy>')+len('</hierarchy>')])
node=next(n for n in tree.iter('node') if n.get('resource-id')=='connect_toggle')
x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
def shot(name):
    path=out/(name+'.png');path.write_bytes(subprocess.check_output(adb+['exec-out','screencap','-p']));return Image.open(path).convert('RGB')
before=shot('power-before')
subprocess.check_call(adb+['shell','input','motionevent','DOWN',str((x1+x2)//2),str((y1+y2)//2)])
try:
    time.sleep(.5);pressed=shot('power-pressed')
finally:
    subprocess.check_call(adb+['shell','input','motionevent','UP',str((x1+x2)//2),str((y1+y2)//2)])
delta=ImageChops.difference(before.crop((x1,y1,x2,y2)),pressed.crop((x1,y1,x2,y2)))
w,h=delta.size;r=min(w,h)/2
outside=[max(delta.getpixel((x,y))) for y in range(h) for x in range(w) if (x+.5-w/2)**2+(y+.5-h/2)**2>(r+3)**2]
result={'status':'PASS' if max(outside,default=0)<=2 else 'FAIL','bounds':[x1,y1,x2,y2],'outsideCircleMaxDelta':max(outside,default=0)}
(out/'power-feedback.json').write_text(json.dumps(result,indent=2));print(result)
assert result['status']=='PASS', 'rectangular press feedback remains outside circle'
