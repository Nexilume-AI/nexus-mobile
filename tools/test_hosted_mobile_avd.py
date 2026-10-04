from pathlib import Path
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

from hosted_mobile_avd import AndroidAVD, disposable_avds


class HostedMobileAVDTests(unittest.TestCase):
    def make_avd(self):
        return AndroidAVD(
            name="nexus-mobile-test", serial="emulator-5560", port=5560,
            sdk_root=Path("D:/Android/Sdk"),
        )

    def test_disposable_avds_use_distinct_android_emulator_ports(self):
        caller_a, caller_b = disposable_avds(Path("D:/Android/Sdk"))
        self.assertEqual(caller_a.serial, "emulator-5560")
        self.assertEqual(caller_b.serial, "emulator-5562")
        self.assertNotEqual(caller_a.port, caller_b.port)

    def test_pairing_helper_uses_real_activity_and_not_debug_command_receiver(self):
        source = Path(__file__).with_name("hosted_mobile_avd.py").read_text(encoding="utf-8")
        for expected in ("nexus-mobile://pair", "shell_safe_deep_link", "Confirm and connect",
                         "self.ensure_accessibility()", '"dumpsys", "accessibility"'):
            self.assertIn(expected, source)
        self.assertNotIn("MobileE2ECommandReceiver", source)
        self.assertNotIn("MobileConfigStore", source)

    def test_tap_node_rejects_offscreen_scroll_children(self):
        avd = self.make_avd()
        node = ET.fromstring('<node text="Confirm and connect" bounds="[32,1302][688,1422]" />')
        with patch.object(avd, "screen_size", return_value=(720, 1280)), \
                patch.object(avd, "adb_run") as adb_run:
            with self.assertRaisesRegex(RuntimeError, "ANDROID_NODE_OFFSCREEN"):
                avd._tap_node(node, "Confirm and connect")
            adb_run.assert_not_called()

    def test_pairing_retries_an_acknowledged_tap_until_the_activity_transitions(self):
        avd = self.make_avd()
        with patch.object(avd, "adb_run"), \
                patch.object(avd, "wait_for_text_recovering_system_ui"), \
                patch.object(avd, "tap_text") as tap_text, \
                patch.object(avd, "ensure_accessibility") as ensure_accessibility, \
                patch("hosted_mobile_avd.time.sleep"), \
                patch.object(avd, "wait_for_node", side_effect=[
                    RuntimeError("tap was lost"), ET.fromstring('<node text="Connection" />')]):
            avd.pair(base_url="http://10.0.2.2:8000", device_id="device", token="test-only")
            self.assertEqual([call.args[0] for call in tap_text.call_args_list],
                             ["Confirm and connect", "Confirm and connect"])
            ensure_accessibility.assert_called_once_with()


if __name__ == "__main__":
    unittest.main()
