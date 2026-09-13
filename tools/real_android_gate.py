"""Fail-fast gate for the physical Android portion of Mobile acceptance.

This intentionally exits non-zero when a real ADB device is absent. It is not
a pytest skip and must run before the live OpenWrt/Private Display scenario.
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path


def adb_path(explicit: str) -> str:
    candidates = [
        explicit,
        os.environ.get("ADB", ""),
        shutil.which("adb") or "",
        r"D:\Android\Sdk\platform-tools\adb.exe",
    ]
    for value in candidates:
        if value and Path(value).is_file():
            return str(Path(value))
    raise RuntimeError("ADB_UNAVAILABLE: install Android platform-tools or pass --adb")


def connected_devices(adb: str) -> list[str]:
    output = subprocess.run([adb, "devices", "-l"], check=True, capture_output=True, text=True).stdout
    return [line.split()[0] for line in output.splitlines()[1:] if "\tdevice" in line]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", default="")
    parser.add_argument("--apk", default=str(Path(__file__).parents[1] / "app/build/outputs/apk/debug/app-debug.apk"))
    parser.add_argument("--install", action="store_true")
    args = parser.parse_args()
    try:
        adb = adb_path(args.adb)
        devices = connected_devices(adb)
        if not devices:
            raise RuntimeError("ANDROID_DEVICE_REQUIRED: no authorized physical ADB device is connected")
        if len(devices) != 1:
            raise RuntimeError(f"ANDROID_DEVICE_AMBIGUOUS: expected one device, found {len(devices)}")
        apk = Path(args.apk)
        if not apk.is_file():
            raise RuntimeError(f"DEBUG_APK_REQUIRED: {apk}")
        if args.install:
            subprocess.run([adb, "-s", devices[0], "install", "-r", str(apk)], check=True)
        packages = subprocess.run(
            [adb, "-s", devices[0], "shell", "pm", "path", "com.nexus.mobile"],
            check=True,
            capture_output=True,
            text=True,
        ).stdout
        if "package:" not in packages:
            raise RuntimeError("NEXUS_MOBILE_DEBUG_REQUIRED: install the debug APK with --install")
        print(f"Physical Android gate passed for {devices[0]}")
        return 0
    except (RuntimeError, subprocess.CalledProcessError) as exc:
        print(str(exc), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
