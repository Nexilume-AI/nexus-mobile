from pathlib import Path
import inspect
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
        for expected in ("nexus-mobile://pair", "shell_safe_deep_link", '"confirm and connect"',
                         '"pair and connect"',
                         "self.ensure_accessibility()", '"dumpsys", "accessibility"'):
            self.assertIn(expected, source)
        # Read-only video UI inspection uses a debug receiver. Pairing itself
        # must still use the real Activity and never write pairing preferences.
        pairing = inspect.getsource(AndroidAVD.pair)
        self.assertNotIn("MobileE2ECommandReceiver", pairing)
        self.assertNotIn("send_command", pairing)
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
        confirmation = ET.fromstring('<node text="Pair and connect" />')
        title = ET.fromstring('<node text="Pair with this server?" />')
        connected = ET.fromstring('<node text="Connection" />')
        responses = iter([title, confirmation, RuntimeError("tap was lost"), confirmation, connected])

        def wait_for_node(predicate, **kwargs):
            response = next(responses)
            if isinstance(response, Exception):
                raise response
            self.assertTrue(predicate(response))
            return response

        with patch.object(avd, "adb_run"), \
                patch.object(avd, "_tap_node") as tap_node, \
                patch.object(avd, "ensure_accessibility") as ensure_accessibility, \
                patch("hosted_mobile_avd.time.sleep"), \
                patch.object(avd, "wait_for_node", side_effect=wait_for_node):
            avd.pair(base_url="http://10.0.2.2:8000", device_id="device", token="test-only")
            self.assertEqual([call.args for call in tap_node.call_args_list],
                             [(confirmation, "Pair and connect"), (confirmation, "Pair and connect")])
            ensure_accessibility.assert_called_once_with()

    def test_pairing_never_reports_success_after_five_lost_confirmation_taps(self):
        avd = self.make_avd()
        confirmation = ET.fromstring('<node text="Confirm and connect" />')
        responses = [ET.fromstring('<node text="Confirm Nexus pairing" />')]
        for _ in range(5):
            responses.extend([confirmation, RuntimeError("tap was lost")])
        with patch.object(avd, "adb_run"), \
                patch.object(avd, "_tap_node") as tap_node, \
                patch.object(avd, "ensure_accessibility") as ensure_accessibility, \
                patch("hosted_mobile_avd.time.sleep"), \
                patch.object(avd, "wait_for_node", side_effect=responses):
            with self.assertRaisesRegex(RuntimeError, "tap was lost"):
                avd.pair(base_url="http://10.0.2.2:8000", device_id="device", token="test-only")
            self.assertEqual(tap_node.call_count, 5)
            ensure_accessibility.assert_not_called()


if __name__ == "__main__":
    unittest.main()
