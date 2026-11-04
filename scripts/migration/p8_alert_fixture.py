#!/usr/bin/env python3
"""Owned local webhook and live alert drill; never selects an existing receiver."""
import argparse
import copy
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import threading
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
ALERT = "PersonalOSGoTargetDown"
CONTAINER_SOURCE = "/fixture/scripts/migration/p8_alert_fixture.py"


def local_receiver_config(template):
    config = copy.deepcopy(template)
    receivers = config.get("receivers", [])
    if (set(config) != {"global", "route", "receivers"}
            or config["global"] != {"resolve_timeout": "5m"}
            or set(config["route"]) != {"receiver", "group_by", "group_wait", "group_interval", "repeat_interval"}
            or config["route"]["receiver"] != "operator-webhook"
            or len(receivers) != 1 or set(receivers[0]) != {"name", "webhook_configs"}
            or receivers[0]["name"] != "operator-webhook"):
        raise ValueError("Review changed receiver template before running the local drill")
    hooks = receivers[0]["webhook_configs"]
    if (len(hooks) != 1 or hooks[0] != dict(url="https://alerts.example.invalid/personal-ai-os",
                                          send_resolved=True, http_config={"follow_redirects": False})):
        raise ValueError("Local fixture refuses extra destinations, credentials or HTTP overrides")
    hooks[0]["url"] = "http://alert-receiver:8080/alerts"
    return config


def write_overlay(temporary, environment, distributed=False):
    directory = Path(temporary)
    config = local_receiver_config(json.loads((ROOT / "backend-go/deploy/monitoring/alertmanager.example.json").read_text()))
    config_path = directory / "alertmanager-local.json"
    config_path.write_text(json.dumps(config))
    config_path.chmod(0o644)
    environment.update(GO_ALERTMANAGER_CONFIG=str(config_path),
                       GO_PROMETHEUS_ALERTING_CONFIG="prometheus.alerting.yml" if distributed else "prometheus.alerting.combined.yml")
    receiver = dict(image="python:3.12-slim", user="65534:65534", read_only=True,
                    cap_drop=["ALL"], security_opt=["no-new-privileges:true"], networks=["private"],
                    command=["python", "-B", CONTAINER_SOURCE, "--receiver"],
                    volumes=[str(Path(__file__).resolve()) + ":" + CONTAINER_SOURCE + ":ro"])
    path = directory / "local-alert-receiver.json"
    path.write_text(json.dumps(dict(services={"alert-receiver": receiver})))
    return path


class Receipts:
    def __init__(self):
        self.lock = threading.Lock()
        self.rejected = 0
        self.accepted = []

    def accept(self, payload):
        if not isinstance(payload, dict) or payload.get("status") not in ("firing", "resolved") or not isinstance(payload.get("alerts"), list):
            return 400
        with self.lock:
            # One deliberate retryable response proves retries reach the owned endpoint.
            if payload["status"] == "firing" and self.rejected == 0:
                self.rejected += 1
                return 503
            if len(self.accepted) >= 64:
                return 429
            self.accepted.append(payload)
            return 200

    def snapshot(self):
        with self.lock:
            return dict(rejected=self.rejected, accepted=list(self.accepted))


def run_receiver():
    receipts = Receipts()

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_args):
            pass

        def respond(self, code, value):
            body = json.dumps(value).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            self.respond(200, receipts.snapshot()) if self.path == "/events" else self.respond(404, {})

        def do_POST(self):
            if self.path != "/alerts":
                self.respond(404, {})
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= 65536:
                    self.respond(413, {})
                    return
                payload = json.loads(self.rfile.read(length))
            except (ValueError, UnicodeError):
                self.respond(400, {})
                return
            self.respond(receipts.accept(payload), {})

    ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()


