import subprocess
import unittest
from unittest.mock import Mock

from release_runtime_smoke import require_unused_serial, smoke


class ReleaseSmokeSafetyTests(unittest.TestCase):
    def test_only_explicit_missing_serial_is_allowed(self):
        device = Mock(serial="emulator-5564")
        device.adb_run.return_value = subprocess.CompletedProcess(
            [], 1, "", "adb: error: device 'emulator-5564' not found\n")
        require_unused_serial(device)

    def test_connected_offline_unauthorized_and_unknown_fail_closed(self):
        for code, stdout, stderr in (
            (0, "device\n", ""), (1, "", "error: device offline"),
            (1, "", "error: device unauthorized"),
            (1, "", "cannot connect to daemon"), (1, "", ""),
        ):
            with self.subTest(stderr=stderr, stdout=stdout):
                device = Mock(serial="emulator-5564")
                device.adb_run.return_value = subprocess.CompletedProcess([], code, stdout, stderr)
                with self.assertRaises(RuntimeError):
                    require_unused_serial(device)

    def test_occupied_serial_never_creates_or_stops_emulator(self):
        from unittest.mock import patch
        with patch("release_runtime_smoke.AndroidAVD") as avd:
            device = avd.return_value
            device.serial = "emulator-5564"
            device.created = False
            device.process = None
            device.adb_run.return_value = subprocess.CompletedProcess([], 1, "", "device offline")
            with self.assertRaises(RuntimeError):
                smoke("app.apk", "test.apk", "sdk", ["native"], 5564)
            device.create.assert_not_called()
            device.stop.assert_not_called()
