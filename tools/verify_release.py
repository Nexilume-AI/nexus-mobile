"""Inspect a built APK rather than trusting source-set declarations alone."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile

def verify(apk, aapt, apksigner=None, require_signed=False, expected_certificate=None):
    if expected_certificate and not apksigner: raise ValueError('--expected-certificate needs --apksigner')
    apk=Path(apk).resolve()
    manifest=subprocess.run([str(aapt), 'dump', 'xmltree', str(apk), 'AndroidManifest.xml'],
                            capture_output=True, text=True, check=True).stdout
    for name in ('MobileE2ECommandReceiver', 'MobileE2EActivity', 'DEBUG_E2E_COMMAND'):
        if name in manifest: raise ValueError('Debug-only component present in release APK: '+name)
    for name in ('usesCleartextTraffic','allowBackup'):
        if not re.search(r'android:'+name+r'\([^\n]*?=\(type 0x12\)0x0\b',manifest):
            raise ValueError(name+' must be explicitly false in APK manifest')
    if re.search(r'android:debuggable[^\n]*=\(type 0x12\)0xffffffff',manifest):
        raise ValueError('APK is debuggable')
    with zipfile.ZipFile(apk) as archive:
        if any(name.endswith(('.jks','.keystore','.key')) for name in archive.namelist()):
            raise ValueError('Unexpected key material in APK')
    result={'apk':apk.name,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),
            'release_manifest_checked':True,'signature_verified':False}
    if require_signed and not apksigner: raise ValueError('--require-signed needs --apksigner')
    if apksigner:
        checked=subprocess.run([str(apksigner),'verify','--verbose','--print-certs',str(apk)],
                               capture_output=True,text=True)
        if checked.returncode:
            raise ValueError('APK signature verification failed')
        digests=re.findall(r'certificate SHA-256 digest: ([0-9a-fA-F]+)',checked.stdout)
        if not digests: raise ValueError('No signer certificate digest')
        if expected_certificate and [d.lower() for d in digests]!=[expected_certificate.lower()]:
            raise ValueError('Unexpected signing certificate')
        result.update(signature_verified=True,signer_sha256=digests)
    apk.with_suffix(apk.suffix+'.sha256').write_text(result['sha256']+'  '+apk.name+'\n')
    return result

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk',required=True)
    parser.add_argument('--aapt',required=True)
    parser.add_argument('--apksigner')
    parser.add_argument('--require-signed',action='store_true')
    parser.add_argument('--expected-certificate')
    args=parser.parse_args()
    print(json.dumps(verify(args.apk,args.aapt,args.apksigner,args.require_signed,args.expected_certificate),indent=2))
