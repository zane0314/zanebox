"""Check acceptance scope locally with fake ADB, fixtures, and Maestro."""
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile


project = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix="links-acceptance-scope-") as temporary:
    root = Path(temporary)
    (root / "tests").mkdir()
    adb = root / "fake-adb"
    adb.write_text('''#!/usr/bin/env python3
import json, os, pathlib, sys
args = sys.argv[1:]
with open(os.environ['SCOPE_CALLS'], 'a') as stream:
    stream.write(json.dumps(args) + '\\n')
if 'instrument' in args:
    print('FAILURES!!!\\nTests run: 3,  Failures: 1' if os.environ.get('SCOPE_FAILURE') == 'instrument' else 'OK (1 test)')
elif 'logcat' in args and '-d' in args and os.environ.get('SCOPE_FAILURE') == 'crash':
    print('FATAL EXCEPTION: main\\nProcess: com.zane.zanebox')
elif 'dumpsys' in args and 'package' in args:
    print('versionName=fixture\\nversionCode=1')
elif 'pull' in args:
    pathlib.Path(args[-1]).mkdir(parents=True, exist_ok=True)
''')
    adb.chmod(0o700)
    for name in ["run_emulator_acceptance.sh", "run_optimization_acceptance.sh", "ui_mcp_session.sh"]:
        text = (project / "tests" / name).read_text()
        # The fixture must never reach a real device, including legacy absolute paths.
        (root / "tests" / name).write_text(text.replace("/opt/homebrew/bin/adb", str(adb)))
    shutil.copy2(project / "tests/verify_device_evidence.py", root / "tests/verify_device_evidence.py")
    fixture_source = """import os, socket, sys, time
from pathlib import Path
name = Path(__file__).name
with open(os.environ['SCOPE_CALLS'], 'a') as stream:
    stream.write(__import__('json').dumps(['fixture', name]) + '\\n')
if os.environ.get('SCOPE_FAILURE') == 'fixture': sys.exit(2)
ports = [19092,19093] if name == 'selected_delay_fixture.py' else [19080,19081,19082,19085,19086,19087,19088,19089]
sockets = []
for port in ports:
    sock = socket.socket(); sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(('127.0.0.1',port)); sock.listen(128); sockets.append(sock)
time.sleep(60)
"""
    for name in ['network_fixture.py', 'selected_delay_fixture.py']:
        (root / 'tests' / name).write_text(fixture_source)
    (root / "tests/run_maestro_ui.py").write_text('''import json, os, pathlib, sys
with open(os.environ['SCOPE_CALLS'], 'a') as stream:
    stream.write(json.dumps(['maestro']) + '\\n')
failed = os.environ.get('SCOPE_FAILURE') == 'maestro'
pathlib.Path(sys.argv[sys.argv.index('--output') + 1]).write_text(json.dumps({'status': 'FAIL' if failed else 'PASS'}))
sys.exit(1 if failed else 0)
''')
    for name in ["app/build/outputs/apk/debug/app-debug.apk",
                 "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
                 "tests/probe/build/outputs/apk/debug/ZaneProbe-debug.apk",
                 "native/OwnBoxForAndroid/libcore/.build/libcore.aar"]:
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b"local scope fixture")
    (root / "version.properties").write_text("versionName=fixture\nversionCode=1\n")

    def run(name, script, settings, passed, full=False):
        out = root / name
        calls = root / (name + ".jsonl")
        environment = os.environ.copy()
        for key in ["ZANE_TEST_CLASSES", "ZANE_TEST_COUNT", "ZANE_OPT_CLASSES"]:
            environment.pop(key, None)
        environment.pop("SCOPE_FAILURE", None)
        environment.update({"ADB": str(adb), "ANDROID_SERIAL": "emulator-scope-fixture",
                            "SCOPE_CALLS": str(calls), **settings})
        calls.write_text("")
        completed = subprocess.run(["bash", str(root / "tests" / script), str(out)],
                                   env=environment, capture_output=True, text=True, errors="replace", timeout=30)
        assert (completed.returncode == 0) == passed, (name, completed.stdout, completed.stderr)
        result_file = out / "result.json"
        if passed:
            assert json.loads(result_file.read_text())["status"] == "PASS", name
        elif result_file.exists():
            assert json.loads(result_file.read_text())["status"] != "PASS", name
        actions = [json.loads(line) for line in calls.read_text().splitlines()]
        if script == "run_optimization_acceptance.sh" and passed:
            assert any(action == ["maestro"] for action in actions) == full, name
            assert any("com.zane.zanebox.SelectionCacheTest" in action for action in actions) == full, name
        if script == "run_emulator_acceptance.sh":
            fixtures = [action[1] for action in actions if action[0] == "fixture"]
            selected = not settings.get("ZANE_TEST_CLASSES") or any(
                item in settings["ZANE_TEST_CLASSES"] for item in ['SelectedNodeDelayUiTest', 'NodeSwitchLatencyTest', 'XhttpCompatibilityTest'])
            assert ('selected_delay_fixture.py' in fixtures) == selected, (name, fixtures)
            if settings.get("SCOPE_FAILURE") == "instrument":
                result = json.loads(result_file.read_text())
                assert result['instrumentationTests'] == 3 and result['instrumentationFailures'] == 1, result
            if settings.get("SCOPE_FAILURE") == "fixture":
                assert not any('instrument' in action for action in actions), actions
        if settings.get("SCOPE_FAILURE") == "instrument":
            assert ["maestro"] not in actions, name
        assert not (out / "hashes.txt").exists(), name

    with socket.socket() as listener:
        listener.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
        listener.bind(('127.0.0.1',19080));listener.listen(1)
        run("port-occupied", "run_emulator_acceptance.sh",
            {"ZANE_TEST_CLASSES": "com.zane.zanebox.NativeSchemaTest"}, False)
        assert not (root/'port-occupied.jsonl').read_text(), '占用时不得启动夹具或安装应用'
        # Active close leaves TIME_WAIT; it must not be mistaken for a live fixture owner.
        with socket.create_connection(('127.0.0.1',19080)) as client:
            peer,_=listener.accept();peer.close();client.recv(1)
    run("targeted", "run_emulator_acceptance.sh",
        {"ZANE_TEST_CLASSES": "com.zane.zanebox.NativeSchemaTest"}, True)
    run("selected-delay", "run_emulator_acceptance.sh",
        {"ZANE_TEST_CLASSES": "com.zane.zanebox.SelectedNodeDelayUiTest"}, True)
    run("fixture-failure", "run_emulator_acceptance.sh",
        {"ZANE_TEST_CLASSES": "com.zane.zanebox.NativeSchemaTest", "SCOPE_FAILURE": "fixture"}, False)
    run("emulator-full", "run_emulator_acceptance.sh", {}, True)
    run("count-mismatch", "run_emulator_acceptance.sh", {"ZANE_TEST_COUNT": "2"}, False)
    run("instrument-failure", "run_emulator_acceptance.sh", {"SCOPE_FAILURE": "instrument"}, False)
    run("crash", "run_emulator_acceptance.sh", {"SCOPE_FAILURE": "crash"}, False)
    run("optimization-targeted", "run_optimization_acceptance.sh",
        {"ZANE_OPT_CLASSES": "OptimizationRuntimeTest"}, True)
    run("optimization-full", "run_optimization_acceptance.sh", {}, True, full=True)
    run("optimization-full", "run_optimization_acceptance.sh",
        {"SCOPE_FAILURE": "instrument"}, False)
    run("optimization-maestro-failure", "run_optimization_acceptance.sh",
        {"SCOPE_FAILURE": "maestro"}, False)

    # A UI session must reject an unrelated package or stale APK before installing it.
    aapt = root / 'fake-aapt'
    aapt.write_text("""#!/usr/bin/env python3
import os
print("package: name='%s' versionCode='1' versionName='%s'" % (os.environ.get('SCOPE_PACKAGE','com.zane.zanebox'),os.environ.get('SCOPE_VERSION','fixture')))
""")
    aapt.chmod(0o700)
    ui_run=root/'ui-session';ui_run.mkdir();(ui_run/'maestro-session.done').touch()
    for name, overrides, passed in [('links',{},True),('wrong-package',{'SCOPE_PACKAGE':'com.zane.proxy'},False),('wrong-version',{'SCOPE_VERSION':'old'},False)]:
        calls=root/('ui-'+name+'.jsonl');calls.write_text('')
        environment={**os.environ,'ADB':str(adb),'AAPT':str(aapt),'ANDROID_SERIAL':'emulator-scope-fixture',
                     'S25_RUN_DIR':str(ui_run),'ZANE_UI_OUT':str(root/('ui-'+name)),
                     'ZANE_UI_VERSION':'fixture','ZANE_UI_VERSION_CODE':'1','SCOPE_CALLS':str(calls),**overrides}
        completed=subprocess.run(['bash',str(root/'tests/ui_mcp_session.sh'),str(root/'app/build/outputs/apk/debug/app-debug.apk')],env=environment,capture_output=True,text=True,timeout=5)
        assert (completed.returncode==0)==passed,(name,completed.stdout,completed.stderr)
        actions=[json.loads(line) for line in calls.read_text().splitlines()]
        assert any('install' in action for action in actions)==passed,(name,actions)
        assert not any('com.zane.proxy' in action for action in actions),(name,actions)

    compare=project/'tests/compare_config_optimization.py'
    for arguments, expected in [([], 'required'),(['--baseline-source',str(root/'missing'),'--output',str(root/'comparison')],'基线不完整'),(['--baseline-source',str(project),'--output',str(root/'comparison')],'基线与当前')]:
        completed=subprocess.run(['python3',str(compare),*arguments],capture_output=True,text=True,timeout=5)
        assert completed.returncode!=0 and expected in completed.stderr,(arguments,completed.stderr)

print("PASS: declared fixtures, readiness failure, test/failure counts, targeted/full scope, UI package/version, and explicit baseline checks")
