#!/usr/bin/env python3
"""从原 JTokkit 1.1.0 提取确定性词表；默认校验，不下载依赖。"""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[2]
DEST = ROOT / 'backend-go/internal/business/context/assets'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write', action='store_true')
    parser.add_argument('--jar', type=Path, default=Path.home()/'.m2/repository/com/knuddels/jtokkit/1.1.0/jtokkit-1.1.0.jar')
    args = parser.parse_args()
    manifest = {'source': 'com.knuddels:jtokkit:1.1.0', 'license': 'MIT',
                'jar_sha256': hashlib.sha256(args.jar.read_bytes()).hexdigest(), 'vocabularies': {}}
    with zipfile.ZipFile(args.jar) as archive:
        for name in ('cl100k_base', 'o200k_base', 'p50k_base', 'r50k_base'):
            data = archive.read('com/knuddels/jtokkit/'+name+'.tiktoken')
            packed = gzip.compress(data, mtime=0)
            target = DEST/(name+'.txt.gz')
            manifest['vocabularies'][name] = {'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data), 'tokens': len(data.splitlines())}
            if args.write:
                DEST.mkdir(parents=True, exist_ok=True)
                target.write_bytes(packed)
            elif gzip.decompress(target.read_bytes()) != data:
                raise SystemExit('词表发生变化：'+name)
    text = json.dumps(manifest, ensure_ascii=False, indent=2)+'\n'
    if args.write:
        (DEST/'manifest.json').write_text(text)
    elif (DEST/'manifest.json').read_text() != text:
        raise SystemExit('词表来源清单发生变化')
    print('原JTokkit四份词表/来源哈希通过')


if __name__ == '__main__':
    main()
