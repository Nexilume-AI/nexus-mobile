import subprocess
import struct
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

JNI_CLASSES = ('Lorg/jni_zero/JniZero;', 'Lorg/jni_zero/CommonApis;', 'Lorg/webrtc/PeerConnectionFactory;')

def dex_fixture(classes=JNI_CLASSES, defined=True):
    """Minimal DEX tables, distinguishing a class definition from a mere reference."""
    count = len(classes)
    types = 112 + count * 4
    definitions = types + count * 4
    data = bytearray(definitions + (count * 32 if defined else 0))
    data[:8] = b'dex\n035\0'
    struct.pack_into('<4I', data, 56, count, 112, count, types)
    struct.pack_into('<2I', data, 96, count if defined else 0, definitions)
    for i, name in enumerate(classes):
        struct.pack_into('<I', data, 112 + i * 4, len(data))
        struct.pack_into('<I', data, types + i * 4, i)
        if defined:
            struct.pack_into('<I', data, definitions + i * 32, i)
        data.extend(bytes([len(name)]) + name.encode('ascii') + b'\0')
    return data

class ReleaseVerifierTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.apk = Path(self.temp.name) / 'release.apk'
        with zipfile.ZipFile(self.apk, 'w') as archive:
            archive.writestr('AndroidManifest.xml', 'placeholder')
            for i, _ in enumerate(ICONS):
                archive.writestr(f'res/optimized{i}.xml', 'placeholder')
            archive.writestr('classes.dex', dex_fixture())

    def inspect(self, manifest, **kwargs):
        with patch('verify_release.subprocess.run', side_effect=[
            subprocess.CompletedProcess([], 0, manifest, ''),
            subprocess.CompletedProcess([], 0, RESOURCES, ''),
        ]):
            return verify(self.apk, 'aapt', **kwargs)

    def test_unsigned_release_has_no_signature_claim(self):
        result = self.inspect(SAFE)
        self.assertFalse(result['signature_verified'])
        self.assertTrue(result['webrtc_jni_checked'])
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

    def test_reject_stripped_jni_bridge_even_if_dex_references_it(self):
        with zipfile.ZipFile(self.apk, 'a') as archive:
            archive.writestr('classes2.dex', dex_fixture(defined=False))
        # Rebuild just the primary DEX as if R8 removed all JNI-only classes.
        with zipfile.ZipFile(self.apk) as archive:
            entries = {name: archive.read(name) for name in archive.namelist()}
        entries['classes.dex'] = dex_fixture(('Lorg/webrtc/PeerConnectionFactory;',))
        with zipfile.ZipFile(self.apk, 'w') as archive:
            for name, content in entries.items():
                archive.writestr(name, content)
        with self.assertRaisesRegex(ValueError, 'JNI.*JniZero'):
            self.inspect(SAFE)

    def test_accept_jni_bridge_in_secondary_dex(self):
        with zipfile.ZipFile(self.apk) as archive:
            entries = {name: archive.read(name) for name in archive.namelist()}
        entries['classes.dex'] = dex_fixture(())
        entries['classes2.dex'] = dex_fixture()
        with zipfile.ZipFile(self.apk, 'w') as archive:
            for name, content in entries.items():
                archive.writestr(name, content)
        self.assertTrue(self.inspect(SAFE)['webrtc_jni_checked'])

    def test_reject_unexpected_certificate(self):
        with patch('verify_release.subprocess.run', side_effect=[subprocess.CompletedProcess([], 0, SAFE, ''), subprocess.CompletedProcess([], 0, RESOURCES, ''), subprocess.CompletedProcess([], 0, 'Signer #1 certificate SHA-256 digest: ab', '')]):
            with self.assertRaisesRegex(ValueError, 'Unexpected signing certificate'):
                verify(self.apk, 'aapt', 'apksigner', True, 'cd')

if __name__ == '__main__':
    unittest.main()
