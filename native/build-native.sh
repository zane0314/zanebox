#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
NATIVE_ROOT="$SCRIPT_DIR"
LIBCORE_ROOT="$NATIVE_ROOT/OwnBoxForAndroid/libcore"
BUILD_DIR="$LIBCORE_ROOT/.build"
AAR="$BUILD_DIR/libcore.aar"
INPUT_HASH_FILE="$BUILD_DIR/native-input.sha256"
VERIFY_DIR="$PROJECT_ROOT/.build/native-verify"
ABI_CHECK="$PROJECT_ROOT/tests/check_native_abi.py"

ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/25.0.8775105}"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
GO_VERSION="1.26.8"
GO_BIN="${GO_BIN:-$PROJECT_ROOT/.toolchains/go-$GO_VERSION/go/bin/go}"
GOMOBILE_BIN="${GOMOBILE_BIN:-/Users/zane/go/bin/gomobile-matsuri}"
GOBIND_BIN="${GOBIND_BIN:-/Users/zane/go/bin/gobind-matsuri}"
PYTHON_BIN="${PYTHON_BIN:-$(command -v python3 || true)}"

export ANDROID_HOME ANDROID_NDK_HOME JAVA_HOME
export GOTOOLCHAIN=local
export GOFLAGS=-mod=readonly
export GOBIND=gobind-matsuri
export PATH="$(dirname "$GO_BIN"):$JAVA_HOME/bin:$(dirname "$GOMOBILE_BIN"):/opt/homebrew/bin:$PATH"

GOMOBILE_VERSION="v0.0.0-20250830135019-17d6af34f6bd"
NDK_VERSION="25.0.8775105"
ANDROID_API=23
NATIVE_ABI="arm64-v8a"
NATIVE_ENTRY="jni/$NATIVE_ABI/libgojni.so"
NATIVE_TAGS='with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api'
NATIVE_LDFLAGS='-s -w -X github.com/sagernet/sing-box/constant.Version=1.14.0 -extldflags=-Wl,-z,max-page-size=16384'

die() { printf 'native build failed: %s\n' "$*" >&2; exit 1; }
log() { printf '[AnyBox native] %s\n' "$*"; }
need_exec() { [ -x "$1" ] || die "missing executable: $1"; }
need_file() { [ -f "$1" ] || die "missing file: $1"; }

find_ndk_host() {
  local host
  host="$(find "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt" -mindepth 1 -maxdepth 1 \
    -type d -name 'darwin-*' -print | LC_ALL=C sort | sed -n '1p')"
  [ -n "$host" ] || die "NDK LLVM host directory not found under $ANDROID_NDK_HOME"
  printf '%s\n' "$host"
}

check_toolchain() {
  local revision gomobile_info gobind_info ndk_host
  [ -n "$GO_BIN" ] || die "go is not on PATH"
  need_exec "$GO_BIN"
  need_exec "$GOMOBILE_BIN"
  need_exec "$GOBIND_BIN"
  need_file "$ANDROID_NDK_HOME/source.properties"
  ndk_host="$(find_ndk_host)"
  need_exec "$ndk_host/bin/aarch64-linux-android${ANDROID_API}-clang"
  need_exec "$ndk_host/bin/llvm-readelf"

  [ "$("$GO_BIN" env GOVERSION)" = "go$GO_VERSION" ] \
    || die "Go $GO_VERSION required; got $("$GO_BIN" env GOVERSION)"
  revision="$(sed -n 's/^Pkg.Revision = //p' "$ANDROID_NDK_HOME/source.properties")"
  [ "$revision" = "$NDK_VERSION" ] \
    || die "Android NDK $NDK_VERSION required; got ${revision:-unknown}"
  gomobile_info="$("$GO_BIN" version -m "$GOMOBILE_BIN")"
  case "$gomobile_info" in
    *"golang.org/x/mobile"*"$GOMOBILE_VERSION"*) ;;
    *) die "gomobile-matsuri is not fixed x/mobile $GOMOBILE_VERSION" ;;
  esac
  gobind_info="$("$GO_BIN" version -m "$GOBIND_BIN")"
  case "$gobind_info" in
    *"golang.org/x/mobile"*"$GOMOBILE_VERSION"*) ;;
    *) die "gobind-matsuri is not fixed x/mobile $GOMOBILE_VERSION" ;;
  esac
  [ -d "$LIBCORE_ROOT" ] || die "missing libcore source tree: $LIBCORE_ROOT"
  [ -f "$LIBCORE_ROOT/go.mod" ] || die "missing libcore go.mod"
  log "fixed Go $GO_VERSION, NDK $NDK_VERSION, gomobile/gobind $GOMOBILE_VERSION checked"
}

