"""Disposable Android AVD helpers for the hosted-Agent live scenario.

The helper uses the real Nexus Mobile pairing deep link and Android UI. It
does not write pairing preferences or bypass the device heartbeat / command-poll
protocol. Video acceptance can opt into read-only debug UI inspection to avoid
UIAutomator suppressing Accessibility; device actions still use the real protocol.
"""

from __future__ import annotations

import os
import json
import re
import subprocess
import time
import urllib.parse
import uuid
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import Callable


PACKAGE = "com.nexus.mobile"
ACCESSIBILITY_SERVICE = f"{PACKAGE}/.NexusAccessibilityService"
ACCESSIBILITY_SERVICE_FULL = f"{PACKAGE}/{PACKAGE}.NexusAccessibilityService"
SYSTEM_IMAGE = "system-images;android-34;google_apis;x86_64"


def _run(
    command: list[str],
    *,
    timeout: int = 120,
    input_text: str | None = None,
    check: bool = True,
) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        command,
        input=input_text,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="strict",
        timeout=timeout,
        check=False,
    )
    if check and result.returncode != 0:
        rendered = " ".join(command)
        raise RuntimeError(
            f"ANDROID_COMMAND_FAILED ({rendered}):\n{result.stdout}\n{result.stderr}"
        )
    return result


def android_tool(sdk_root: Path, relative: str) -> str:
    candidate = sdk_root / relative
    if os.name == "nt" and not candidate.suffix:
        for suffix in (".exe", ".bat"):
            with_suffix = candidate.with_suffix(suffix)
            if with_suffix.is_file():
                return str(with_suffix)
    if candidate.is_file():
        return str(candidate)
    raise RuntimeError(f"ANDROID_TOOL_UNAVAILABLE: {candidate}")