def notification(snapshot, status, role):
    expected = dict(alertname=ALERT, job="personal-ai-os-go", instance="backend:9091", role=role, severity="critical")
    for payload in snapshot["accepted"]:
        if payload.get("receiver") != "operator-webhook" or payload.get("status") != status:
            continue
        for alert in payload.get("alerts", []):
            if all(alert.get("labels", {}).get(key) == value for key, value in expected.items()) and alert.get("status") == status:
                if not alert.get("fingerprint") or not alert.get("startsAt"):
                    raise AssertionError("Notification lost alert identity")
                if status == "resolved":
                    start = datetime.fromisoformat(alert["startsAt"].replace("Z", "+00:00"))
                    end = datetime.fromisoformat(alert["endsAt"].replace("Z", "+00:00"))
                    if end <= start:
                        raise AssertionError("Resolved notification has invalid interval")
                return alert
    return None


def drill(capture, command, environment, containers, ready, distributed=False):
    role = "api" if distributed else "combined"

    def query(name):
        return json.loads(capture(["docker", "exec", containers["alert-receiver"], "python", "-B",
                                   CONTAINER_SOURCE, "--query", name]))

    def wait(label, seconds, inspect):
        deadline = time.monotonic() + seconds
        while True:
            value = inspect()
            if value:
                return value
            if time.monotonic() >= deadline:
                raise TimeoutError("Local alert drill timed out: " + label)
            time.sleep(1)

    wait("Alertmanager discovery", 45, lambda: query("alertmanagers")["data"]["activeAlertmanagers"])
    rules = [rule for group in query("rules")["data"]["groups"] for rule in group["rules"] if rule.get("name") == ALERT]
    if len(rules) != 1 or rules[0].get("duration") != 120:
        raise AssertionError("Target-down rule must retain its actual two-minute hold")

    def state():
        matches = [alert for alert in query("alerts")["data"]["alerts"]
                   if alert["labels"].get("alertname") == ALERT and alert["labels"].get("instance") == "backend:9091"]
        if len(matches) > 1:
            raise AssertionError("Duplicate target-down alert")
        return matches[0]["state"] if matches else "inactive"

    if state() != "inactive" or query("events")["accepted"]:
        raise AssertionError("Alert drill did not start clean")
    capture(command + ["stop", "--timeout", "40", "backend"], cwd=ROOT, env=environment)
    if capture(["docker", "inspect", "--format", "{{.State.ExitCode}}", containers["backend"]]) != "0":
        raise AssertionError("Drill backend did not stop gracefully")
    started = time.monotonic()
    wait("pending", 45, lambda: state() == "pending")
    print("P8 local alert: actual target stopped, pending observed; preserving for=2m", flush=True)
    wait("firing", 165, lambda: state() == "firing")
    fired = wait("webhook firing and retry", 45, lambda: notification(query("events"), "firing", role))
    if query("events")["rejected"] != 1:
        raise AssertionError("Expected one retryable webhook response before successful delivery")
    print("P8 local alert: firing notification accepted after intentional 503 retry", flush=True)
    capture(command + ["start", "backend"], cwd=ROOT, env=environment)
    ready("backend")
    wait("alert recovery", 60, lambda: state() == "inactive")
    resolved = wait("resolved webhook", 90, lambda: notification(query("events"), "resolved", role))
    if resolved["fingerprint"] != fired["fingerprint"] or resolved["startsAt"] != fired["startsAt"]:
        raise AssertionError("Recovery notification differs from the fired alert")
    print("P8 local alert: healthy target recovered, matching resolved notification accepted", flush=True)
    return dict(rule_for_seconds=120, pending_observed=True, firing_received=True, retryable_503_count=1,
                resolved_received=True, same_fingerprint=True, elapsed_seconds=round(time.monotonic() - started, 1))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--receiver", action="store_true")
    parser.add_argument("--query", choices=["events", "rules", "alerts", "alertmanagers"])
    args = parser.parse_args()
    if args.receiver:
        run_receiver()
    elif args.query:
        url = "http://127.0.0.1:8080/events" if args.query == "events" else "http://prometheus:9090/api/v1/" + args.query
        with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(url, timeout=3) as response:
            print(json.dumps(json.load(response)))
    else:
        parser.error("Select an explicit fixture mode")
