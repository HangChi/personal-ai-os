#!/usr/bin/env python3
"""Send once through the immutable Java Push transport using a private disposable fixture."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import tempfile
from urllib.parse import urlsplit
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SOURCES = ['WebPushTransport', 'WebPushSettings', 'PushTransport']


def private_fixture(path):
    info, parent = path.lstat(), path.parent.lstat()
    if (not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o600
            or not stat.S_ISDIR(parent.st_mode) or stat.S_IMODE(parent.st_mode) != 0o700
            or not path.parent.name.startswith('personalos-p5-chrome-')):
        raise ValueError('Requires a private harness-owned fixture')
    fixture = json.loads(path.read_text())
    endpoint = urlsplit(fixture['subscription']['endpoint'])
    if (fixture.get('marker') != 'P8 paired java transport fixture' or endpoint.scheme != 'https'
            or endpoint.hostname != 'fcm.googleapis.com' or endpoint.username or endpoint.fragment
            or endpoint.port not in (None, 443)):
        raise ValueError('Invalid disposable subscription or marker')
    return fixture


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixture', type=Path)
    parser.add_argument('--compile-only', action='store_true', help='Compile without credentials or network sends')
    args = parser.parse_args()
    if not args.compile_only:
        if not args.fixture:
            parser.error('--fixture is required unless --compile-only')
        private_fixture(args.fixture)
    baseline = json.loads((ROOT / 'specs/033-go-rewrite/inventory.json').read_text())['baseline_commit']
    report = ROOT / 'backend/target/surefire-reports/TEST-com.personalos.chat.service.ChatSessionServiceTest.xml'
    cp = next(p.attrib['value'] for p in ET.parse(report).find('properties')
              if p.attrib.get('name') == 'java.class.path')
    cp = os.pathsep.join(p for p in cp.split(os.pathsep) if p.endswith('.jar'))
    env = {k: v for k, v in os.environ.items() if k in {'PATH', 'HOME', 'TMPDIR', 'JAVA_HOME', 'LANG'}}
    hashes = {}
    with tempfile.TemporaryDirectory(prefix='personalos-push-java-') as directory:
        files = []
        for source in SOURCES:
            path = 'backend/src/main/java/com/personalos/calendar/push/' + source + '.java'
            raw = subprocess.check_output(['git', 'show', baseline + ':' + path], cwd=ROOT)
            target = Path(directory) / (source + '.java')
            target.write_bytes(raw)
            files.append(str(target))
            hashes[path] = hashlib.sha256(raw).hexdigest()
        compiled = subprocess.run(['javac', '-encoding', 'UTF-8', '-proc:none', '-sourcepath', '', '-cp', cp,
                        '-d', directory, *files, str(ROOT / 'scripts/migration/PushTransportOracle.java')],
                       env=env, capture_output=True, text=True, timeout=90)
        if compiled.returncode:
            raise SystemExit('Java fixture compilation failed: ' + compiled.stderr[:4000])
        if args.compile_only:
            print('Fixed Java Push fixture compiled; no browser, credentials or sends')
            return
        result = subprocess.run(['java', '-cp', directory + os.pathsep + cp,
                                 'com.personalos.migration.PushTransportOracle', str(args.fixture)],
                                env=env, capture_output=True, text=True, timeout=30)
    print('P8_PUSH_SOURCE ' + json.dumps(dict(baseline_commit=baseline, sources_sha256=hashes), sort_keys=True))
    status = next((line for line in result.stdout.splitlines() if re.fullmatch(r'P8_PUSH_STATUS [0-9]{3}', line)), None)
    if status:
        print(status)
    if result.returncode:
        raise SystemExit('Disposable Java transport failed (details redacted)')


if __name__ == '__main__':
    try:
        main()
    except (subprocess.SubprocessError, OSError, ValueError, KeyError) as error:
        raise SystemExit('Disposable Java fixture failed: ' + type(error).__name__) from None
