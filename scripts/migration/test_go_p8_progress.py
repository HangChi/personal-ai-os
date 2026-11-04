import copy
import io
import json
from contextlib import redirect_stdout
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import go_p8_progress as subject


class P8ProgressTest(unittest.TestCase):
    def test_only_retention_is_implemented_and_gates_preserved(self):
        inventory = json.loads((subject.SPEC / "inventory.json").read_text())
        before = json.loads((subject.SPEC / "progress.json").read_text())
        selected = {e["id"] for group in ("files", "runtime_hooks", "java_test_cases")
                    for e in inventory[group] if subject.selected(e)}
        self.assertEqual(len(selected), 7)
        progress = copy.deepcopy(before)
        for key in selected:
            progress["items"].pop(key, None)
        with tempfile.TemporaryDirectory() as directory:
            spec = Path(directory)
            (spec / "inventory.json").write_text(json.dumps(inventory))
            (spec / "progress.json").write_text(json.dumps(progress))
            with patch.object(subject, "SPEC", spec), redirect_stdout(io.StringIO()):
                subject.update()
            after = json.loads((spec / "progress.json").read_text())
        self.assertEqual(after["release_gates"], before["release_gates"])
        for key in selected:
            self.assertEqual(after["items"][key]["status"], "implemented")
        for key in before["items"].keys() - selected:
            self.assertEqual(after["items"][key], before["items"][key])
