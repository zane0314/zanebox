#!/usr/bin/env python3
"""Small stdio MCP client for the project-local Maestro CLI."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
from typing import Any, TextIO


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MAESTRO = ROOT / ".toolchains/maestro/bin/maestro"
DEFAULT_LOG = ROOT / "reports/ui-1.0.2/maestro-setup/maestro-mcp.log"
PROTOCOL_VERSION = "2024-11-05"


class McpError(RuntimeError):
    pass


class McpClient:
    def __init__(self, maestro: Path, log_path: Path) -> None:
        log_path.parent.mkdir(parents=True, exist_ok=True)
        self.log: TextIO = log_path.open("a", encoding="utf-8")
        env = os.environ.copy()
        env["MAESTRO_CLI_NO_ANALYTICS"] = "1"
        env["MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED"] = "true"
        self.process = subprocess.Popen(
            [str(maestro), "mcp", "--no-viewer", "--working-dir", str(ROOT)],
            cwd=ROOT,
            env=env,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=self.log,
            text=True,
            bufsize=1,
        )
        self.next_id = 1

    def close(self) -> None:
        if self.process.stdin is not None:
            self.process.stdin.close()
        try:
            self.process.wait(timeout=2)
        except subprocess.TimeoutExpired:
            self.process.terminate()
            try:
                self.process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait()
        self.log.close()

    def send(self, message: dict[str, Any]) -> None:
        if self.process.stdin is None:
            raise McpError("MCP server stdin is unavailable")
        self.process.stdin.write(json.dumps(message, separators=(",", ":")) + "\n")
        self.process.stdin.flush()

    def read_message(self) -> dict[str, Any]:
        if self.process.stdout is None:
            raise McpError("MCP server stdout is unavailable")
        line = self.process.stdout.readline()
        if not line:
            raise McpError(f"MCP server exited with status {self.process.poll()}")
        try:
            message = json.loads(line)
        except json.JSONDecodeError as error:
            raise McpError(f"invalid MCP JSON line: {line!r}") from error
        if not isinstance(message, dict):
            raise McpError(f"invalid MCP message: {message!r}")
        return message

    def request(self, method: str, params: dict[str, Any]) -> dict[str, Any]:
        request_id = self.next_id
        self.next_id += 1
        self.send({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params})
        while True:
            message = self.read_message()
            if message.get("id") != request_id:
                continue
            if "error" in message:
                raise McpError(json.dumps(message["error"], ensure_ascii=False))
            result = message.get("result")
            if not isinstance(result, dict):
                raise McpError(f"invalid result for {method}: {message!r}")
            return result

    def notify(self, method: str, params: dict[str, Any]) -> None:
        self.send({"jsonrpc": "2.0", "method": method, "params": params})

    def initialize(self) -> dict[str, Any]:
        result = self.request(
            "initialize",
            {
                "protocolVersion": PROTOCOL_VERSION,
                "capabilities": {},
                "clientInfo": {"name": "zanebox-maestro-client", "version": "1.0"},
            },
        )
        self.notify("notifications/initialized", {})
        return result


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--maestro",
        type=Path,
        default=Path(os.environ.get("MAESTRO_BIN", DEFAULT_MAESTRO)),
        help="Maestro CLI path (default: project-local .toolchains/maestro/bin/maestro)",
    )
    parser.add_argument("--log", type=Path, default=DEFAULT_LOG, help="server stderr log path")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("list", help="print the MCP tools/list result as JSON")
    call = commands.add_parser("call", help="call one MCP tool with a JSON arguments object")
    call.add_argument("name")
    call.add_argument("arguments", nargs="?", default="{}")
    return parser


def run(arguments: argparse.Namespace) -> dict[str, Any]:
    if not arguments.maestro.is_file():
        raise McpError(f"Maestro binary not found: {arguments.maestro}")
    client = McpClient(arguments.maestro, arguments.log)
    try:
        client.initialize()
        if arguments.command == "list":
            return client.request("tools/list", {})
        try:
            params = json.loads(arguments.arguments)
        except json.JSONDecodeError as error:
            raise McpError(f"call arguments must be valid JSON: {error}") from error
        if not isinstance(params, dict):
            raise McpError("call arguments must be a JSON object")
        return client.request("tools/call", {"name": arguments.name, "arguments": params})
    finally:
        client.close()


def main(argv: list[str] | None = None) -> int:
    try:
        result = run(build_parser().parse_args(argv))
    except (McpError, OSError) as error:
        print(f"maestro-mcp-client: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
