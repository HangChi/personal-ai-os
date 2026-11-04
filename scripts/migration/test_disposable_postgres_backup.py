import subprocess
import unittest
from unittest.mock import patch

import disposable_postgres_backup as subject


class DisposableBackupTest(unittest.TestCase):
    def test_seed_and_dump_never_mutate_business_test_database(self):
        calls = []

        def execute(command, **kwargs):
            calls.append(command)
            return subprocess.CompletedProcess(command, 0, stdout=b"PGDMPsynthetic")

        with patch.object(subject.subprocess, "run", side_effect=execute):
            subject.verify_backup_fixture("a" * 64)
        seed = next(command for command in calls if "psql" in command)
        dump = next(command for command in calls if "pg_dump" in command)
        self.assertIn("--dbname=personalos_go_backup_source", seed)
        self.assertIn("--dbname=personalos_go_backup_source", dump)
        self.assertFalse(any("--dbname=personalos_go_test" in command for command in calls))
        self.assertIn("--template=personalos_go_test", calls[0])
        self.assertIn("--dbname=personalos_go_restore", calls[-1])


if __name__ == "__main__":
    unittest.main()
