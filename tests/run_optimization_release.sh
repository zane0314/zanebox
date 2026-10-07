#!/usr/bin/env bash
set -Eeuo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
out="$1"
mkdir -p "$out"
python3 -B "$root/tests/network_fixture.py" --log "$out/fixture.jsonl" >"$out/fixture.stderr" 2>&1 &
fixture=$!
trap 'kill "$fixture" 2>/dev/null || true' EXIT
/opt/homebrew/bin/adb -s "$ANDROID_SERIAL" forward tcp:12080 tcp:2080
python3 -B "$root/tests/verify_optimization_runtime.py" "$out/runtime" "$root/app/build/outputs/apk/release/app-release.apk"
python3 -B "$root/tests/run_maestro_ui.py" optimization version --output "$out/maestro.json"
/opt/homebrew/bin/adb -s "$ANDROID_SERIAL" exec-out screencap -p >"$out/final-screen.png"
/opt/homebrew/bin/adb -s "$ANDROID_SERIAL" logcat -d >"$out/final-logcat.txt"
echo RELEASE_OPTIMIZATION_PASS
