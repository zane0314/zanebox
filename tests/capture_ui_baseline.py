"""Capture the unchanged reference APK inside the project's disposable emulator."""
import os
import pathlib
import subprocess
import time
import re
import xml.etree.ElementTree as ET

root = pathlib.Path(__file__).resolve().parents[1]
out = root / "reports/ui-1.0.2/baseline"
out.mkdir(parents=True, exist_ok=True)
adb = ["/opt/homebrew/bin/adb", "-s", os.environ["ANDROID_SERIAL"]]

def run(*args):
    return subprocess.check_output(adb + list(args))

reference = "/Users/zane/Documents/ChatGPT/zane 代理软件/work/AnyBox-2.0.0-clean-150/releases/anybox-2.1.9/anybox-2.1.9.apk"
run("install", reference)
run("shell", "pm", "grant", "com.zane.proxy", "android.permission.POST_NOTIFICATIONS")
run("shell", "am", "start", "-W", "-n", "com.zane.proxy/io.nekohasekai.sagernet.ui.MainActivity")
time.sleep(3)
device_file = "/sdcard/zb-102-baseline-window.xml"
def capture(name):
    time.sleep(.4)
    run("shell", "uiautomator", "dump", device_file)
    xml = run("exec-out", "cat", device_file)
    ET.fromstring(xml)
    (out / (name + ".xml")).write_bytes(xml)
    (out / (name + ".png")).write_bytes(run("exec-out", "screencap", "-p"))
    run("shell", "rm", device_file)
    print("基线截图", name, flush=True)
    return ET.fromstring(xml)

def tap(tree, label):
    node = next((e for e in tree.iter("node") if label in (e.get("text"), e.get("content-desc")) or e.get("resource-id", "").endswith("/" + label)), None)
    if node is None:
        print("入口未找到", label, flush=True)
        return False
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    run("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
    return True

tree = capture("nodes-empty")
tap(tree, "设置")
settings = capture("settings")
for label, name in [("通用设置", "general"), ("路由设置", "route"), ("DNS 设置", "dns"), ("备份与恢复", "backup"), ("工具", "tools")]:
    if tap(settings, label):
        capture(name)
        run("shell", "input", "keyevent", "4")
        settings = capture("settings-return")
run("shell", "input", "keyevent", "4")
tree = capture("return-nodes")
if tap(tree, "智能分流"):
    capture("smart")
    run("shell", "input", "keyevent", "4")
tree = capture("nodes-final")
if tap(tree, "更多操作"):
    capture("node-menu")
    run("shell", "input", "keyevent", "4")
tree = capture("nodes-return")
if tap(tree, "添加节点"):
    capture("add-menu")
print("基线捕获完成：", out, flush=True)
