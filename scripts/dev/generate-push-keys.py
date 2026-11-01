#!/usr/bin/env python3
"""生成 VAPID 密钥到权限为 0600 的新文件，终端只显示文件路径（需 OpenSSL）。"""
import argparse
import base64
import os
from pathlib import Path
import subprocess
import tempfile


def generate(destination):
    with tempfile.TemporaryDirectory(prefix='paios-vapid-') as directory:
        private = Path(directory) / 'key.pem'
        subprocess.run(['openssl', 'ecparam', '-name', 'prime256v1', '-genkey', '-noout', '-out', str(private)],
                       check=True, capture_output=True)
        public = subprocess.run(['openssl', 'ec', '-in', str(private), '-pubout', '-outform', 'DER'],
                                check=True, capture_output=True).stdout
        secret = subprocess.run(['openssl', 'ec', '-in', str(private), '-outform', 'DER'],
                                check=True, capture_output=True).stdout
        # RFC5915 ECPrivateKey: INTEGER version=1, OCTET STRING 32字节私钥；SPKI 尾部是未压缩点。
        marker = secret.index(b'\x02\x01\x01\x04\x20') + 5
        private_bytes = secret[marker:marker + 32]
        public_bytes = public[-65:]
        if len(private_bytes) != 32 or len(public_bytes) != 65 or public_bytes[0] != 4:
            raise ValueError('不支持的 OpenSSL 密钥编码')
        encode = lambda data: base64.urlsafe_b64encode(data).rstrip(b'=').decode()
        text = ('WEB_PUSH_PUBLIC_KEY=' + encode(public_bytes) + '\nWEB_PUSH_PRIVATE_KEY=' + encode(private_bytes)
                + '\n# 另行配置 WEB_PUSH_SUBJECT=mailto:你的联系邮箱 和 WEB_PUSH_ENABLED=true\n')
        # O_EXCL 防止覆盖用户已有密钥；不在进程参数/终端输出私钥。
        descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, 'w') as output:
            output.write(text)
    print('推送密钥已保存：' + str(destination.resolve()))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path, help='尚不存在的目标文件，勿提交到 Git')
    arguments = parser.parse_args()
    try:
        generate(arguments.output)
    except (ValueError, OSError, subprocess.CalledProcessError):
        raise SystemExit('生成失败：请检查 OpenSSL、输出目录，以及目标文件是否已经存在')
