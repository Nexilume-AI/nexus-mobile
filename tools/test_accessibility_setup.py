"""Source/resource integration checks for the user-controlled Android setup flow."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import unittest


ROOT = Path(__file__).resolve().parents[1] / "app" / "src" / "main"


def test_restricted_settings_help_is_connected_to_app_specific_settings():
    source = (ROOT / "java/com/nexus/mobile/MainActivity.kt").read_text(encoding="utf-8")
    assert "private fun showRestrictedSettingsGuide()" in source
    assert "Settings.ACTION_APPLICATION_DETAILS_SETTINGS" in source
    assert 'Uri.fromParts("package", packageName, null)' in source
    assert "private fun openAccessibilitySettings()" in source
    # Every setup entry uses the walkthrough; only its explicit CTA opens settings.
    assert source.count("showAccessibilityWalkthrough()") >= 3
    assert source.count("startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))") == 1


def test_guide_has_complete_english_and_chinese_resources():
    source = (ROOT / "java/com/nexus/mobile/MainActivity.kt").read_text(encoding="utf-8")
    names = set(re.findall(r"R\.string\.(accessibility_\w+)", source))
    assert "accessibility_restricted_steps" in names
    assert "accessibility_return_hint" in names
    for locale in ("values", "values-zh-rCN"):
        strings = {row.attrib["name"]: row.text for row in ET.parse(ROOT / "res" / locale / "strings.xml").getroot()}
        assert names <= strings.keys()
        assert all(strings[name] and strings[name].strip() for name in names)
        steps = strings["accessibility_restricted_steps"]
        assert all(f"{step}." in steps for step in (1, 2, 3))


def test_settings_return_rechecks_permission_without_granting_it():
    source = (ROOT / "java/com/nexus/mobile/MainActivity.kt").read_text(encoding="utf-8")
    assert "showReturnHint = !accessibilityReady && returnedFromApplicationInfo" in source
    assert "val accessibilityReady = isAccessibilityEnabled()" in source
    assert "awaitingApplicationInfoReturn" in source
    assert "ActivityNotFoundException" in source
    assert "catch (_: SecurityException)" in source
    assert "Settings.Secure.put" not in source
    assert "WRITE_SECURE_SETTINGS" not in (ROOT / "AndroidManifest.xml").read_text(encoding="utf-8")


def load_tests(loader, tests, pattern):
    """Run these guards under unittest as well as pytest."""
    return unittest.TestSuite(unittest.FunctionTestCase(value) for name, value in globals().items()
                              if name.startswith("test_") and callable(value))
