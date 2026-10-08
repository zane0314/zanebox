"""Drive long-press drag through Maestro MCP using bounds read from the active emulator."""
import json,os,re,shlex,subprocess,time
from pathlib import Path
from maestro_mcp_client import McpClient,DEFAULT_MAESTRO
root=Path(__file__).resolve().parents[1]
out=Path(os.environ.get('SMART_DRAG_MAESTRO_OUT',str(root/'reports/smart-drag-1.0.20/device/maestro-drag')));out.mkdir(parents=True,exist_ok=True)
serial=os.environ['ANDROID_SERIAL'];assert serial=='emulator-5580'
adb=['/opt/homebrew/bin/adb','-s',serial]
assert subprocess.check_output(adb+['emu','avd','name'],text=True).splitlines()[0]=='ZaneBox_S25Plus_API35_Acceptance'
def order():
 sql="SELECT value FROM kv WHERE key='smartPolicyOrder';"
 text=subprocess.check_output(adb+['shell','su 0 sqlite3 -json /data/user/0/com.zane.zanebox/databases/zanebox.db '+shlex.quote(sql)],text=True)
 rows=json.loads(text or '[]');assert rows and rows[0]['value'];return rows[0]['value'].splitlines()
def payload(result):return json.loads(next(c['text'] for c in result['content'] if c['type']=='text'))
def walk(nodes):
 for node in nodes:
  yield node
  yield from walk(node.get('c',[]))
client=McpClient(DEFAULT_MAESTRO,out/'maestro-mcp.log');result={'status':'FAIL','device':serial}
try:
 client.initialize()
 initial=out/'position.yaml';initial.write_text('''appId: com.zane.zanebox
---
- launchApp
- tapOn:
    id: tab_1
- scrollUntilVisible:
    element:
      id: smart_speed
    direction: DOWN
    timeout: 60000
''')
 positioned=payload(client.request('tools/call',{'name':'run','arguments':{'device_id':serial,'files':[str(initial)]}}));assert positioned.get('success'),positioned
 screen=payload(client.request('tools/call',{'name':'inspect_screen','arguments':{'device_id':serial}}));(out/'before-hierarchy.json').write_text(json.dumps(screen,ensure_ascii=False,indent=2))
 nodes=list(walk(screen['elements']))
 def bounds(tag):return tuple(map(int,re.findall(r'-?\d+',next(n['b'] for n in nodes if n.get('rid')==tag))))
 a=bounds('smart_speed');b=bounds('smart_youtube');view=bounds('page_list')
 size=subprocess.check_output(adb+['shell','wm','size'],text=True);width,height=map(int,re.findall(r'(\d+)x(\d+)',size)[-1])
 start=(a[0]+60,(a[1]+a[3])/2);direction=1 if b[1]>a[1] else -1
 end=(start[0],(b[1]+b[3])/2+direction*(b[3]-b[1])/4)
 assert view[1]<start[1]<view[3] and view[1]<end[1]<view[3],(a,b,view)
 env={'DRAG_START':f'{round(start[0]*100/width)}%, {round(start[1]*100/height)}%','DRAG_END':f'{round(end[0]*100/width)}%, {round(end[1]*100/height)}%'}
 before=order();expected=before.copy();target=expected.index('youtube');expected.remove('speed');expected.insert(target,'speed')
 source=(root/'tests/maestro/smart-drag.yaml').read_text();source=source[source.index('- swipe:'):]
 flow=out/'drag.yaml';flow.write_text('appId: com.zane.zanebox\n---\n'+source.replace('reports/ui-1.0.2/current',str(out)).replace('${DRAG_START}',env['DRAG_START']).replace('${DRAG_END}',env['DRAG_END']))
 executed=payload(client.request('tools/call',{'name':'run','arguments':{'device_id':serial,'files':[str(flow)],'env':env}}));assert executed.get('success'),executed
 deadline=time.monotonic()+10
 while order()!=expected:
  assert time.monotonic()<deadline,(before,expected,order())
  time.sleep(.1)
 result.update(status='PASS',before=before,after=order(),expected=expected,gesture_coordinates_from_hierarchy=env,flow=executed)
finally:
 client.close();subprocess.run(adb+['shell','am','force-stop','dev.mobile.maestro'],check=False,stdout=subprocess.DEVNULL)
 (out/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
print('MAESTRO_DRAG_PASS')
