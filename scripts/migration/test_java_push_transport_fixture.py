import json
from pathlib import Path
import tempfile
import unittest

from java_push_transport_fixture import private_fixture


class PushFixtureTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='personalos-p5-chrome-')
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / 'subscription.json'
        self.data = dict(marker='P8 paired java transport fixture',
                         subscription=dict(endpoint='https://fcm.googleapis.com/disposable'))
        self.path.write_text(json.dumps(self.data))
        self.path.chmod(0o600)

    def test_private_fixture_accepts_os_temp_alias_but_not_a_file_symlink(self):
        self.assertEqual(private_fixture(self.path), self.data)
        self.assertEqual(private_fixture(self.path.resolve()), self.data)
        link = self.path.parent / 'linked.json'
        link.symlink_to(self.path)
        with self.assertRaises(ValueError):
            private_fixture(link)

    def test_permissive_file_or_directory_is_rejected(self):
        self.path.chmod(0o644)
        with self.assertRaises(ValueError):
            private_fixture(self.path)
        self.path.chmod(0o600)
        self.path.parent.chmod(0o755)
        with self.assertRaises(ValueError):
            private_fixture(self.path)

    def test_non_fcm_destination_or_wrong_marker_is_rejected(self):
        for endpoint, marker in [('https://example.com/', self.data['marker']),
                                 ('https://fcm.googleapis.com:444/', self.data['marker']),
                                 ('https://fcm.googleapis.com/', 'wrong')]:
            self.path.write_text(json.dumps(dict(marker=marker, subscription=dict(endpoint=endpoint))))
            with self.assertRaises(ValueError):
                private_fixture(self.path)
