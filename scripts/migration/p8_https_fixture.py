"""Private-CA test overlay for the actual production candidate; never a prod launcher."""
import copy
import hashlib
import json
from pathlib import Path
import socket
import ssl
import subprocess
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
IMAGE = "personal-ai-os-web-go:candidate"


def internal_issuer_config(adapted):
    result = copy.deepcopy(adapted)
    if "tls" in result["apps"] or "pki" in result["apps"]:
        raise ValueError("Review candidate TLS changes before modifying the fixture")
    # Only issuance/timing change. All production HTTP routes/listeners/headers remain.
    result["apps"]["pki"] = {"certificate_authorities": {"local": {"install_trust": False}}}
    result["apps"]["tls"] = {"automation": {"renew_interval": "2s", "policies": [{
        "subjects": ["localhost"], "issuers": [{"module": "internal", "lifetime": "90s"}],
        "renewal_window_ratio": 0.5,
    }]}}
    return result


def write_overlay(directory, environment):
    path = ROOT / "backend-go/deploy/Caddyfile.prod"
    output = subprocess.run([
        "docker", "run", "--rm", "--name", "personalos-p8-adapt-" + uuid.uuid4().hex[:12],
        "--network", "none", "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
        "--env", "GO_PUBLIC_HOST=localhost", "--env", "GO_HTTPS_PORT=" + environment["GO_HTTPS_PORT"],
        "--mount", "type=bind,source=" + str(path) + ",target=/fixture/Caddyfile,readonly",
        "--entrypoint", "caddy", IMAGE, "adapt", "--config", "/fixture/Caddyfile", "--adapter", "caddyfile",
    ], check=True, text=True, capture_output=True, timeout=30)
    fixture = Path(directory) / "caddy-private-ca.json"
    fixture.write_text(json.dumps(internal_issuer_config(json.loads(output.stdout))))
    overlay = Path(directory) / "disposable-https.json"
    overlay.write_text(json.dumps({"services": {
        "backend": {"environment": {"SPRING_PROFILES_ACTIVE": "monitoring", "APP_SECURITY_PRODUCTIONMODE": "false"}},
        "web": {"entrypoint": ["caddy", "run", "--config", "/fixture/caddy.json"],
                "volumes": [str(fixture) + ":/fixture/caddy.json:ro"]},
    }}))
    return overlay


def root_context(container):
    deadline = time.monotonic() + 30
    while True:
        result = subprocess.run(["docker", "exec", container, "cat", "/data/caddy/pki/authorities/local/root.crt"],
                                text=True, capture_output=True, timeout=5)
        if result.returncode == 0:
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
            context.load_verify_locations(cadata=result.stdout)
            return context, hashlib.sha256(ssl.PEM_cert_to_DER_cert(result.stdout)).hexdigest()
        if time.monotonic() >= deadline:
            raise TimeoutError("Private test CA was not created in candidate certificate volume")
        time.sleep(.3)


def peer(context, port, hostname="localhost"):
    with socket.create_connection(("127.0.0.1", port), timeout=3) as raw:
        with context.wrap_socket(raw, server_hostname=hostname) as connection:
            certificate = connection.getpeercert()
            return {"sha256": hashlib.sha256(connection.getpeercert(binary_form=True)).hexdigest(),
                    "serial": certificate["serialNumber"], "notAfter": certificate["notAfter"],
                    "version": connection.version()}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def verify_entry(context, https_port, http_port):
    # A known valid name must reach certificate verification and reject the CA.
    try:
        peer(ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT), https_port)
    except ssl.SSLCertVerificationError:
        pass
    else:
        raise AssertionError("HTTPS accepted an unknown CA")
    # Caddy may reject unknown SNI before sending a certificate at all.
    try:
        peer(context, https_port, "p8-wrong-host.invalid")
    except ssl.SSLError:
        pass
    else:
        raise AssertionError("HTTPS accepted a wrong hostname")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    try:
        opener.open("http://localhost:" + str(http_port) + "/tasks?fixture=p8", timeout=5)
    except urllib.error.HTTPError as response:
        with response:
            if response.code != 308 or response.headers.get("Location") != "https://localhost:" + str(https_port) + "/tasks?fixture=p8":
                raise AssertionError("Candidate HTTP redirect does not preserve the public HTTPS port/path/query: "
                                     + str(response.headers.get("Location")))
    else:
        raise AssertionError("Candidate HTTP listener did not redirect to HTTPS")
    return peer(context, https_port)


def verify_renewal(context, port, original):
    deadline = time.monotonic() + 70
    while True:
        current = peer(context, port)
        if current["sha256"] != original["sha256"]:
            if current["serial"] == original["serial"] or ssl.cert_time_to_seconds(current["notAfter"]) <= ssl.cert_time_to_seconds(original["notAfter"]):
                raise AssertionError("Renewed certificate did not advance serial/expiry")
            return current
        if time.monotonic() >= deadline:
            raise TimeoutError("Private-CA certificate did not renew within the test window")
        time.sleep(1)
