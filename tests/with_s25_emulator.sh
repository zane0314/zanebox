#!/usr/bin/env bash
# Run one acceptance command with disposable emulator data. Never targets a phone.
set -Eeuo pipefail
[[ $# -gt 0 ]] || { echo 'usage: with_s25_emulator.sh COMMAND [ARGS...]' >&2; exit 2; }
root=$(cd "$(dirname "$0")/.." && pwd)
PROJECT_ROOT="$root"
source "$root/toolchain.conf"
emulator="$ANDROID_SDK_ROOT/emulator/emulator"
adb_bin=/opt/homebrew/bin/adb
template=AnyBox_S25Plus_API35
avd=ZaneBox_S25Plus_API35_Acceptance
avd_home="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
export ANDROID_SERIAL=emulator-5580 ADB_SERIAL=emulator-5580
[[ -x "$emulator" && -x "$adb_bin" ]]
"$emulator" -list-avds | grep -Fxq "$template"
if "$adb_bin" devices | awk 'NR>1 && $1 ~ /^emulator-/ {found=1} END {exit !found}'; then
    echo 'An emulator is already running; refusing a second instance.' >&2
    exit 1
fi
[[ ! -e "$avd_home/$avd.ini" ]] || { echo 'Acceptance AVD already exists; inspect before retrying.' >&2; exit 1; }
for port in 5580 5581; do
    if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
        echo "Port $port is busy; refusing to replace an existing emulator." >&2
        exit 1
    fi
done
mkdir -p "$root/.build" "$root/reports"
lock="$root/.build/s25-emulator.lock"
mkdir "$lock" || { echo 'S25 emulator session already active; inspect before retrying.' >&2; exit 1; }
run=''; pid=''; ini=''; modem=''
[[ -e "$HOME/.android/modem-nv-ram-5580" ]] || modem="$HOME/.android/modem-nv-ram-5580"
cleanup() {
    result=$?
    trap - EXIT INT TERM
    set +e
    if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
        "$adb_bin" -s "$ANDROID_SERIAL" emu kill >/dev/null 2>&1
        for ((i=0; i<20; i++)); do
            kill -0 "$pid" 2>/dev/null || break
            sleep 1
        done
        if kill -0 "$pid" 2>/dev/null; then kill "$pid"; sleep 2; fi
        if kill -0 "$pid" 2>/dev/null; then
            echo "CLEANUP FAIL: emulator still running; temporary data retained at $run" >&2
            rmdir "$lock"
            exit 1
        fi
        wait "$pid" 2>/dev/null
    fi
    if [[ -n "$ini" ]]; then rm -f -- "$ini" || result=1; fi
    if [[ -n "$modem" ]]; then rm -f -- "$modem" || result=1; fi
    if [[ -n "$run" ]]; then
        # Exact mktemp-created directory only; never clear an AVD or other cache.
        rm -rf -- "$run"
        if [[ -e "$run" ]]; then result=1; echo "CLEANUP FAIL: $run remains" >&2;
        else echo 'S25_CLEANUP_PASS: session data removed; local evidence retained'; fi
    fi
    rmdir "$lock" || result=1
    exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
run=$(mktemp -d "$root/.build/s25-session.XXXXXX")
export S25_RUN_DIR="$run"
mkdir "$run/device.avd"
cp "$avd_home/$template.avd/config.ini" "$run/device.avd/config.ini"
ini="$avd_home/$avd.ini"
printf 'avd.ini.encoding=UTF-8\npath=%s\ntarget=android-35\n' "$run/device.avd" > "$ini"
export S25_EVIDENCE_DIR
S25_EVIDENCE_DIR=$(mktemp -d "$root/reports/s25-emulator-$(date +%Y%m%d-%H%M%S).XXXXXX")
printf 'serial=%s\navd=%s\ntemporary_data=%s\n' "$ANDROID_SERIAL" "$avd" "$run" > "$S25_EVIDENCE_DIR/session.txt"
options=(-no-snapshot -no-metrics)
[[ "${S25_HEADLESS:-0}" == 1 ]] && options+=(-no-window)
(
    trap - EXIT INT TERM
    exec "$emulator" -avd "$avd" -port 5580 -memory 4096 -cores 2 -gpu host \
        "${options[@]}" > "$S25_EVIDENCE_DIR/emulator.log" 2>&1
) &
pid=$!
ready=0
for ((i=0; i<180; i++)); do
    kill -0 "$pid" 2>/dev/null || { tail -n 35 "$S25_EVIDENCE_DIR/emulator.log" >&2; exit 1; }
    if [[ "$("$adb_bin" -s "$ANDROID_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then ready=1; break; fi
    sleep 1
done
[[ "$ready" == 1 ]] || { echo "BOOT FAIL: see $S25_EVIDENCE_DIR/emulator.log" >&2; exit 1; }
[[ "$("$adb_bin" -s "$ANDROID_SERIAL" emu avd name | tr -d '\r' | head -n 1)" == "$avd" ]]
printf 'S25_READY: %s; evidence=%s\n' "$ANDROID_SERIAL" "$S25_EVIDENCE_DIR"
"$@"
