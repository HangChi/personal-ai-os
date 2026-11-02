#!/usr/bin/env python3
"""生成 VAPID 密钥到权限为 0600 的新文件，终端只显示文件路径（需 OpenSSL）。"""
import argparse
import base64
import os
from pathlib import Path
import subprocess
import tempfile


def ec_private_scalar(encoded):
    """读取 RFC5915 的版本与 OCTET STRING，兼容省略前导零的 OpenSSL 编码。"""
    def item(data, offset):
        if offset + 2 > len(data):
            raise ValueError('不完整的 DER 编码')
        tag, size = data[offset], data[offset + 1]
        start = offset + 2
        if size & 0x80:
            width = size & 0x7f
            if not 1 <= width <= 4 or start + width > len(data):
                raise ValueError('不合法的 DER 长度')
            size = int.from_bytes(data[start:start + width], 'big')
            start += width
        end = start + size
        if end > len(data):
            raise ValueError('不完整的 DER 内容')
        return tag, data[start:end], end
    tag, sequence, end = item(encoded, 0)
    if tag != 0x30 or end != len(encoded):
        raise ValueError('不支持的 EC 私钥结构')
    tag, version, offset = item(sequence, 0)
    if tag != 0x02 or version != b'\x01':
        raise ValueError('不支持的 EC 私钥版本')
    tag, scalar, _ = item(sequence, offset)
    if tag != 0x04 or not 1 <= len(scalar) <= 32 or not any(scalar):
        raise ValueError('不支持的 P-256 私钥长度')
    return scalar.rjust(32, b'\x00')


def generate(destination):
    if destination.exists():
        raise FileExistsError('目标密钥文件已存在')
    with tempfile.TemporaryDirectory(prefix='paios-vapid-') as directory:
        private = Path(directory) / 'key.pem'
        subprocess.run(['openssl', 'ecparam', '-name', 'prime256v1', '-genkey', '-noout', '-out', str(private)],
                       check=True, capture_output=True)
        public = subprocess.run(['openssl', 'ec', '-in', str(private), '-pubout', '-outform', 'DER'],
                                check=True, capture_output=True).stdout
        secret = subprocess.run(['openssl', 'ec', '-in', str(private), '-outform', 'DER'],
                                check=True, capture_output=True).stdout
        # RFC5915 ECPrivateKey: INTEGER version=1, OCTET STRING 32字节私钥；SPKI 尾部是未压缩点。
        private_bytes = ec_private_scalar(secret)
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
