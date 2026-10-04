"""Integration guards for the bundled, camera-only pairing scanner.

These check Android wiring; real image decoding is covered by PairingQrDecoderTest.
Physical camera/permission behavior still requires device acceptance.
"""
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import unittest


ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main"
ANDROID = "{http://schemas.android.com/apk/res/android}"
TOOLS = "{http://schemas.android.com/tools}"


def test_scanner_is_bundled_without_google_services_or_external_capture_activity():
    dependencies = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    assert 'com.journeyapps:zxing-android-embedded:4.3.0' in dependencies
    assert 'com.google.zxing:core:3.5.4' in dependencies
    sources = "\n".join(path.read_text(encoding="utf-8") for path in (MAIN / "java").rglob("*.kt"))
    for removed in ("play-services-code-scanner", "com.google.mlkit", "GmsBarcodeScanning"):
        assert removed not in dependencies + sources
    manifest = ET.parse(MAIN / "AndroidManifest.xml").getroot()
    assert any(row.get(ANDROID + "name") == "android.permission.CAMERA" for row in manifest.findall("uses-permission"))
    camera = next(row for row in manifest.findall("uses-feature") if row.get(ANDROID + "name") == "android.hardware.camera.any")
    assert camera.get(ANDROID + "required") == "false"
    activities = manifest.find("application").findall("activity")
    scanner = next(row for row in activities if row.get(ANDROID + "name") == ".PairingQrScanActivity")
    assert scanner.get(ANDROID + "exported") == "false"
    assert not scanner.findall("intent-filter")
    capture = next(row for row in activities if row.get(ANDROID + "name") == "com.journeyapps.barcodescanner.CaptureActivity")
    assert capture.get(TOOLS + "node") == "remove"


def test_scan_returns_to_existing_confirmation_and_never_adds_manual_link_entry():
    source = (MAIN / "java/com/nexus/mobile/MainActivity.kt").read_text(encoding="utf-8")
    assert "Intent(this, PairingQrScanActivity::class.java)" in source
    assert "override fun onActivityResult(" in source
    assert "handlePairingValue(value)" in source
    scanner = (MAIN / "java/com/nexus/mobile/PairingQrScanActivity.kt").read_text(encoding="utf-8")
    assert "PairingQrDecoder.factory()" in scanner
    assert "PairingParser.parse(" in scanner
    assert "decodeContinuous" in scanner
    assert "cameraView.pauseAndWait()" in scanner
    assert "FLAG_SECURE" in scanner
    assert "requestPermissions(arrayOf(Manifest.permission.CAMERA)" in scanner
    for forbidden in ("EditText", "ClipboardManager", "MobileConfigStore.save", "getBitmap(", "Log."):
        assert forbidden not in scanner
    assert "EditText" not in source


def test_scanner_messages_are_localized_and_no_google_fallback_is_offered():
    files = [MAIN / "java/com/nexus/mobile" / name for name in ("MainActivity.kt", "PairingQrScanActivity.kt")]
    sources = "\n".join(path.read_text(encoding="utf-8") for path in files)
    names = set(re.findall(r"R\.string\.(qr_\w+)", sources))
    assert {"qr_camera_denied", "qr_camera_unavailable", "qr_invalid", "qr_expired", "qr_retry"} <= names
    for locale in ("values", "values-zh-rCN"):
        strings = {row.attrib["name"]: row.text for row in ET.parse(MAIN / "res" / locale / "strings.xml").getroot()}
        assert names <= strings.keys()
        assert all(strings[name] and strings[name].strip() for name in names)
    assert "Open the pairing QR with this app instead" not in sources


def test_upstream_license_is_packaged_without_nexus_additional_conditions():
    notice = (MAIN / "assets/third_party_scanner.txt").read_text(encoding="utf-8")
    assert "ZXing Android Embedded 4.3.0" in notice
    assert "ZXing Core 3.5.4" in notice
    assert "END OF TERMS AND CONDITIONS" in notice
    assert "APPENDIX: How to apply the Apache License to your work." in notice
    assert "LicenseRef-Nexus-Additional-Terms" not in notice


def load_tests(loader, tests, pattern):
    """Run these guards under unittest as well as pytest."""
    return unittest.TestSuite(unittest.FunctionTestCase(value) for name, value in globals().items()
                              if name.startswith("test_") and callable(value))