@dataclass
class AndroidAVD:
    name: str
    serial: str
    port: int
    sdk_root: Path
    process: subprocess.Popen[str] | None = None
    created: bool = False

    @property
    def adb(self) -> str:
        return android_tool(self.sdk_root, "platform-tools/adb")

    @property
    def emulator(self) -> str:
        return android_tool(self.sdk_root, "emulator/emulator")

    @property
    def avdmanager(self) -> str:
        return android_tool(self.sdk_root, "cmdline-tools/latest/bin/avdmanager")

    def adb_run(self, *args: str, timeout: int = 120, check: bool = True) -> subprocess.CompletedProcess[str]:
        return _run([self.adb, "-s", self.serial, *args], timeout=timeout, check=check)

    def create(self) -> None:
        listed = _run([self.emulator, "-list-avds"], timeout=30).stdout.splitlines()
        if self.name in {item.strip() for item in listed}:
            _run([self.avdmanager, "delete", "avd", "--name", self.name], timeout=60)
        _run(
            [
                self.avdmanager,
                "create",
                "avd",
                "--force",
                "--name",
                self.name,
                "--package",
                SYSTEM_IMAGE,
                "--device",
                "pixel_6",
            ],
            timeout=120,
            input_text="no\n",
        )
        self.created = True

    def start(self) -> None:
        self.process = subprocess.Popen(
            [
                self.emulator,
                "-avd",
                self.name,
                "-port",
                str(self.port),
                "-no-window",
                "-no-audio",
                "-no-boot-anim",
                "-no-snapshot",
                "-wipe-data",
                "-memory",
                # API 34 may report `fork failed: Out of memory` while
                # PackageManager and SystemUI settle with 1280 MiB.  Keep
                # enough guest RAM for real command polling and screenshots;
                # the two disposable AVDs still remain below the live host's
                # bounded E2E footprint.
                "2048",
                "-cores",
                "2",
                "-skin",
                "720x1280",
                "-gpu",
                "swiftshader_indirect",
                "-camera-back",
                "none",
                "-camera-front",
                "none",
            ],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            text=True,
        )
        _run([self.adb, "-s", self.serial, "wait-for-device"], timeout=180)
        deadline = time.monotonic() + 240
        while time.monotonic() < deadline:
            booted = self.adb_run("shell", "getprop", "sys.boot_completed", check=False).stdout.strip()
            if booted == "1":
                self.adb_run("shell", "input", "keyevent", "82", check=False)
                # boot_completed precedes the first stable System UI frame on
                # cold API 34 AVDs. Give PackageManager and SystemUI a bounded
                # settling window before installing and opening the pairing UI.
                time.sleep(12)
                return
            if self.process.poll() is not None:
                raise RuntimeError(f"ANDROID_EMULATOR_EXITED: {self.name}")
            time.sleep(1)
        raise RuntimeError(f"ANDROID_BOOT_TIMEOUT: {self.name}")

    def install(self, apk: Path) -> None:
        if not apk.is_file():
            raise RuntimeError(f"ANDROID_APK_UNAVAILABLE: {apk}")
        self.adb_run("install", "-r", "-g", str(apk), timeout=180)
        self.ensure_accessibility()

    def ensure_accessibility(self, *, timeout: float = 30) -> None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            self.adb_run(
                "shell",
                "settings",
                "put",
                "secure",
                "enabled_accessibility_services",
                ACCESSIBILITY_SERVICE_FULL,
            )
            self.adb_run(
                "shell", "settings", "put", "secure", "accessibility_enabled", "1"
            )
            self.adb_run(
                "shell",
                "am",
                "start",
                "-W",
                "-n",
                f"{PACKAGE}/.MainActivity",
                check=False,
            )
            time.sleep(1)
            state = self.adb_run(
                "shell", "dumpsys", "accessibility", timeout=30, check=False
            ).stdout
            if ACCESSIBILITY_SERVICE_FULL in state and "Nexus Mobile Control" in state:
                return
            # A force-stop during a transient pairing retry can leave the
            # setting enabled while the framework has not rebound the service.
            self.adb_run(
                "shell", "settings", "put", "secure", "accessibility_enabled", "0"
            )
            time.sleep(0.25)
        raise RuntimeError(
            f"ANDROID_ACCESSIBILITY_UNAVAILABLE: {self.name} did not bind Nexus Mobile Control"
        )

    def pair(self, *, base_url: str, device_id: str, token: str, expires_at: str = "") -> None:
        query = urllib.parse.urlencode(
            {
                "base_url": base_url.rstrip("/"),
                "device_id": device_id,
                "token": token,
                **({"expires_at": expires_at} if expires_at else {}),
            }
        )
        deep_link = f"nexus-mobile://pair?{query}"
        # adb forwards `shell` arguments through Android's command shell.
        # Quote the complete URI so query-string ampersands are not interpreted
        # as background operators on a real emulator or physical device.
        shell_safe_deep_link = "'" + deep_link.replace("'", "'\\''") + "'"
        for attempt in range(3):
            self.adb_run(
                "shell",
                "am",
                "start",
                "-W",
                "-a",
                "android.intent.action.VIEW",
                "-d",
                shell_safe_deep_link,
                "-n",
                f"{PACKAGE}/.MainActivity",
            )
            try:
                self.wait_for_node(lambda item: any(value.casefold() in
                    ((item.get("text") or "") + " " + (item.get("content-desc") or "")).casefold()
                    for value in ("Pair with this server?", "Confirm Nexus pairing")), timeout=30)
                break
            except RuntimeError:
                if attempt == 2:
                    raise
                # A freshly booted real AVD can leave the Activity behind a
                # transient System UI surface. Restart only this disposable
                # app and replay the same one-time pairing deep link.
                self.adb_run("shell", "am", "force-stop", PACKAGE)
                self.adb_run("shell", "input", "keyevent", "3")
                time.sleep(2)
        for attempt in range(5):
            try:
                confirmation = self.wait_for_node(lambda item: (item.get("text") or "").casefold()
                    in {"pair and connect", "confirm and connect"}, timeout=3)
                self._tap_node(confirmation, "Pair and connect")
            except RuntimeError:
                if attempt == 4:
                    raise
                # Compact emulator viewports keep the explicit confirmation
                # below the fold. Read the real Android viewport and scroll it
                # proportionally instead of assuming one fixed resolution.
                width, height = self.screen_size()
                self.adb_run(
                    "shell",
                    "input",
                    "swipe",
                    str(width // 2),
                    str(int(height * 0.82)),
                    str(width // 2),
                    str(int(height * 0.22)),
                    "550",
                )
                for _ in range(4):
                    self.adb_run("shell", "input", "keyevent", "20")
                time.sleep(0.75)
                continue
            try:
                # Coordinate input on a freshly booted emulator can be
                # acknowledged by adb without reaching the Activity. Verify
                # the UI transition before accepting the tap, then retry the
                # same visible confirmation instead of waiting until the
                # whole pairing attempt times out.
                self.wait_for_node(
                    lambda item: any(
                        value in ((item.get("text") or "") + " " + (item.get("content-desc") or ""))
                        for value in ("Pairing confirmed", "Paired. Finish setup below.", "Connection")
                    ),
                    timeout=5,
                )
                self.ensure_accessibility()
                return
            except RuntimeError:
                if attempt == 4:
                    raise
                time.sleep(0.75)

    def screen_size(self) -> tuple[int, int]:
        output = self.adb_run("shell", "wm", "size").stdout
        matches = re.findall(r"(?:Physical|Override) size:\s*(\d+)x(\d+)", output)
        if not matches:
            raise RuntimeError(f"ANDROID_SCREEN_SIZE_UNAVAILABLE: {output.strip()}")
        width, height = matches[-1]
        return int(width), int(height)

    def wait_for_text_recovering_system_ui(self, value: str, *, timeout: float = 30) -> None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            root = self.dump_ui()
            nodes = list(root.iter("node"))
            if any(
                value.casefold() in (item.get("text") or "").casefold()
                or value.casefold() in (item.get("content-desc") or "").casefold()
                for item in nodes
            ):
                return
            system_ui_anr = any(
                "system ui isn't responding" in (item.get("text") or "").casefold()
                for item in nodes
            )
            if system_ui_anr:
                wait_node = next(
                    (item for item in nodes if (item.get("text") or "").casefold() == "wait"),
                    None,
                )
                if wait_node is not None:
                    self._tap_node(wait_node, "Wait")
                    time.sleep(3)
                    continue
            time.sleep(0.5)
        raise RuntimeError(f"ANDROID_UI_TIMEOUT: {value}")

    def dump_ui(self) -> ET.Element:
        if getattr(self, "preserve_accessibility", False):
            nonce = uuid.uuid4().hex
            self.adb_run("shell", "am", "broadcast", "-n", f"{PACKAGE}/.MobileE2ECommandReceiver",
                "--es", "command", "inspect_ui", "--es", "command_id", nonce)
            raw = self.adb_run("shell", "run-as", PACKAGE, "cat", "files/mobile-e2e-result.json").stdout
            self.adb_run("shell", "run-as", PACKAGE, "rm", "files/mobile-e2e-result.json")
            payload = json.loads(raw)
            if payload.get("id") != nonce:
                raise RuntimeError("ANDROID_UI_INSPECTION_CONFLICT")
            return ET.fromstring(payload["result"]["xml"])
        self.adb_run("shell", "uiautomator", "dump", "/sdcard/nexus-hosted-mobile.xml")
        raw = self.adb_run("exec-out", "cat", "/sdcard/nexus-hosted-mobile.xml").stdout
        return ET.fromstring(raw)

    def tap_text(self, value: str, *, timeout: float = 20) -> None:
        node = self.wait_for_node(
            lambda item: value.casefold() in (item.get("text") or "").casefold()
            or value.casefold() in (item.get("content-desc") or "").casefold(),
            timeout=timeout,
        )
        self._tap_node(node, value)

    def _tap_node(self, node: ET.Element, value: str) -> None:
        match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds") or "")
        if match is None:
            raise RuntimeError(f"ANDROID_NODE_BOUNDS_INVALID: {value}")
        left, top, right, bottom = (int(item) for item in match.groups())
        x = (left + right) // 2
        y = (top + bottom) // 2
        width, height = self.screen_size()
        # UIAutomator includes children that are still outside a ScrollView's
        # visible viewport. Treat those as unavailable so the caller can
        # scroll before tapping instead of sending a no-op coordinate beyond
        # the real display.
        if right <= left or bottom <= top or not (0 <= x < width and 0 <= y < height):
            raise RuntimeError(
                f"ANDROID_NODE_OFFSCREEN: {value} at ({x},{y}) outside {width}x{height}"
            )
        self.adb_run("shell", "input", "tap", str(x), str(y))

    def wait_for_node(self, predicate: Callable[[ET.Element], bool], *, timeout: float = 20) -> ET.Element:
        deadline = time.monotonic() + timeout
        last_values: list[str] = []
        while time.monotonic() < deadline:
            root = self.dump_ui()
            nodes = list(root.iter("node"))
            for node in nodes:
                if predicate(node):
                    return node
            last_values = [
                value
                for node in nodes
                for value in (node.get("text"), node.get("content-desc"))
                if value
            ]
            time.sleep(0.25)
        raise RuntimeError(f"ANDROID_UI_TIMEOUT: {last_values[-20:]}")

    def wait_for_text(self, value: str, *, timeout: float = 30) -> None:
        self.wait_for_node(
            lambda item: value.casefold() in (item.get("text") or "").casefold()
            or value.casefold() in (item.get("content-desc") or "").casefold(),
            timeout=timeout,
        )

    def stop(self) -> None:
        self.adb_run("emu", "kill", timeout=20, check=False)
        if self.process is not None:
            try:
                self.process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                self.process.terminate()
                try:
                    self.process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    self.process.kill()
        if self.created:
            _run([self.avdmanager, "delete", "avd", "--name", self.name], timeout=60, check=False)


def disposable_avds(sdk_root: Path) -> tuple[AndroidAVD, AndroidAVD]:
    return (
        AndroidAVD(name="NexusCallerA_API34", serial="emulator-5560", port=5560, sdk_root=sdk_root),
        AndroidAVD(name="NexusCallerB_API34", serial="emulator-5562", port=5562, sdk_root=sdk_root),
    )
