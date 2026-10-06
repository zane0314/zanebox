"""Run with a full-screen UiPage open; compare the native frame with Compose's requested size."""
import os
import pathlib
import re
import subprocess
import sys

serial=os.environ.get("ANDROID_SERIAL","emulator-5580")
assert serial.startswith("emulator-")
dump=pathlib.Path(sys.argv[1]).read_text() if len(sys.argv)>1 else subprocess.check_output(
    ["/opt/homebrew/bin/adb","-s",serial,"shell","dumpsys","window","windows"],text=True)
dialogs=[w for w in dump.split("Window #") if "package=com.zane.zanebox" in w and "ty=APPLICATION " in w and "isVisible=true" in w]
assert len(dialogs)==1,"需要一个可见的全屏 UiPage"
w=dialogs[0]
frame=re.search(r"frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]",w)
requested=re.search(r"Requested w=(\d+) h=(\d+)",w)
assert frame and requested
left,top,right,bottom=map(int,frame.groups())
width,height=map(int,requested.groups())
assert (left,top)==(0,0),f"全屏窗口被安全区挤压：{frame.group()}"
assert width<=right-left+1 and height<=bottom-top+1,f"窗口小于布局：{requested.group()} {frame.group()}"
print("PASS",frame.group(),requested.group())
