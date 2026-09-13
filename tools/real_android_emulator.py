"""Run the Nexus Mobile command executor against a booted Android AVD.

This is an actual device test: commands cross ADB into the debug APK and are
executed by NexusAccessibilityService through MobileCommandExecutor.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")


ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.nexus.mobile"
RECEIVER = f"{PACKAGE}/.MobileE2ECommandReceiver"
ACTION = "com.nexus.mobile.DEBUG_E2E_COMMAND"
RESULT_FILE = "files/mobile-e2e-result.json"


def find_adb(explicit: str) -> str:
    candidates = (
        explicit,
        os.environ.get("ADB", ""),
        shutil.which("adb") or "",
        r"D:\Android\Sdk\platform-tools\adb.exe",
    )
    for candidate in candidates:
        if candidate and Path(candidate).is_file():
            return str(Path(candidate))
    raise RuntimeError("ADB_UNAVAILABLE")


def run(adb: str, serial: str, *args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [adb, "-s", serial, *args],
        check=check,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="strict",
    )


def emulator_serial(adb: str, explicit: str) -> str:
    if explicit:
        candidates = [explicit]
    else:
        output = subprocess.run(
            [adb, "devices"], check=True, capture_output=True, text=True, encoding="utf-8"
        ).stdout
        candidates = [line.split()[0] for line in output.splitlines()[1:] if "\tdevice" in line]
    emulators = [serial for serial in candidates if run(adb, serial, "shell", "getprop", "ro.kernel.qemu").stdout.strip() == "1"]
    if not emulators:
        raise RuntimeError("ANDROID_EMULATOR_REQUIRED: no booted AVD is connected")
    if len(emulators) != 1:
        raise RuntimeError(f"ANDROID_EMULATOR_AMBIGUOUS: expected one AVD, found {len(emulators)}")
    return emulators[0]


def command(
    adb: str,
    serial: str,
    command_id: str,
    action: str,
    *,
    text: str | None = None,
    **arguments: Any,
) -> dict[str, Any]:
    call = [
        "shell", "am", "broadcast", "--receiver-foreground", "-a", ACTION,
        "-n", RECEIVER, "--es", "command_id", command_id, "--es", "command", action,
    ]
    if text is not None:
        call += ["--es", "text_base64", base64.b64encode(text.encode("utf-8")).decode("ascii")]
    for key, value in arguments.items():
        call += ["--es", f"arg_{key}", str(value)]
    run(adb, serial, *call)
    deadline = time.monotonic() + 35
    result: dict[str, Any] = {}
    while time.monotonic() < deadline:
        read = run(
            adb,
            serial,
            "shell",
            "run-as",
            PACKAGE,
            "cat",
            RESULT_FILE,
            check=False,
        )
        if read.returncode == 0 and read.stdout.strip():
            result = json.loads(read.stdout)
            if result.get("id") == command_id:
                break
        time.sleep(0.1)
    if result.get("id") != command_id:
        raise RuntimeError(f"ANDROID_RESULT_TIMEOUT: expected {command_id}, received {result.get('id')}")
    if not result.get("succeeded"):
        raise RuntimeError(
            f"ANDROID_COMMAND_FAILED[{command_id}/{action}]: "
            f"{result.get('error_code')} {result.get('error_message')}"
        )
    return result.get("result") or {}


def observe_until(adb: str, serial: str, text: str, *, timeout: float = 10.0) -> dict[str, Any]:
    deadline = time.monotonic() + timeout
    last: dict[str, Any] = {}
    attempt = 0
    while time.monotonic() < deadline:
        attempt += 1
        last = command(adb, serial, f"observe-{attempt}-{time.time_ns()}", "observe")
        if any(
            text.casefold() in str(node.get("text") or "").casefold()
            or text.casefold() in str(node.get("description") or "").casefold()
            for node in (last.get("nodes") or [])
        ):
            return last
        time.sleep(0.2)
    raise RuntimeError(f"ANDROID_STATE_TIMEOUT: {text}")


def assert_exact_ui_text(adb: str, serial: str, expected: str) -> None:
    run(adb, serial, "shell", "uiautomator", "dump", "/sdcard/nexus-mobile-e2e.xml")
    document = run(adb, serial, "exec-out", "cat", "/sdcard/nexus-mobile-e2e.xml").stdout
    root = ET.fromstring(document)
    values = [
        value
        for node in root.iter("node")
        for value in (node.get("text"), node.get("content-desc"))
        if value
    ]
    if expected not in values:
        raise RuntimeError(f"ANDROID_UI_TEXT_MISMATCH: {expected}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", default="")
    parser.add_argument("--serial", default="")
    parser.add_argument("--apk", default=str(ROOT / "app/build/outputs/apk/debug/app-debug.apk"))
    parser.add_argument("--install", action="store_true")
    args = parser.parse_args()
    try:
        adb = find_adb(args.adb)
        serial = emulator_serial(adb, args.serial)
        apk = Path(args.apk)
        if not apk.is_file():
            raise RuntimeError(f"DEBUG_APK_REQUIRED: {apk}")
        if args.install:
            run(adb, serial, "install", "-r", str(apk))
        if "package:" not in run(adb, serial, "shell", "pm", "path", PACKAGE).stdout:
            raise RuntimeError("NEXUS_MOBILE_DEBUG_REQUIRED: install with --install")

        run(adb, serial, "shell", "am", "force-stop", PACKAGE)
        run(adb, serial, "shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity")
        run(
            adb,
            serial,
            "shell",
            "settings",
            "put",
            "secure",
            "enabled_accessibility_services",
            f"{PACKAGE}/.NexusAccessibilityService",
        )
        run(adb, serial, "shell", "settings", "put", "secure", "accessibility_enabled", "1")
        time.sleep(2)

        observe_until(adb, serial, "Open Mobile E2E Surface")
        command(adb, serial, "wait-main", "wait_for_state", text="Open Mobile E2E Surface", timeout_ms=1_000)
        command(adb, serial, "open-surface", "tap_text", text="Open Mobile E2E Surface")
        observed = observe_until(adb, serial, "Enter unique Run marker")
        command(adb, serial, "wait-surface", "wait_for_state", text="Enter unique Run marker", timeout_ms=1_000)
        nodes = observed.get("nodes") or []
        if not any(node.get("description") == "E2E message" for node in nodes):
            raise RuntimeError("ANDROID_OBSERVATION_INVALID: E2E message node missing")
        if not any(node.get("description") == "Apply" for node in nodes):
            raise RuntimeError("ANDROID_OBSERVATION_INVALID: Apply node missing")

        marker = f"Nexus中文🧪-{int(time.time() * 1000)}"
        command(adb, serial, "focus", "tap_text", text="Enter unique Run marker")
        command(adb, serial, "type", "type_text", text=marker)
        observe_until(adb, serial, "Nexus中文🧪-")
        assert_exact_ui_text(adb, serial, marker)
        command(adb, serial, "apply", "tap_text", text="APPLY")
        observe_until(adb, serial, "Saved: Nexus中文🧪-")
        assert_exact_ui_text(adb, serial, f"Saved: {marker}")
        command(
            adb,
            serial,
            "wait-saved",
            "wait_for_state",
            text="Saved: Nexus中文🧪-",
            timeout_ms=1_000,
        )

        capture = command(adb, serial, "capture", "capture_screen")
        if int(capture.get("width") or 0) <= 0 or int(capture.get("height") or 0) <= 0:
            raise RuntimeError("ANDROID_SCREENSHOT_INVALID: empty dimensions")
        if int(capture.get("screenshot_bytes") or 0) <= 100:
            raise RuntimeError("ANDROID_SCREENSHOT_INVALID: empty image")

        command(
            adb,
            serial,
            "swipe",
            "swipe",
            start_x=0.5,
            start_y=0.8,
            end_x=0.5,
            end_y=0.25,
            duration_ms=350,
        )
        command(adb, serial, "back", "press_back")
        print(
            json.dumps(
                {
                    "status": "passed",
                    "serial": serial,
                    "android": run(adb, serial, "shell", "getprop", "ro.build.version.release").stdout.strip(),
                    "marker": marker,
                    "nodes": len(nodes),
                    "screenshot": {
                        "width": capture["width"],
                        "height": capture["height"],
                        "bytes": capture["screenshot_bytes"],
                    },
                },
                ensure_ascii=False,
            )
        )
        return 0
    except (RuntimeError, subprocess.CalledProcessError, UnicodeError, json.JSONDecodeError) as error:
        print(str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
