#!/usr/bin/env bash
# One fresh S25+ session, final R8 package only. Reset only this run's public fixtures.
set -Eeuo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
out="$1"
bash "$root/tests/run_optimization_release.sh" "$out/runtime"
/opt/homebrew/bin/adb -s "$ANDROID_SERIAL" uninstall com.zane.zanebox >"$out/reset-own-fixture.txt"
ZANE_SUB_SERVICE_MODE=vpn ZANE_SUB_ISOLATE_RUNTIME=1 ZANE_SUB_PREVIOUS_APK="$root/reports/background-1.0.15/Links-1.0.15.apk" bash "$root/tests/run_subscription_idle_acceptance.sh" "$out/idle"
/opt/homebrew/bin/adb -s "$ANDROID_SERIAL" exec-out screencap -p >"$out/final-screen.png"
echo FINAL_OPTIMIZATION_PASS
