"""Run the UI acceptance flows through the official project-local Maestro MCP."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
from maestro_mcp_client import McpClient, DEFAULT_MAESTRO, DEFAULT_LOG

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("flows", nargs="*", default=["smoke", "editors", "auxiliary"])
parser.add_argument("--output", default="reports/ui-1.0.2/maestro-result.json")
args = parser.parse_args()
out = root / args.output
out.parent.mkdir(parents=True, exist_ok=True)
identity = dict(line.split("=", 1) for line in (root / "version.properties").read_text().splitlines() if "=" in line)
serial = os.environ.get("ANDROID_SERIAL", "emulator-5580")
adb = ["/opt/homebrew/bin/adb", "-s", serial]
assert serial.startswith("emulator-")
assert subprocess.check_output(adb + ["emu", "avd", "name"], text=True).splitlines()[0] == "ZaneBox_S25Plus_API35_Acceptance"

# Mobile's resident driver and Maestro cannot own Android UiAutomation at the same time.
for line in subprocess.check_output(adb + ["shell", "ps", "-A", "-o", "PID,ARGS"], text=True).splitlines():
    if "com.mobilenext.mobilecli.DeviceServer" in line:
        subprocess.check_call(adb + ["shell", "kill", line.split()[0]])

client = McpClient(DEFAULT_MAESTRO, out.parent / "maestro-mcp.log")
results = []
try:
    client.initialize()
    devices = client.request("tools/call", {"name": "list_devices", "arguments": {}})
    entries = json.loads(devices["content"][0]["text"])["devices"]
    assert any(d["device_id"] == serial and d["connected"] for d in entries)
    for flow in args.flows:
        source = root / "tests/maestro" / (flow + ".yaml")
        path = out.parent / "flows" / out.stem / (flow + ".yaml")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source.read_text().replace("reports/ui-1.0.2/current", str(out.parent / "current" / out.stem)))
        response = client.request("tools/call", {"name": "run", "arguments": {
            "device_id": serial, "files": [str(path)], "env": {"VERSION_NAME": identity["versionName"], "VERSION_CODE": identity["versionCode"]}}})
        text = next(c["text"] for c in response["content"] if c["type"] == "text")
        try:
            result = json.loads(text)
        except json.JSONDecodeError:
            result = {"success": False, "error": text}
        results.append({"flow": flow, "result": result})
        print(flow, "PASS" if result.get("success") else "FAIL", flush=True)
        if not result.get("success"):
            hierarchy = client.request("tools/call", {"name": "inspect_screen", "arguments": {"device_id": serial}})
            (out.parent / (out.stem + "-failure-hierarchy.json")).write_text(json.dumps(hierarchy, ensure_ascii=False))
            break
finally:
    client.close()
    subprocess.run(adb + ["shell", "am", "force-stop", "dev.mobile.maestro"], check=False, stdout=subprocess.DEVNULL)

passed = len(results) == len(args.flows) and all(r["result"].get("success") for r in results)
out.write_text(json.dumps({"status": "PASS" if passed else "FAIL", "device": serial, "flows": results}, ensure_ascii=False, indent=2))
sys.exit(0 if passed else 1)
