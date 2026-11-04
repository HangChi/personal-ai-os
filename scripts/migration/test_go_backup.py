import unittest
from unittest.mock import patch

import go_backup


class GoBackupTest(unittest.TestCase):
    def fixture(self):
        return dict(GO_BACKUP_DB_CONTAINER="synthetic-only", GO_BACKUP_DB_USER="fixture", GO_BACKUP_DB_NAME="fixture",
                    GO_BACKUP_DIR="/tmp/p8-backup-fixture", GO_BACKUP_UPLOAD_DIR="/tmp/p8-upload-fixture")

    def test_no_java_target_defaults_and_no_dotenv_reader(self):
        with patch.object(go_backup.baseline, "settings", side_effect=AssertionError("must not read .env")):
            with self.assertRaises(ValueError):
                go_backup.configuration({}, "backup")
            result = go_backup.configuration(self.fixture(), "verify")
        self.assertEqual(result, {"DB_CONTAINER": "synthetic-only", "POSTGRES_USER": "fixture", "POSTGRES_DB": "fixture"})

    def test_plaintext_and_attachment_source_are_explicit(self):
        values = self.fixture()
        with self.assertRaises(ValueError):
            go_backup.configuration(values, "backup")
        result = go_backup.configuration(values, "backup", True)
        self.assertEqual(result["BACKUP_RETAIN"], "0")
        self.assertEqual(result["BACKUP_AGE_RECIPIENT"], "")
        values["GO_BACKUP_BACKEND_CONTAINER"] = "synthetic-backend"
        with self.assertRaises(ValueError):
            go_backup.configuration(values, "backup", True)
        values.pop("GO_BACKUP_UPLOAD_DIR")
        self.assertEqual(go_backup.configuration(values, "backup", True)["BACKUP_BACKEND_CONTAINER"], "synthetic-backend")


if __name__ == "__main__":
    unittest.main()
