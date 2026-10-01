import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch
from verify_release import verify

SAFE = """android:allowBackup(0x01010280)=(type 0x12)0x0
android:usesCleartextTraffic(0x010104ec)=(type 0x12)0x0
android:icon(0x01010002)=@0x7f010001
android:roundIcon(0x0101052c)=@0x7f010001"""

ICONS = ('mipmap/ic_launcher', 'drawable/ic_nexus_foreground', 'drawable/ic_nexus_monochrome', 'drawable/ic_nexus_notification')
RESOURCES = '\n'.join(f'resource 0x7f010001 com.nexus.mobile:{name}: t=0x03\n  (string8) "res/optimized{i}.xml"' for i, name in enumerate(ICONS))

class ReleaseVerifierTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.apk = Path(self.temp.name) / 'release.apk'
        with zipfile.ZipFile(self.apk, 'w') as archive:
            archive.writestr('AndroidManifest.xml', 'placeholder')
            for i, _ in enumerate(ICONS):
                archive.writestr(f'res/optimized{i}.xml', 'placeholder')

    def inspect(self, manifest, **kwargs):
        with patch('verify_release.subprocess.run', side_effect=[
            subprocess.CompletedProcess([], 0, manifest, ''),
            subprocess.CompletedProcess([], 0, RESOURCES, ''),
        ]):
            return verify(self.apk, 'aapt', **kwargs)

    def test_unsigned_release_has_no_signature_claim(self):
        result = self.inspect(SAFE)
        self.assertFalse(result['signature_verified'])
        self.assertTrue(self.apk.with_suffix('.apk.sha256').exists())

    def test_reject_unsafe_manifests(self):
        for manifest in (SAFE + '\nMobileE2ECommandReceiver', SAFE + '\nandroid:debuggable(0x1)=(type 0x12)0xffffffff', SAFE.replace('0x0', '0xffffffff'), ''):
            with self.subTest(manifest=manifest), self.assertRaises(ValueError):
                self.inspect(manifest)

    def test_requires_signature_tool(self):
        for kwargs in ({'require_signed': True}, {'expected_certificate': 'ab'}):
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                self.inspect(SAFE, **kwargs)

    def test_reject_missing_launcher_icon(self):
        with self.assertRaisesRegex(ValueError, 'Missing application icon'):
            self.inspect(SAFE.replace('android:icon', 'android:other'))

    def test_reject_missing_branding_asset(self):
        with zipfile.ZipFile(self.apk, 'w') as archive:
            archive.writestr('AndroidManifest.xml', 'placeholder')
        with self.assertRaisesRegex(ValueError, 'Missing Nexus branding resource'):
            self.inspect(SAFE)

    def test_reject_unexpected_certificate(self):
        with patch('verify_release.subprocess.run', side_effect=[subprocess.CompletedProcess([], 0, SAFE, ''), subprocess.CompletedProcess([], 0, RESOURCES, ''), subprocess.CompletedProcess([], 0, 'Signer #1 certificate SHA-256 digest: ab', '')]):
            with self.assertRaisesRegex(ValueError, 'Unexpected signing certificate'):
                verify(self.apk, 'aapt', 'apksigner', True, 'cd')

if __name__ == '__main__':
    unittest.main()
