"""临时 TLS 测试证书；不读取系统/用户密钥，不修改系统信任。"""

import os
from pathlib import Path
import re
import shutil
import subprocess


def capture(command, **kwargs):
    return subprocess.run(command, text=True, capture_output=True, check=True,
                          timeout=120, **kwargs).stdout.strip()


def create_tls_fixture(directory, dns_names=()):
    """只生成本次测试 CA/服务端证书；CA 私钥不挂载入容器。"""
    if any(not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9.-]{0,252}", name) for name in dns_names):
        raise ValueError("Invalid disposable TLS DNS name")
    folder = Path(directory)
    server = folder / "server"
    server.mkdir(mode=0o700)
    configuration = folder / "ca.cnf"
    configuration.write_text("""[req]
distinguished_name=dn
x509_extensions=v3_ca
prompt=no
[dn]
CN=personalos-go-test-ca
[v3_ca]
basicConstraints=critical,CA:true
keyUsage=critical,keyCertSign,cRLSign
subjectKeyIdentifier=hash
""")
    environment = {"PATH": os.environ.get("PATH", "/usr/bin:/bin")}
    capture(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-sha256", "-days", "2",
             "-config", str(configuration), "-keyout", str(folder / "ca.key"),
             "-out", str(folder / "ca.crt")], env=environment)
    server_configuration = folder / "server.cnf"
    server_configuration.write_text("[req]\ndistinguished_name=dn\nprompt=no\n[dn]\nCN=localhost\n")
    capture(["openssl", "req", "-new", "-newkey", "rsa:2048", "-nodes", "-sha256",
             "-config", str(server_configuration),
             "-keyout", str(server / "server.key"), "-out", str(folder / "server.csr")], env=environment)
    extensions = folder / "server.ext"
    extensions.write_text("basicConstraints=critical,CA:false\nkeyUsage=critical,digitalSignature,keyEncipherment\n"
                          "extendedKeyUsage=serverAuth\nsubjectKeyIdentifier=hash\nauthorityKeyIdentifier=keyid,issuer\n"
                          "subjectAltName=DNS:localhost,IP:127.0.0.1" + "".join(",DNS:" + name for name in dns_names) + "\n")
    capture(["openssl", "x509", "-req", "-in", str(folder / "server.csr"), "-CA", str(folder / "ca.crt"),
             "-CAkey", str(folder / "ca.key"), "-CAcreateserial", "-days", "2", "-sha256",
             "-extfile", str(extensions), "-out", str(server / "server.crt")], env=environment)
    shutil.copyfile(folder / "ca.crt", server / "ca.crt")
    capture(["openssl", "verify", "-CAfile", str(folder / "ca.crt"), str(server / "server.crt")], env=environment)
    return server, (folder / "ca.crt").read_text()

