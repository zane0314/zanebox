#!/usr/bin/env bash
set -Eeuo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
out="$1"
mkdir -p "$out"
python3 -B "$root/tests/network_fixture.py" --log "$out/fixture.jsonl" >"$out/fixture.stderr" 2>&1 &
fixture=$!
trap 'kill "$fixture" 2>/dev/null || true' EXIT
/opt/homebrew/bin/adb -s "$ANDROID_SERIAL" install -r "$root/app/build/outputs/apk/debug/app-debug.apk" >"$out/install.txt"
python3 -B "$root/tests/run_maestro_ui.py" optimization version --output "$out/maestro.json"
printf '%s' "$S25_RUN_DIR/mobile.done" >"$out/done-path.txt"
echo UI_SESSION_READY
while [[ ! -e "$S25_RUN_DIR/mobile.done" ]];do sleep 1;done
