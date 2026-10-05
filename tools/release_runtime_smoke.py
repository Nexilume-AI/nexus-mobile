"""Exercise the minified release on an isolated AVD, without a Cloud or phone.

Build with -PnexusMobileReleaseSmoke=true assembleRelease assembleReleaseAndroidTest.
Both APKs use the local debug signing key; never publish these test artifacts.
API 34 validates our timeout callback and late-response handling; Android 15+
system-enforced timeout acceptance must additionally run on an API 35+ device.
"""
import argparse
import re
from pathlib import Path
import uuid

from hosted_mobile_avd import AndroidAVD


def require_unused_serial(device):
    state = device.adb_run("get-state", check=False)
    # Offline/unauthorized are occupied too. Fail closed on other adb errors.
    absent = "device '" + device.serial + "' not found"
    if state.returncode == 0 or state.stdout.strip() or absent not in state.stderr:
        raise RuntimeError("Requested emulator port is in use or cannot be verified")


def smoke(apk, test_apk, sdk, cases, port):
    device = AndroidAVD("nexus-release-smoke-" + uuid.uuid4().hex[:12],
                        "emulator-" + str(port), port, Path(sdk))
    try:
        # Unique name and port: do not disturb any existing phone or AVD.
        require_unused_serial(device)
        device.create()
        device.start()
        for artifact in (apk, test_apk):
            device.adb_run("install", "-r", str(Path(artifact).resolve()), timeout=180)
        for case in cases:
            result = device.adb_run("shell", "am", "instrument", "-w", "-r", "-e", "case", case,
                                    "com.nexus.mobile.test/com.nexus.mobile.ReleaseSmokeInstrumentation",
                                    timeout=60)
            if "PASS release " + case not in result.stdout or "INSTRUMENTATION_CODE: -1" not in result.stdout:
                # The platform output may include paths/stack details. Return
                # only the case label; inspect logcat locally when debugging.
                detail = re.search(r'FAIL release [a-z]+: [A-Za-z]+', result.stdout)
                if detail:
                    reason = detail.group(0)
                elif 'Process crashed' in result.stdout:
                    reason = 'target process crashed'
                else:
                    reason = 'instrumentation did not complete'
                # This fresh AVD has no real pairing/account; instrumentation
                # diagnostics describe the test runner, not user device data.
                print(result.stdout[:2000], flush=True)
                raise RuntimeError("RELEASE_SMOKE_FAILED: " + case + ' (' + reason + ')')
            print("PASS minified release: " + case, flush=True)
    finally:
        if device.created or device.process is not None:
            device.stop()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True)
    parser.add_argument("--test-apk", required=True)
    parser.add_argument("--sdk", required=True)
    parser.add_argument("--case", choices=("native", "timeout"), action="append")
    parser.add_argument("--port", type=int, default=5564)
    args = parser.parse_args()
    smoke(args.apk, args.test_apk, args.sdk, args.case or ["native", "timeout"], args.port)
