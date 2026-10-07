#!/usr/bin/env python3
"""Verify and remove only files created by this disposable test run."""
import hashlib,json,os,pathlib,subprocess,sys
out=pathlib.Path(sys.argv[1]);adb=['/opt/homebrew/bin/adb','-s',os.environ['ANDROID_SERIAL']]
base='/sdcard/Android/data/com.zane.zanebox/files/'
created=json.loads((out/'created-device-files.json').read_text());records=[]
for name in created:
    assert name.startswith(base) and '..' not in pathlib.PurePosixPath(name).parts
    local=out/'device-files'/name.removeprefix(base)
    assert local.is_file(),str(local)
    digest=hashlib.sha256(local.read_bytes()).hexdigest()
    assert subprocess.check_output(adb+['shell','sha256sum',name],text=True).split()[0]==digest,name
    records.append({'remote':name,'local':str(local),'sha256':digest})
(out/'verified-device-files.json').write_text(json.dumps(records,indent=2))
for record in records:subprocess.check_call(adb+['shell','rm','--',record['remote']])
print('VERIFIED_DEVICE_EVIDENCE',len(records))
