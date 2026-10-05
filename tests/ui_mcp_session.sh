#!/usr/bin/env bash
# Keeps the one disposable emulator alive while Maestro/Mobile MCP drive it.
set -Eeuo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
done_file="$S25_RUN_DIR/maestro-session.done"
ui_out="${ZANE_UI_OUT:-$root/reports/ui-1.0.2}"
mkdir -p "$ui_out"
python3 - "$ui_out" "$done_file" <<'PY'
import json,pathlib,sys
out=pathlib.Path(sys.argv[1])
(out/'session.json').write_text(json.dumps({'serial':'emulator-5580','done_file':sys.argv[2]}))
PY
/opt/homebrew/bin/adb -s "$ANDROID_SERIAL" install '/Users/zane/Documents/ChatGPT/zane 代理软件/work/AnyBox-2.0.0-clean-150/releases/anybox-2.1.9/anybox-2.1.9.apk'
/opt/homebrew/bin/adb -s "$ANDROID_SERIAL" shell pm grant com.zane.proxy android.permission.POST_NOTIFICATIONS
echo 'MCP_SESSION_READY'
while [[ ! -e "$done_file" ]]; do sleep 1; done
