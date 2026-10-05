#!/usr/bin/env python3
from pathlib import Path
import subprocess,json
root=Path(__file__).resolve().parents[1]
jar=root/'.build/native-verify/classes.jar'
baseline=json.loads((root/'tests/native-abi-baseline.json').read_text())
missing=[]
for cls in sorted({x['class'] for x in baseline}):
    output=subprocess.check_output(['/opt/homebrew/opt/openjdk@17/bin/javap','-s','-private','-classpath',str(jar),cls],text=True)
    members={};declaration=''
    for line in output.splitlines():
        line=line.strip()
        if line.startswith('descriptor:'):
            descriptor=line.split(': ',1)[1]
            name=declaration.split('(',1)[0].split()[-1].rstrip(';')
            if name==cls:name='<init>'
            members[(name,descriptor,' static ' in ' '+declaration)]=declaration.split()
        else:declaration=line
    for member in [x for x in baseline if x['class']==cls]:
        actual=members.get((member['name'],member['descriptor'],member['static']))
        if actual is None or (member['visibility']=='public' and 'public' not in actual) or (member['visibility']=='protected' and not {'public','protected'}.intersection(actual)):
            missing.append(cls+' '+member['name']+member['descriptor'])
report={'checked_members':len(baseline),'missing_or_changed':missing}
(root/'reports').mkdir(exist_ok=True)
(root/'reports/native-abi.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report));assert not missing
