from pathlib import Path
import xml.etree.ElementTree as ET

import pytest

from hosted_mobile_avd import AndroidAVD, disposable_avds


def test_disposable_avds_use_distinct_android_emulator_ports():
    caller_a, caller_b = disposable_avds(Path("D:/Android/Sdk"))
    assert caller_a.serial == "emulator-5560"
    assert caller_b.serial == "emulator-5562"
    assert caller_a.port != caller_b.port


def test_pairing_helper_uses_real_activity_and_not_debug_command_receiver():
    source = Path(__file__).with_name("hosted_mobile_avd.py").read_text(encoding="utf-8")
    assert "nexus-mobile://pair" in source
    assert "shell_safe_deep_link" in source
    assert "Confirm and connect" in source
    assert "self.ensure_accessibility()" in source
    assert '"dumpsys", "accessibility"' in source
    assert "MobileE2ECommandReceiver" not in source
    assert "MobileConfigStore" not in source


def test_tap_node_rejects_offscreen_scroll_children(monkeypatch):
    avd = AndroidAVD(
        name="nexus-mobile-test",
        serial="emulator-5560",
        port=5560,
        sdk_root=Path("D:/Android/Sdk"),
    )
    calls = []
    monkeypatch.setattr(avd, "screen_size", lambda: (720, 1280))
    monkeypatch.setattr(avd, "adb_run", lambda *args, **kwargs: calls.append(args))
    node = ET.fromstring('<node text="Confirm and connect" bounds="[32,1302][688,1422]" />')

    with pytest.raises(RuntimeError, match="ANDROID_NODE_OFFSCREEN"):
        avd._tap_node(node, "Confirm and connect")

    assert calls == []


def test_pairing_retries_an_acknowledged_tap_until_the_activity_transitions(monkeypatch):
    avd = AndroidAVD(
        name="nexus-mobile-test",
        serial="emulator-5560",
        port=5560,
        sdk_root=Path("D:/Android/Sdk"),
    )
    taps = []
    transitions = []
    monkeypatch.setattr(avd, "adb_run", lambda *args, **kwargs: None)
    monkeypatch.setattr(avd, "wait_for_text_recovering_system_ui", lambda *args, **kwargs: None)
    monkeypatch.setattr(avd, "tap_text", lambda value, **kwargs: taps.append(value))
    monkeypatch.setattr(avd, "ensure_accessibility", lambda: transitions.append("ready"))
    monkeypatch.setattr("hosted_mobile_avd.time.sleep", lambda _seconds: None)

    attempts = iter([RuntimeError("tap was lost"), ET.fromstring('<node text="Connection" />')])

    def wait_for_node(_predicate, **_kwargs):
        value = next(attempts)
        if isinstance(value, Exception):
            raise value
        return value

    monkeypatch.setattr(avd, "wait_for_node", wait_for_node)
    avd.pair(base_url="http://10.0.2.2:8000", device_id="device", token="secret")

    assert taps == ["Confirm and connect", "Confirm and connect"]
    assert transitions == ["ready"]
