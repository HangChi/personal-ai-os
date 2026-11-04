import copy
import unittest

from p8_https_fixture import internal_issuer_config


class GoHTTPSFixtureTest(unittest.TestCase):
    def test_issuer_overlay_preserves_all_production_http_configuration(self):
        config = {"admin": {"disabled": True}, "apps": {"http": {"servers": {
            "srv0": {"listen": [":8443"], "routes": [{"match": [{"host": ["localhost"]}]}]}
        }, "http_port": 8080, "https_port": 8443}}}
        before = copy.deepcopy(config)
        fixture = internal_issuer_config(config)
        self.assertEqual(config, before)
        self.assertEqual(fixture["apps"]["http"], before["apps"]["http"])
        self.assertEqual(fixture["admin"], before["admin"])
        self.assertFalse(fixture["apps"]["pki"]["certificate_authorities"]["local"]["install_trust"])
        self.assertEqual(fixture["apps"]["tls"]["automation"]["policies"][0]["issuers"],
                         [{"module": "internal", "lifetime": "90s"}])

    def test_candidate_tls_changes_require_review_instead_of_silent_replacement(self):
        for app in ("tls", "pki"):
            with self.assertRaises(ValueError):
                internal_issuer_config({"apps": {"http": {}, app: {}}})
