import copy
import io
import json
from contextlib import redirect_stdout
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import go_p6_progress as subject


class P6ProgressTest(unittest.TestCase):
    def test_inventory_paths_cover_tool_ids_and_preserve_other_phases(self):
        inventory = json.loads((subject.SPEC / 'inventory.json').read_text())
        before = json.loads((subject.SPEC / 'progress.json').read_text())
        expected = {entry['id'] for values in inventory.values() if isinstance(values, list)
                    for entry in values if isinstance(entry, dict) and 'id' in entry
                    and '/knowledge/' in entry.get('path', '')
                    and entry['path'].endswith('.java')
                    and Path(entry['path']).stem not in {'KnowledgeExportContributor', 'KnowledgeSearchContributor'}}
        progress = copy.deepcopy(before)
        for key in expected:
            progress['items'].pop(key, None)
        with tempfile.TemporaryDirectory() as tmp:
            spec = Path(tmp)
            (spec / 'inventory.json').write_text(json.dumps(inventory))
            (spec / 'progress.json').write_text(json.dumps(progress))
            with patch.object(subject, 'SPEC', spec), redirect_stdout(io.StringIO()):
                subject.update(list(subject.GROUPS))
            after = json.loads((spec / 'progress.json').read_text())
        self.assertEqual(len(expected), 247)
        self.assertIn('tool:Tool:searchKnowledge', expected)
        for key in expected:
            self.assertEqual(after['items'][key]['status'], 'implemented', key)
            self.assertTrue(after['items'][key]['evidence'], key)
        self.assertEqual(after['release_gates'], before['release_gates'])
        for key in before['items'].keys() - expected:
            self.assertEqual(after['items'][key]['status'], before['items'][key]['status'], key)
