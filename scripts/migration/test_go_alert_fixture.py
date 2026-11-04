import copy
import json
from pathlib import Path
import tempfile
import unittest

from p8_alert_fixture import ALERT, CONTAINER_SOURCE, ROOT, Receipts, local_receiver_config, notification, write_overlay


def payload(status="firing"):
    return dict(receiver="operator-webhook", status=status, alerts=[dict(status=status,
        labels=dict(alertname=ALERT, job="personal-ai-os-go", instance="backend:9091", role="combined", severity="critical"),
        fingerprint="fixture", startsAt="2026-11-04T10:00:00Z", endsAt="2026-11-04T10:03:00Z")])


class GoAlertFixtureTest(unittest.TestCase):
    def test_owned_overlay_uses_only_local_receiver_and_no_public_ports(self):
        original = (ROOT / "backend-go/deploy/monitoring/alertmanager.example.json").read_bytes()
        for distributed in (False, True):
            with self.subTest(distributed=distributed), tempfile.TemporaryDirectory() as directory:
                env = {}
                receiver = json.loads(write_overlay(directory, env, distributed).read_text())["services"]["alert-receiver"]
                self.assertNotIn("ports", receiver)
                self.assertEqual(receiver["networks"], ["private"])
                self.assertEqual(receiver["user"], "65534:65534")
                self.assertTrue(receiver["read_only"])
                self.assertEqual(Path(CONTAINER_SOURCE).parents[2], Path("/fixture"))
                self.assertEqual(receiver["command"][2], CONTAINER_SOURCE)
                config = json.loads(Path(env["GO_ALERTMANAGER_CONFIG"]).read_text())
                expected = json.loads(original)
                expected["receivers"][0]["webhook_configs"][0]["url"] = "http://alert-receiver:8080/alerts"
                self.assertEqual(config, expected)
                self.assertEqual(env["GO_PROMETHEUS_ALERTING_CONFIG"],
                                 "prometheus.alerting.yml" if distributed else "prometheus.alerting.combined.yml")
        self.assertEqual((ROOT / "backend-go/deploy/monitoring/alertmanager.example.json").read_bytes(), original)

    def test_retryable_response_does_not_count_as_accepted_delivery(self):
        receipts = Receipts()
        self.assertEqual(receipts.accept(payload()), 503)
        self.assertEqual(receipts.snapshot(), dict(rejected=1, accepted=[]))
        self.assertEqual(receipts.accept(payload()), 200)
        self.assertEqual(receipts.accept(payload("resolved")), 200)
        self.assertEqual(len(receipts.snapshot()["accepted"]), 2)

    def test_changed_template_cannot_silently_enable_other_destinations(self):
        original = json.loads((ROOT / "backend-go/deploy/monitoring/alertmanager.example.json").read_text())
        changes = []
        extra = copy.deepcopy(original)
        extra["receivers"].append(dict(name="external", webhook_configs=[dict(url="https://unexpected.invalid")]))
        changes.append(extra)
        route = copy.deepcopy(original)
        route["route"]["routes"] = [dict(receiver="external")]
        changes.append(route)
        hook = copy.deepcopy(original)
        hook["receivers"][0]["webhook_configs"].append(dict(url="https://unexpected.invalid"))
        changes.append(hook)
        override = copy.deepcopy(original)
        override["receivers"][0]["webhook_configs"][0]["http_config"]["proxy_url"] = "http://unexpected.invalid"
        changes.append(override)
        for changed in changes:
            with self.subTest(config=changed), self.assertRaises(ValueError):
                local_receiver_config(changed)

    def test_invalid_payload_does_not_consume_retry_or_delivery(self):
        for invalid in (None, [], {}, dict(status="firing", alerts="bad")):
            receipts = Receipts()
            self.assertEqual(receipts.accept(invalid), 400)
            self.assertEqual(receipts.snapshot(), dict(rejected=0, accepted=[]))

    def test_notifications_require_expected_receiver_target_role_and_state(self):
        original = payload()
        self.assertIsNotNone(notification(dict(accepted=[original]), "firing", "combined"))
        self.assertIsNone(notification(dict(accepted=[original]), "resolved", "combined"))
        self.assertIsNone(notification(dict(accepted=[original]), "firing", "api"))
        for key in original["alerts"][0]["labels"]:
            changed = copy.deepcopy(original)
            changed["alerts"][0]["labels"][key] = "other"
            self.assertIsNone(notification(dict(accepted=[changed]), "firing", "combined"))
        changed = copy.deepcopy(original)
        changed["receiver"] = "other"
        self.assertIsNone(notification(dict(accepted=[changed]), "firing", "combined"))

    def test_missing_identity_and_invalid_recovery_interval_fail_closed(self):
        for field in ("fingerprint", "startsAt"):
            changed = payload()
            del changed["alerts"][0][field]
            with self.assertRaises(AssertionError):
                notification(dict(accepted=[changed]), "firing", "combined")
        changed = payload("resolved")
        changed["alerts"][0]["endsAt"] = changed["alerts"][0]["startsAt"]
        with self.assertRaises(AssertionError):
            notification(dict(accepted=[changed]), "resolved", "combined")

    def test_delivery_history_is_bounded(self):
        receipts = Receipts()
        for _ in range(64):
            self.assertEqual(receipts.accept(payload("resolved")), 200)
        self.assertEqual(receipts.accept(payload("resolved")), 429)
        self.assertEqual(len(receipts.snapshot()["accepted"]), 64)
