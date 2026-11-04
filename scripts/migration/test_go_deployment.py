"""Render isolated Go Compose definitions with synthetic values, without starting services."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]


class GoDeploymentTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not shutil.which("docker"):
            raise unittest.SkipTest("Docker Compose required for native configuration validation")

    def model(self, distributed=False, values=True, production=False):
        env = {key: value for key, value in os.environ.items() if key in {"PATH", "HOME", "DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_CONFIG"}}
        if values:
            env.update(GO_AUTH_USERNAME="fixture", GO_AUTH_PASSWORD="synthetic-password-p8",
                       GO_POSTGRES_DB="fixture", GO_POSTGRES_USER="fixture", GO_POSTGRES_PASSWORD="synthetic-db",
                       GO_POSTGRES_TLS_DIR="/tmp/personalos-p8-not-real-tls", GO_POSTGRES_CA="/tmp/personalos-p8-not-real-ca.crt",
                       GO_MODEL_KEY_ENCRYPTION_KEY="synthetic-key", GO_REDIS_SESSION_URI="rediss://fixture.invalid:6379",
                       GO_REDIS_SESSION_NAMESPACE="p8-synthetic", GO_PUBLIC_HOST="p8-fixture.invalid")
        command = ["docker", "compose", "--env-file", "/dev/null", "-f", "backend-go/deploy/compose.yml"]
        if distributed:
            command += ["-f", "backend-go/deploy/compose.distributed.yml"]
        if production:
            command += ["-f", "backend-go/deploy/compose.prod.yml"]
        result = subprocess.run(command + ["config", "--format=json"], cwd=ROOT, env=env,
                                capture_output=True, text=True, timeout=45)
        if not values:
            self.assertNotEqual(result.returncode, 0)
            return
        self.assertEqual(result.returncode, 0, result.stderr)
        return json.loads(result.stdout)

    def test_credentials_and_certificates_have_no_connection_defaults(self):
        self.model(values=False)

    def test_combined_ports_nonroot_and_original_migrations(self):
        model = self.model()
        services = model["services"]
        self.assertEqual(set(services), {"postgres", "migrate", "backend", "web"})
        backend = services["backend"]
        self.assertEqual(backend["user"], "10001:10001")
        self.assertTrue(backend["read_only"])
        self.assertEqual(backend["cap_drop"], ["ALL"])
        self.assertIn("no-new-privileges:true", backend["security_opt"])
        self.assertFalse(any(services[name].get("ports") for name in ("backend", "postgres", "migrate")))
        self.assertTrue(all(port["host_ip"] == "127.0.0.1" for port in services["web"]["ports"]))
        environment = backend["environment"]
        self.assertEqual(environment["APP_RUNTIME_ROLE"], "combined")
        self.assertEqual(environment["AUTH_ENABLED"], "true")
        self.assertEqual(environment["APP_SECURITY_CSRF_ENABLED"], "true")
        self.assertEqual(environment["GO_POSTGRES_SSL_MODE"], "verify-full")
        self.assertIn("MODEL_KEY_ENCRYPTION_KEY", environment)
        self.assertNotIn("MODEL_CONFIG_MASTER_KEY", environment)
        self.assertEqual(services["migrate"]["environment"]["FLYWAY_BASELINE_ON_MIGRATE"], "false")
        self.assertTrue(any(v.get("target") == "/flyway/sql" and v.get("read_only") for v in services["migrate"]["volumes"]))

    def test_distributed_roles_have_independent_pools_and_management(self):
        services = self.model(distributed=True)["services"]
        for service, role in {"backend":"api", "index":"index", "review":"review", "delivery":"delivery"}.items():
            entry = services[service]
            self.assertEqual(entry["environment"]["APP_RUNTIME_ROLE"], role)
            self.assertEqual(entry["user"], "10001:10001")
            self.assertFalse(entry.get("ports"))
            if role != "api":
                self.assertIn("worker-" + role, entry["environment"]["SPRING_PROFILES_ACTIVE"])
        self.assertEqual(services["backend"]["environment"]["APP_SHAREDSESSION_ENABLED"], "true")

    def test_production_candidate_requires_secure_cookies_and_separate_tls_entry(self):
        services = self.model(production=True)["services"]
        self.assertEqual(services["backend"]["environment"]["SPRING_PROFILES_ACTIVE"], "monitoring,prod")
        self.assertEqual(services["backend"]["environment"]["SERVER_SERVLET_SESSION_COOKIE_SECURE"], "true")
        self.assertEqual({str(port["target"]) for port in services["web"]["ports"]}, {"8080", "8443"})
        self.assertTrue(all(port["host_ip"] == "127.0.0.1" for port in services["web"]["ports"]))
