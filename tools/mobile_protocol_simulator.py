"""Deterministic Nexus Mobile protocol simulator for CI and local acceptance.

This process uses the real device Heartbeat, Command Poll and Result HTTP APIs.
It never imports Django models or bypasses the Run Mobile Delegate. Create a
caller-owned Mobile device in Nexus first, then pass its one-time pairing token.
"""

from __future__ import annotations

import argparse
import base64
import json
import struct
import time
import urllib.error
import urllib.request
import zlib
from dataclasses import dataclass
from typing import Any


def _png(width: int = 320, height: int = 180) -> bytes:
    rows = bytearray()
    for y in range(height):
        rows.append(0)
        for x in range(width):
            color = (247, 248, 245)
            if y < 34:
                color = (28, 29, 27)
            elif 24 < x < width - 24 and 58 < y < 108:
                color = (225, 255, 194)
            rows.extend(color)
    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(bytes(rows), 9)) + chunk(b"IEND", b"")


@dataclass
class Surface:
    page: str = "main"
    focused: bool = False
    draft: str = ""
    saved: str = ""
    scrolled: bool = False

    def nodes(self) -> list[dict[str, Any]]:
        if self.page == "main":
            return [{"text": "Open Mobile E2E Surface", "clickable": True, "className": "Button"}]
        return [
            {"text": "Nexus Mobile E2E", "clickable": False, "className": "TextView"},
            {"text": "E2E message", "clickable": True, "editable": True, "className": "EditText"},
            {"text": "Apply", "clickable": True, "className": "Button"},
            {"text": f"Saved: {self.saved}" if self.saved else "Not saved", "clickable": False, "className": "TextView"},
            *([{"text": "End of Mobile E2E Surface", "clickable": False, "className": "TextView"}] if self.scrolled else []),
        ]


class MobileProtocolSimulator:
    def __init__(self, base_url: str, device_id: str, token: str, poll_seconds: float = 0.25) -> None:
        self.base_url = base_url.rstrip("/")
        self.device_id = device_id
        self._token = token
        self.poll_seconds = poll_seconds
        self.surface = Surface()

    def request(self, method: str, path: str, payload: dict[str, Any] | None = None) -> dict[str, Any]:
        body = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(
            self.base_url + path,
            data=body,
            method=method,
            headers={
                "Content-Type": "application/json; charset=utf-8",
                "Accept": "application/json",
                "X-Nexus-Mobile-Token": self._token,
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                result = json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", "replace")[:500]
            raise RuntimeError(f"Mobile protocol request failed with HTTP {exc.code}: {detail}") from None
        return result.get("data", result)

    def heartbeat(self) -> None:
        self.request("POST", f"/api/v1/mobile-devices/{self.device_id}/device/heartbeat/", {
            "current_package": "com.nexus.mobile",
            "current_activity": "MobileE2EActivity" if self.surface.page == "e2e" else "MainActivity",
            "observation": {"nodes": self.surface.nodes()},
            "capabilities": {"accessibility": True, "screen_observation": True},
        })

    def execute(self, command: dict[str, Any]) -> dict[str, Any]:
        action = str(command.get("action") or "")
        arguments = command.get("arguments") or {}
        if action == "open_app":
            self.surface.page = "main"
        elif action == "tap_text":
            target = str(arguments.get("text") or "")
            if target == "Open Mobile E2E Surface": self.surface.page = "e2e"
            elif target == "E2E message": self.surface.focused = True
            elif target == "Apply": self.surface.saved = self.surface.draft
        elif action == "tap_coordinates":
            pass
        elif action == "type_text":
            if not self.surface.focused: raise RuntimeError("E2E input is not focused")
            self.surface.draft = str(arguments.get("text") or "")
        elif action == "swipe":
            self.surface.scrolled = True
        elif action == "press_back":
            self.surface.page = "main"
        elif action == "wait_for_state":
            wanted = str(arguments.get("text") or "")
            if wanted not in json.dumps(self.surface.nodes(), ensure_ascii=False):
                raise RuntimeError("Requested Mobile state is not present")
        elif action == "observe":
            return {"packageName": "com.nexus.mobile", "nodes": self.surface.nodes()}
        elif action == "capture_screen":
            image = _png()
            return {"screenshot_base64": base64.b64encode(image).decode("ascii"), "content_type": "image/png", "width": 320, "height": 180}
        else:
            raise RuntimeError(f"Unsupported simulator action: {action}")
        return {"ok": True, "nodes": self.surface.nodes()}

    def run(self, *, max_commands: int = 0) -> None:
        handled = 0
        while max_commands <= 0 or handled < max_commands:
            self.heartbeat()
            value = self.request("POST", f"/api/v1/mobile-devices/{self.device_id}/device/commands/next/")
            command = value.get("command") if isinstance(value, dict) and "command" in value else value
            if not command or not command.get("id"):
                time.sleep(self.poll_seconds)
                continue
            command_id = str(command["id"])
            try:
                result = self.execute(command)
                payload = {"status": "succeeded", "result": result}
            except Exception as exc:
                payload = {"status": "failed", "error": str(exc)[:300], "result": {}}
            self.request("POST", f"/api/v1/mobile-commands/{command_id}/device/result/", payload)
            handled += 1


def main() -> None:
    parser = argparse.ArgumentParser(description="Run a deterministic caller Mobile protocol simulator")
    parser.add_argument("--base-url", default="http://127.0.0.1:8000")
    parser.add_argument("--device-id", required=True)
    parser.add_argument("--pairing-token", required=True)
    parser.add_argument("--max-commands", type=int, default=0)
    args = parser.parse_args()
    MobileProtocolSimulator(args.base_url, args.device_id, args.pairing_token).run(max_commands=args.max_commands)


if __name__ == "__main__":
    main()
