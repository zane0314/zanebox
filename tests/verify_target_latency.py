#!/usr/bin/env python3
"""Verify the target-latency release package on the one managed S25 emulator."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import subprocess
import tempfile
import urllib.request


ROOT = Path(__file__).resolve().parents[1]
ADB = Path("/opt/homebrew/bin/adb")
SERIAL = "emulator-5580"
AVD = "ZaneBox_S25Plus_API35_Acceptance"
PACKAGE = "com.zane.zanebox"
DATABASE = f"/data/user/0/{PACKAGE}/databases/zanebox.db"
CERT_SHA256 = "501a5ab904e2e601d38cf8b04b19cc3165a503235710d888ff121d2c55d9ee29"
PROBE_URL = "http://203.0.113.9:19080/probe"
PHASES = ("smart", "smart-targets", "merges", "connect", "disconnect")


class AcceptanceError(RuntimeError):
    pass


def command(args: list[str], *, binary: bool = False) -> bytes | str:
    return subprocess.check_output(args, text=not binary, stderr=subprocess.STDOUT)


def adb(*args: str, binary: bool = False) -> bytes | str:
    return command([str(ADB), "-s", SERIAL, *args], binary=binary)


def shell(*args: str) -> str:
    return str(adb("shell", *args)).strip()


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n", encoding="utf-8")


def resolved(path: Path) -> Path:
    return path if path.is_absolute() else ROOT / path


def sdk_tools() -> tuple[Path, Path]:
    local = ROOT / "local.properties"
    values = dict(line.split("=", 1) for line in local.read_text(encoding="utf-8").splitlines() if "=" in line)
    sdk = Path(values.get("sdk.dir", os.environ.get("ANDROID_SDK_ROOT", "/opt/homebrew/share/android-commandlinetools")))
    tools = sdk / "build-tools" / "35.0.0"
    apksigner, aapt = tools / "apksigner", tools / "aapt"
    if not apksigner.is_file() or not aapt.is_file():
        raise AcceptanceError(f"Android build-tools 35.0.0 缺少 apksigner/aapt：{tools}")
    return apksigner, aapt


def apk_info(apk: Path, apksigner: Path, aapt: Path) -> dict[str, object]:
    if not apk.is_file():
        raise AcceptanceError(f"APK 不存在：{apk}")
    badging = str(command([str(aapt), "dump", "badging", str(apk)]))
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'", badging, re.M)
    if not package:
        raise AcceptanceError(f"无法读取 APK package/version：{apk}")
    manifest = str(command([str(aapt), "dump", "xmltree", str(apk), "AndroidManifest.xml"]))
    debug_flag = re.search(r"android:debuggable\([^)]*\)=[^\r\n]*(?:0xffffffff|\btrue\b)", manifest, re.I)
    signer = str(command([str(apksigner), "verify", "--print-certs", str(apk)]))
    digest = re.search(r"certificate SHA-256 digest:\s*([0-9a-f:]+)", signer, re.I)
    if not digest:
        raise AcceptanceError(f"APK 未提供可识别的签名证书 SHA256：{apk}")
    return {
        "path": str(apk),
        "package": package.group(1),
        "versionCode": int(package.group(2)),
        "versionName": package.group(3),
        "debuggable": bool(debug_flag),
        "certificateSha256": digest.group(1).replace(":", "").lower(),
    }


def check_release_apk(info: dict[str, object], version: dict[str, str], label: str) -> None:
    if info["package"] != PACKAGE:
        raise AcceptanceError(f"{label} APK package 错误：{info['package']}")
    if info["debuggable"]:
        raise AcceptanceError(f"{label} APK 带 debuggable 标记")
    if info["certificateSha256"] != CERT_SHA256:
        raise AcceptanceError(f"{label} APK 长期签名证书不匹配：{info['certificateSha256']}")
    if info["versionName"] != version["versionName"] or str(info["versionCode"]) != version["versionCode"]:
        raise AcceptanceError(
            f"{label} APK 版本 {info['versionName']}/{info['versionCode']} 与当前 version.properties "
            f"{version['versionName']}/{version['versionCode']} 不一致"
        )


def version_properties() -> dict[str, str]:
    path = ROOT / "version.properties"
    values = dict(line.split("=", 1) for line in path.read_text(encoding="utf-8").splitlines() if "=" in line)
    if "versionName" not in values or "versionCode" not in values:
        raise AcceptanceError("version.properties 缺少 versionName/versionCode")
    return values


def require_target_emulator() -> None:
    if os.environ.get("ANDROID_SERIAL") != SERIAL:
        raise AcceptanceError(f"ANDROID_SERIAL 必须严格为 {SERIAL}")
    if not ADB.is_file() or not os.access(ADB, os.X_OK):
        raise AcceptanceError(f"指定 ADB 不可执行：{ADB}")
    devices = str(command([str(ADB), "devices"])).splitlines()[1:]
    running = [line.split()[0] for line in devices if line.split() and line.split()[0].startswith("emulator-") and len(line.split()) > 1 and line.split()[1] == "device"]
    if running != [SERIAL]:
        raise AcceptanceError(f"只允许唯一模拟器 {SERIAL}，当前在线模拟器：{running}")
    if str(adb("get-state")).strip() != "device":
        raise AcceptanceError(f"{SERIAL} 当前不可用")
    name = str(adb("emu", "avd", "name")).splitlines()[0].strip()
    if name != AVD:
        raise AcceptanceError(f"模拟器 AVD 不匹配：{name}")
    root = str(adb("root"))
    if "cannot run as root" in root.lower():
        raise AcceptanceError(f"指定验收模拟器不能取得 adb root：{root}")
    adb("wait-for-device")
    if shell("id", "-u") != "0":
        raise AcceptanceError("adb root 后 uid 不是 0")


def installed_apk(apksigner: Path, aapt: Path, temp: Path) -> dict[str, object] | None:
    paths = str(adb("shell", "pm", "path", PACKAGE)).splitlines()
    package_paths = [line.removeprefix("package:").strip() for line in paths if line.startswith("package:")]
    if not package_paths:
        return None
    base = next((path for path in package_paths if path.endswith("/base.apk")), package_paths[0])
    local = temp / "installed-base.apk"
    adb("pull", base, str(local))
    return apk_info(local, apksigner, aapt)


def install(apk: Path) -> None:
    output = str(adb("install", "-r", "-d", str(apk)))
    if "Success" not in output:
        raise AcceptanceError(f"安装失败：{output.strip()}")
    adb("shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")


def uninstall_test_app() -> None:
    output = str(adb("uninstall", PACKAGE))
    if "Success" not in output:
        raise AcceptanceError(f"卸载唯一测试包失败：{output.strip()}")


def stop_app() -> None:
    adb("shell", "am", "force-stop", PACKAGE + ".test")
    adb("shell", "am", "force-stop", PACKAGE)
    processes = str(adb("shell", "ps", "-A", "-o", "PID,ARGS")).splitlines()
    running = [line for line in processes if re.search(r"\scom\.zane\.zanebox(?::\S+)?$", line)]
    if running:
        raise AcceptanceError(f"{PACKAGE} force-stop 后仍有进程，拒绝读写数据库文件：{running}")


def launch_and_stop() -> None:
    result = shell("am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity")
    if "Status: ok" not in result:
        raise AcceptanceError(f"启动 {PACKAGE} 失败：{result}")
    stop_app()


def checkpoint_seed(source: Path, work: Path) -> Path:
    if not source.is_file():
        raise AcceptanceError(f"seed-db 不存在：{source}")
    folder = work / "seed-checkpoint"
    folder.mkdir(parents=True, exist_ok=True)
    database = folder / "zanebox.db"
    shutil.copy2(source, database)
    for suffix in ("-wal", "-shm"):
        sidecar = Path(str(source) + suffix)
        if sidecar.exists():
            shutil.copy2(sidecar, Path(str(database) + suffix))
    connection = sqlite3.connect(database)
    try:
        checkpoint = connection.execute("PRAGMA wal_checkpoint(TRUNCATE)").fetchone()
        if checkpoint and checkpoint[0] != 0:
            raise AcceptanceError(f"seed-db 本机 WAL checkpoint 忙碌：{checkpoint}")
        mode = connection.execute("PRAGMA journal_mode=DELETE").fetchone()
        if not mode or mode[0].lower() != "delete":
            raise AcceptanceError(f"seed-db 无法切换为已 checkpoint 的 DELETE journal：{mode}")
        check_database(connection, expected_version=3)
    finally:
        connection.close()
    for suffix in ("-wal", "-shm"):
        Path(str(database) + suffix).unlink(missing_ok=True)
    return database


def check_database(connection: sqlite3.Connection, expected_version: int = 3) -> None:
    version = connection.execute("PRAGMA user_version").fetchone()[0]
    if version != expected_version:
        raise AcceptanceError(f"SQLite schema 版本应为 {expected_version}，实际为 {version}")
    check = connection.execute("PRAGMA quick_check").fetchone()[0]
    if check != "ok":
        raise AcceptanceError(f"SQLite quick_check 失败：{check}")
    tables = {row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    required = {"state", "sequence", "node_status", "kv"}
    if not required.issubset(tables):
        raise AcceptanceError(f"SQLite 缺少 schema v3 表：{sorted(required - tables)}")


def push_database(local_db: Path, *, sidecars: bool, clear_old_sidecars: bool) -> None:
    db_dir = DATABASE.rsplit("/", 1)[0]
    uid_gid = shell("stat", "-c", "%u:%g", f"/data/user/0/{PACKAGE}")
    if not re.fullmatch(r"\d+:\d+", uid_gid):
        raise AcceptanceError(f"无法读取 {PACKAGE} 正确 uid:gid：{uid_gid}")
    remote = f"/data/local/tmp/zanebox-target-latency-{os.getpid()}.db"
    remote_paths = [remote]
    try:
        adb("push", str(local_db), remote)
        if sidecars:
            for suffix in ("-wal", "-shm"):
                local_sidecar = Path(str(local_db) + suffix)
                if local_sidecar.exists():
                    adb("push", str(local_sidecar), remote + suffix)
                    remote_paths.append(remote + suffix)
        shell("mkdir", "-p", db_dir)
        shell("chown", uid_gid, db_dir)
        shell("restorecon", db_dir)
        if clear_old_sidecars:
            shell("rm", "-f", DATABASE + "-wal", DATABASE + "-shm")
        shell("cp", remote, DATABASE)
        if sidecars:
            for suffix in ("-wal", "-shm"):
                local_sidecar = Path(str(local_db) + suffix)
                if local_sidecar.exists():
                    shell("cp", remote + suffix, DATABASE + suffix)
        for path in [DATABASE, *[DATABASE + suffix for suffix in ("-wal", "-shm") if sidecars and Path(str(local_db) + suffix).exists()]]:
            shell("chown", uid_gid, path)
            shell("restorecon", path)
    finally:
        for path in remote_paths:
            subprocess.run([str(ADB), "-s", SERIAL, "shell", "rm", "-f", path], check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def inject_seed(seed: Path) -> None:
    stop_app()
    push_database(seed, sidecars=False, clear_old_sidecars=True)


def pull_database(label: str, out: Path) -> tuple[Path, dict[str, object], list[str]]:
    folder = out / f"database-{label}"
    folder.mkdir(parents=True, exist_ok=True)
    local_db = folder / "zanebox.db"
    for suffix in ("", "-wal", "-shm"):
        Path(str(local_db) + suffix).unlink(missing_ok=True)
    adb("pull", DATABASE, str(local_db))
    sidecars: list[str] = []
    for suffix in ("-wal", "-shm"):
        remote = DATABASE + suffix
        status = subprocess.run([str(ADB), "-s", SERIAL, "shell", "test", "-f", remote], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode
        if status not in (0, 1) or str(adb("get-state")).strip() != "device":
            raise AcceptanceError(f"无法确认数据库 sidecar 状态：{remote}")
        exists = status == 0
        if exists:
            adb("pull", remote, str(Path(str(local_db) + suffix)))
            sidecars.append(suffix)
    return local_db, synthesize_app_data(local_db), sidecars


def synthesize_app_data(local_db: Path) -> dict[str, object]:
    connection = sqlite3.connect(local_db)
    try:
        check_database(connection)
        payload = json.loads(connection.execute("SELECT payload FROM state WHERE id=1").fetchone()[0])
        data = {key: payload.get(key, default) for key, default in (("nodes", []), ("groups", []), ("rules", []), ("merges", []), ("settings", {}))}
        statuses = {
            int(row[0]): {"ping": int(row[1]), "status": int(row[2]), "tx": int(row[3]), "rx": int(row[4])}
            for row in connection.execute("SELECT node_id,ping,status,tx,rx FROM node_status")
        }
        for node in data["nodes"]:
            values = {"ping": -1, "status": 0, "tx": 0, "rx": 0}
            values.update(statuses.get(int(node["id"]), {}))
            node.update(values)
        data["settings"].update({key: value for key, value in connection.execute("SELECT key,value FROM kv WHERE value IS NOT NULL")})
        return data
    except (sqlite3.Error, ValueError, TypeError, KeyError, IndexError) as error:
        raise AcceptanceError(f"读取/合成完整 AppData 失败：{local_db}: {error}") from error
    finally:
        connection.close()


def compare_app_data(before: dict[str, object], after: dict[str, object]) -> None:
    if before != after:
        before_json = json.dumps(before, ensure_ascii=False, sort_keys=True, indent=2).splitlines()
        after_json = json.dumps(after, ensure_ascii=False, sort_keys=True, indent=2).splitlines()
        import difflib

        diff = "\n".join(list(difflib.unified_diff(before_json, after_json, fromfile="before", tofile="after"))[:50])
        raise AcceptanceError(f"覆盖安装后完整 AppData 不相等：\n{diff}")


def validate_latency_fixture(data: dict[str, object]) -> None:
    nodes = {int(node["id"]): node for node in data["nodes"]}
    groups = {int(group["id"]): group for group in data["groups"]}
    merges = {int(merge["id"]): merge for merge in data["merges"]}
    settings = data["settings"]
    if not {6101, 6102}.issubset(nodes) or any(nodes[node]["groupId"] != 6101 for node in (6101, 6102)) or 6101 not in groups:
        raise AcceptanceError("UI fixture 缺少节点 6101/6102 及同组 6101")
    if (nodes[6101]["ping"], nodes[6102]["ping"]) != (900, 5):
        raise AcceptanceError("UI fixture 初始缓存延迟必须为 A=900ms、B=5ms")
    if settings.get("selectedNodeId") != "6102" or settings.get("homeAutoSelect") != "false":
        raise AcceptanceError("UI fixture 首页主节点必须是 6102")
    expected_targets = {"youtube": "group:6101", "telegram": "merge:6105", "netflix": "merge:6106", "ai": "auto"}
    if any(settings.get(f"smart.{key}.target") != value for key, value in expected_targets.items()):
        raise AcceptanceError("UI fixture smart target 与本轮约定不一致")
    if merges.get(6105, {}).get("mode") != "selector" or merges.get(6106, {}).get("mode") != "urltest":
        raise AcceptanceError("UI fixture 汇总组必须是 6105=manual、6106=urltest")


def ensure_forward() -> bool:
    forwards = str(adb("forward", "--list")).splitlines()
    matching = [line.split() for line in forwards if len(line.split()) >= 3 and line.split()[1] == "tcp:12080"]
    if matching:
        if len(matching) == 1 and matching[0][0] == SERIAL and matching[0][2] == "tcp:2080":
            return False
        raise AcceptanceError(f"ADB tcp:12080 已有冲突映射：{matching}")
    adb("forward", "tcp:12080", "tcp:2080")
    return True


def mcp_payload(response: dict[str, object]) -> dict[str, object]:
    content = response.get("content", [])
    text = next((item.get("text") for item in content if isinstance(item, dict) and item.get("type") == "text"), None)
    if not isinstance(text, str):
        raise AcceptanceError(f"Maestro MCP 未返回文本结果：{response}")
    try:
        result = json.loads(text)
    except json.JSONDecodeError as error:
        raise AcceptanceError(f"Maestro MCP 返回非 JSON：{text}") from error
    if not isinstance(result, dict):
        raise AcceptanceError(f"Maestro MCP 返回格式错误：{text}")
    return result


def maestro_phases(source: Path) -> tuple[str, dict[str, str]]:
    header: list[str] = []
    phases: dict[str, list[str]] = {}
    current = None
    for line in source.read_text(encoding="utf-8").splitlines():
        match = re.fullmatch(r"# @phase ([a-z-]+)", line.strip())
        if match:
            current = match.group(1)
            if current in phases:
                raise AcceptanceError(f"Maestro YAML phase 重复：{current}")
            phases[current] = []
        elif current is None:
            header.append(line)
        else:
            phases[current].append(line)
    if tuple(phases) != PHASES:
        raise AcceptanceError(f"Maestro YAML phase 顺序不匹配：{tuple(phases)}")
    prefix = "\n".join(header).rstrip() + "\n"
    return prefix, {name: prefix + "\n".join(lines).strip() + "\n" for name, lines in phases.items()}


def capture_screenshot(path: Path) -> None:
    image = bytes(adb("exec-out", "screencap", "-p", binary=True))
    if not image.startswith(b"\x89PNG\r\n\x1a\n"):
        raise AcceptanceError("adb exec-out screencap 未返回 PNG")
    path.write_bytes(image)


def run_ui(out: Path, version: dict[str, str], appdata: dict[str, object]) -> dict[str, object]:
    validate_latency_fixture(appdata)
    maestro_binary = ROOT / ".toolchains/maestro/bin/maestro"
    if not maestro_binary.is_file():
        raise AcceptanceError(f"Maestro MCP binary 不存在：{maestro_binary}")
    source = ROOT / "tests/maestro/target-latency.yaml"
    _, phases = maestro_phases(source)
    temporary = Path(tempfile.mkdtemp(prefix="target-latency-mcp-", dir=out))
    log = temporary / "maestro-mcp.log"
    env = {"VERSION_NAME": version["versionName"], "VERSION_CODE": version["versionCode"]}
    created_forward = False
    completed: list[str] = []
    expected_exit = None
    from maestro_mcp_client import McpClient

    client = None
    try:
        created_forward = ensure_forward()
        for line in str(adb("shell", "ps", "-A", "-o", "PID,ARGS")).splitlines():
            if "com.mobilenext.mobilecli.DeviceServer" in line:
                subprocess.run([str(ADB), "-s", SERIAL, "shell", "kill", line.split()[0]], check=False, stdout=subprocess.DEVNULL)
        client = McpClient(maestro_binary, log)
        client.initialize()
        listed = mcp_payload(client.request("tools/call", {"name": "list_devices", "arguments": {}}))
        if not any(item.get("device_id") == SERIAL and item.get("connected") for item in listed.get("devices", [])):
            raise AcceptanceError(f"Maestro MCP 未连接指定设备 {SERIAL}：{listed}")
        for phase in PHASES:
            flow = out / "target-latency.yaml"
            flow.write_text(phases[phase], encoding="utf-8")
            result = mcp_payload(client.request("tools/call", {"name": "run", "arguments": {
                "device_id": SERIAL, "files": [str(flow)], "env": env,
            }}))
            if result.get("success") is not True:
                raise AcceptanceError(f"Maestro phase {phase} 失败：{result}")
            completed.append(phase)
            if phase == "smart":
                capture_screenshot(out / "smart-target-latency.png")
            elif phase == "merges":
                capture_screenshot(out / "merge-target-latency.png")
                # The app records each clicked group's own measured winner; another group's round may refresh node pings.
                _, selected, _ = pull_database("ui-choice", out)
                choice = selected["settings"].get("groupSelection.group-6101")
                if choice not in ("node-6101", "node-6102"):
                    raise AcceptanceError(f"release UI 未保存本轮分组择优：{choice}")
                expected_exit = "EXIT_A" if choice == "node-6101" else "EXIT_B"
                successful = [node for node in selected["nodes"] if node["id"] in (6101, 6102) and node["ping"] > 0 and node["status"] != 1]
                lowest = min(successful, key=lambda node: node["ping"])
                env["LOWEST_NODE_NAME"] = lowest["name"]
                env["LOWEST_NODE_DELAY"] = f"{lowest['ping']} ms"
            elif phase == "connect":
                opener = urllib.request.build_opener(urllib.request.ProxyHandler({"http": "http://127.0.0.1:12080"}))
                with opener.open(PROBE_URL, timeout=8) as response:
                    exit_name = response.read().decode("utf-8").strip()
                if exit_name != expected_exit:
                    raise AcceptanceError(f"实际代理出口应为本轮择优 {expected_exit}，收到 {exit_name!r}")
                capture_screenshot(out / "smart-lowest-connected.png")
        return {"status": "PASS", "phases": completed, "proxyExit": expected_exit}
    finally:
        if client is not None:
            client.close()
        subprocess.run([str(ADB), "-s", SERIAL, "shell", "am", "force-stop", "dev.mobile.maestro"], check=False, stdout=subprocess.DEVNULL)
        if created_forward:
            subprocess.run([str(ADB), "-s", SERIAL, "forward", "--remove", "tcp:12080"], check=False, stdout=subprocess.DEVNULL)
        shutil.rmtree(temporary, ignore_errors=True)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("out", type=Path)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--previous-apk", type=Path)
    parser.add_argument("--seed-db", type=Path)
    parser.add_argument("--ui", action="store_true")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    out = resolved(args.out)
    apk = resolved(args.apk)
    previous = resolved(args.previous_apk) if args.previous_apk else None
    seed_path = resolved(args.seed_db) if args.seed_db else None
    out.mkdir(parents=True, exist_ok=True)
    summary_path = out / "summary.json"
    result: dict[str, object] = {"status": "FAIL", "checks": [], "apk": str(apk), "uiRequested": args.ui}
    current_step = "初始化"
    device_ready = False
    crash_saved = False

    def passed(name: str, details: object = True) -> None:
        result["checks"].append({"name": name, "status": "PASS", "details": details})

    try:
        current_step = "APK 与工具链校验"
        apksigner, aapt = sdk_tools()
        version = version_properties()
        candidate_info = apk_info(apk, apksigner, aapt)
        check_release_apk(candidate_info, version, "candidate")
        result["apkInfo"] = candidate_info
        passed(current_step, {"version": f"{version['versionName']}/{version['versionCode']}", "certificateSha256": CERT_SHA256})
        if previous:
            previous_info = apk_info(previous, apksigner, aapt)
            if previous_info["package"] != PACKAGE:
                raise AcceptanceError(f"previous APK package 错误：{previous_info['package']}")
            if previous_info["debuggable"] or previous_info["certificateSha256"] != CERT_SHA256:
                raise AcceptanceError("previous APK 必须是长期签名的正式非 debug 包")
            result["previousApkInfo"] = previous_info
            passed("previous APK package", previous_info["versionName"])

        current_step = "隔离模拟器身份与权限"
        require_target_emulator()
        device_ready = True
        adb("logcat", "-c", "-b", "crash")
        passed(current_step, {"serial": SERIAL, "avd": AVD, "adb": str(ADB)})

        with tempfile.TemporaryDirectory(prefix="target-latency-work-", dir=out) as scratch:
            work = Path(scratch)
            seed = checkpoint_seed(seed_path, work) if seed_path else None
            if seed:
                passed("seed-db 本机 checkpoint", "schema v3; WAL checkpointed")
            installed = installed_apk(apksigner, aapt, work)
            debug_installed = False
            if installed:
                if installed["certificateSha256"] != CERT_SHA256:
                    if not installed["debuggable"]:
                        raise AcceptanceError(f"设备上 Links 使用未知非 debug 签名，拒绝卸载：{installed['certificateSha256']}")
                    debug_installed = True
                passed("检测已安装 Links", {"versionName": installed["versionName"], "debuggable": installed["debuggable"], "debugSignature": debug_installed})

            if previous:
                if debug_installed:
                    stop_app()
                    uninstall_test_app()
                    passed("仅卸载本测试 app 的 debug 签名包")
                install(previous)
                if seed:
                    inject_seed(seed)
                launch_and_stop()
                before_db, before, before_sidecars = pull_database("before", out)
                write_json(out / "before-app-data.json", before)
                result["databaseBefore"] = str(before_db)
                result["databaseBeforeSidecars"] = before_sidecars
                passed("旧版启动后读取完整 schema v3 AppData", {"databaseDirectory": str(before_db.parent), "sidecars": before_sidecars})
                stop_app()
                install(apk)
                launch_and_stop()
                after_db, after, after_sidecars = pull_database("after", out)
                write_json(out / "after-app-data.json", after)
                result["databaseAfter"] = str(after_db)
                result["databaseAfterSidecars"] = after_sidecars
                compare_app_data(before, after)
                result["appDataEqual"] = True
                passed("正式旧版覆盖升级完整 AppData 相等")
            elif installed:
                if seed:
                    inject_seed(seed)
                launch_and_stop()
                before_db, before, before_sidecars = pull_database("before", out)
                write_json(out / "before-app-data.json", before)
                result["databaseBefore"] = str(before_db)
                result["databaseBeforeSidecars"] = before_sidecars
                passed("安装前完整 schema v3 AppData", {"databaseDirectory": str(before_db.parent), "sidecars": before_sidecars})
                if debug_installed:
                    stop_app()
                    uninstall_test_app()
                    install(apk)
                    stop_app()
                    restored = checkpoint_seed(before_db, work / "debug-restore")
                    push_database(restored, sidecars=False, clear_old_sidecars=True)
                else:
                    install(apk)
                launch_and_stop()
                after_db, after, after_sidecars = pull_database("after", out)
                write_json(out / "after-app-data.json", after)
                result["databaseAfter"] = str(after_db)
                result["databaseAfterSidecars"] = after_sidecars
                compare_app_data(before, after)
                result["appDataEqual"] = True
                passed("覆盖安装完整 AppData 相等")
            else:
                install(apk)
                if seed:
                    inject_seed(seed)
                launch_and_stop()
                after_db, after, after_sidecars = pull_database("after", out)
                write_json(out / "after-app-data.json", after)
                result["databaseAfter"] = str(after_db)
                result["databaseAfterSidecars"] = after_sidecars
                result["appDataEqual"] = None
                passed("首次安装并启动 candidate", {"databaseDirectory": str(after_db.parent), "upgradeComparison": "no pre-existing package"})

        if args.ui:
            current_step = "Maestro MCP release UI 与实际代理出口"
            ui_result = run_ui(out, version, after)
            result["ui"] = ui_result
            passed(current_step, ui_result)
        current_step = "Crash buffer"
        crash = str(adb("logcat", "-d", "-b", "crash"))
        (out / "crash-buffer.txt").write_text(crash, encoding="utf-8")
        crash_saved = True
        if crash.strip():
            raise AcceptanceError(f"Crash buffer 非空：{out / 'crash-buffer.txt'}")
        passed(current_step, "empty; only crash buffer captured")
        result["status"] = "PASS"
        result["error"] = None
        write_json(summary_path, result)
        return 0
    except Exception as error:
        if device_ready and not crash_saved:
            try:
                crash = str(adb("logcat", "-d", "-b", "crash"))
                (out / "crash-buffer.txt").write_text(crash, encoding="utf-8")
            except Exception as capture_error:
                result["crashBufferError"] = str(capture_error)
        result["error"] = {"step": current_step, "type": type(error).__name__, "message": str(error)}
        result["checks"].append({"name": current_step, "status": "FAIL", "error": str(error)})
        write_json(summary_path, result)
        raise


if __name__ == "__main__":
    raise SystemExit(main())
