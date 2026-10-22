#!/usr/bin/env bash
# Keeps the one disposable emulator alive while Maestro/Mobile MCP drive it.
set -Eeuo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
: "${S25_RUN_DIR:?请通过 tests/with_s25_emulator.sh 启动}"
: "${ANDROID_SERIAL:?缺少本轮模拟器序号}"
apk="${1:-${ZANE_UI_APK:-}}"
[[ -f "$apk" ]] || { echo '请显式指定 Links APK：ui_mcp_session.sh /path/to/Links.apk' >&2; exit 2; }
ADB=${ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}
AAPT=${AAPT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/build-tools/35.0.0/aapt}
version=${ZANE_UI_VERSION:-$(sed -n 's/^versionName=//p' "$root/version.properties")}
code=${ZANE_UI_VERSION_CODE:-$(sed -n 's/^versionCode=//p' "$root/version.properties")}
badging=$("$AAPT" dump badging "$apk")
python3 - "$badging" "$version" "$code" <<'IDENTITY'
import re,sys
match=re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'",sys.argv[1],re.M)
if not match or match.groups()!=('com.zane.zanebox',sys.argv[3],sys.argv[2]):
    sys.exit('APK 包名或版本不匹配；必须为声明版本的 com.zane.zanebox Links 包')
IDENTITY
"$ADB" -s "$ANDROID_SERIAL" install -r "$apk"
"$ADB" -s "$ANDROID_SERIAL" shell pm grant com.zane.zanebox android.permission.POST_NOTIFICATIONS
package=$("$ADB" -s "$ANDROID_SERIAL" shell dumpsys package com.zane.zanebox)
python3 - "$package" "$version" "$code" <<'INSTALLED'
import re,sys
name=re.search(r'\bversionName=([^\s]+)',sys.argv[1]); code=re.search(r'\bversionCode=(\d+)',sys.argv[1])
if not name or not code or (name[1],code[1])!=(sys.argv[2],sys.argv[3]): sys.exit('已安装 Links 的版本不匹配')
INSTALLED
done_file="$S25_RUN_DIR/maestro-session.done"
ui_out="${ZANE_UI_OUT:-$root/reports/ui-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$ui_out"
python3 - "$ui_out" "$done_file" "$ANDROID_SERIAL" "$apk" "$version" "$code" <<'PYSESSION'
import json,pathlib,sys
out=pathlib.Path(sys.argv[1])
(out/'session.json').write_text(json.dumps({'serial':sys.argv[3],'done_file':sys.argv[2],'apk':str(pathlib.Path(sys.argv[4]).resolve()),'package':'com.zane.zanebox','versionName':sys.argv[5],'versionCode':int(sys.argv[6])}))
PYSESSION
echo 'MCP_SESSION_READY'
while [[ ! -e "$done_file" ]]; do sleep 1; done
