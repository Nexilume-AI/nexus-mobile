"""Integration boundaries for the permission coach; runtime transitions have Kotlin tests."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import unittest

ROOT = Path(__file__).resolve().parents[1] / "app/src/main"


def test_permissions_follow_real_state_and_have_an_explicit_exit():
    source = (ROOT / "java/com/nexus/mobile/MainActivity.kt").read_text(encoding="utf-8")
    assert "PermissionGuideState.fromPermissions(" in source
    assert "NotificationManagerCompat.from(this).areNotificationsEnabled()" in source
    assert "getNotificationChannel(NexusMobileService.CHANNEL_ID)" in source
    assert "channel.importance != NotificationManager.IMPORTANCE_NONE" in source
    assert "override fun onRequestPermissionsResult(" in source
    assert "permission_guide_later" in source
    assert "permission_guide_skip_notifications" in source
    assert "showAccessibilityWalkthrough()" in source
    confirm = source.split("private fun confirmPairing(")[1].split("private fun confirmRemovePairing")[0]
    assert "requestNotificationPermission()" not in confirm
    assert "Settings.Secure.put" not in source
    assert "SYSTEM_ALERT_WINDOW" not in (ROOT / "AndroidManifest.xml").read_text(encoding="utf-8")


def test_camera_prompt_is_user_initiated_and_scanner_only():
    source = (ROOT / "java/com/nexus/mobile/PairingQrScanActivity.kt").read_text(encoding="utf-8")
    resume = source.split("override fun onResume()")[1].split("override fun onPause()")[0]
    assert "showCameraPermissionGuide()" in resume
    assert "requestCameraPermission()" not in resume
    assert "!permissionRequested || shouldShowRequestPermissionRationale" in source
    assert "requestPermissions(arrayOf(Manifest.permission.CAMERA)" in source
    main = (ROOT / "java/com/nexus/mobile/MainActivity.kt").read_text(encoding="utf-8")
    assert "Manifest.permission.CAMERA" not in main


def test_permission_coach_is_localized():
    sources = "\n".join((ROOT / f"java/com/nexus/mobile/{file}").read_text(encoding="utf-8")
                        for file in ("MainActivity.kt", "PairingQrScanActivity.kt"))
    names = set(re.findall(r"R\.string\.((?:permission_guide|qr_camera_guide)_\w+)", sources))
    assert len(names) >= 12
    for locale in ("values", "values-zh-rCN"):
        entries = {row.attrib["name"]: row.text for row in ET.parse(ROOT / "res" / locale / "strings.xml").getroot()}
        assert names <= entries.keys()
        assert all(entries[name] and entries[name].strip() for name in names)


def test_home_and_dialogs_stay_compact_without_removing_recovery_controls():
    source = (ROOT / "java/com/nexus/mobile/MainActivity.kt").read_text(encoding="utf-8")
    assert "PopupMenu(this, anchor)" in source
    assert "MobileHomeAction.forState(" in source
    assert "content.addView(permissionCard(accessibilityReady))" in source
    assert "permissionDetailsExpanded" not in source
    assert "home_hide_permissions" not in source
    assert "content.addView(controlCard(" not in source
    assert "content.addView(privacyCard(" not in source
    assert "home_remove_pairing" in source and "confirmRemovePairing(config)" in source
    assert "home_pause" in source and "NexusMobileService.pause" in source
    assert "private fun AlertDialog.Builder.showAccessible()" in source
    assert "minHeight = dp(48)" in source
    # Existing debug acceptance can still open the surface; Release has no such entry.
    assert 'ghostButton("Open Mobile E2E Surface")' in source
    assert "BuildConfig.DEBUG && pendingPairing == null" in source
    names = set(re.findall(r"R\.string\.(home_\w+)", source))
    for locale in ("values", "values-zh-rCN"):
        entries = {row.attrib["name"]: row.text for row in ET.parse(ROOT / "res" / locale / "strings.xml").getroot()}
        assert names <= entries.keys()


def load_tests(loader, tests, pattern):
    """Include source integration guards in the dependency-free CI runner."""
    return unittest.TestSuite(unittest.FunctionTestCase(value) for name, value in globals().items()
                              if name.startswith("test_") and callable(value))