native_input_hash() {
  {
    printf '%s\n' \
      'native-input-schema=2-portable' \
      "android-api=$ANDROID_API" \
      "abi=$NATIVE_ABI" \
      "ndk=$NDK_VERSION" \
      "gomobile=$GOMOBILE_VERSION" \
      "tags=$NATIVE_TAGS" \
      "ldflags=$NATIVE_LDFLAGS"
    find "$NATIVE_ROOT" -type f \
      ! -path '*/.build/*' ! -path '*/.git/*' ! -name 'BASELINE.json' ! -name 'build-native.sh' ! -name '._*' ! -name '.DS_Store' -print \
      | LC_ALL=C sort \
      | while IFS= read -r file; do
          printf 'path=%s\n' "${file#"$PROJECT_ROOT/"}"
          shasum -a 256 "$file" | awk '{print $1}'
        done
  } | shasum -a 256 | awk '{print $1}'
}

extract_artifact() {
  local ndk_host lib elf_header
  mkdir -p "$VERIFY_DIR"
  [ -s "$AAR" ] || die "native AAR is empty: $AAR"
  unzip -t "$AAR" >/dev/null || die "native AAR is not a valid zip: $AAR"
  unzip -Z1 "$AAR" | grep -Fx "$NATIVE_ENTRY" >/dev/null \
    || die "native AAR has no $NATIVE_ENTRY"
  unzip -p "$AAR" classes.jar > "$VERIFY_DIR/classes.jar"
  unzip -p "$AAR" "$NATIVE_ENTRY" > "$VERIFY_DIR/libgojni.so"
  [ -s "$VERIFY_DIR/classes.jar" ] || die "native AAR classes.jar is empty"
  [ -s "$VERIFY_DIR/libgojni.so" ] || die "native AAR libgojni.so is empty"

  ndk_host="$(find_ndk_host)"
  lib="$VERIFY_DIR/libgojni.so"
  elf_header="$("$ndk_host/bin/llvm-readelf" -h "$lib")"
  printf '%s\n' "$elf_header" | grep -Eq 'Machine:[[:space:]]+AArch64' \
    || die "native library is not AArch64"
  "$ndk_host/bin/llvm-readelf" -l "$lib" \
    | awk '/ LOAD / { if ($NF != "0x4000") bad=1; count++ } END { exit(count >= 1 && !bad ? 0 : 1) }' \
    || die "native library has a LOAD segment not aligned to 16 KiB"
}

check_legacy_abi() {
  [ -n "$PYTHON_BIN" ] || die "python3 is required for native ABI verification"
  need_file "$ABI_CHECK"
  "$PYTHON_BIN" "$ABI_CHECK"
}

build_native() {
  local input_hash previous_hash aar_tmp final_input_hash
  input_hash="$(native_input_hash)"
  previous_hash=""
  [ -f "$INPUT_HASH_FILE" ] && previous_hash="$(sed -n '1p' "$INPUT_HASH_FILE")"

  if [ "${FORCE_BUILD:-0}" != 1 ] && [ -s "$AAR" ] && [ "$input_hash" = "$previous_hash" ]; then
    log "native sources unchanged; reusing $AAR"
  else
    mkdir -p "$BUILD_DIR/android"
    aar_tmp="$BUILD_DIR/libcore.new.aar"
    rm -f "$aar_tmp"
    log "building current native sources with gomobile (hash $input_hash)"
    (
      cd "$LIBCORE_ROOT"
      "$GOMOBILE_BIN" bind \
        -target=android/arm64 \
        -androidapi "$ANDROID_API" \
        -cache "$BUILD_DIR/android" \
        -trimpath \
        -ldflags="$NATIVE_LDFLAGS" \
        -tags="$NATIVE_TAGS" \
        -o "$aar_tmp" \
        .
    )
    [ -s "$aar_tmp" ] || die "gomobile did not produce $aar_tmp"
    mv -f "$aar_tmp" "$AAR"
  fi

  extract_artifact
  check_legacy_abi
  final_input_hash="$(native_input_hash)"
  [ "$final_input_hash" = "$input_hash" ] \
    || die "native sources changed during build/check; rerun against one stable snapshot"
  # Record the hash only after the AAR, ELF and 285-member ABI checks pass.
  printf '%s\n' "$input_hash" > "$INPUT_HASH_FILE"
  log "native artifact verified: $AAR"
}

ACTION=build
FORCE_BUILD=0
case "${1:-}" in
  '') ;;
  --check) ACTION=check ;;
  --force) FORCE_BUILD=1 ;;
  --help|-h)
    cat <<'EOF'
用法：native/build-native.sh [--check|--force]

--check 只检查固定 Go/NDK/gomobile 工具链，不编译。
默认按 native 源码哈希增量编译当前 libcore，并核验 AAR、ELF 16 KiB 对齐及旧 libcore ABI。
--force 忽略源码哈希，强制重新生成 AAR。
EOF
    exit 0
    ;;
  *) die "unknown argument: $1" ;;
esac

check_toolchain
[ "$ACTION" = check ] && exit 0
build_native
