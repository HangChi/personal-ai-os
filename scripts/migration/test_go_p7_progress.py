import copy
import io
import json
from contextlib import redirect_stdout
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import go_p7_progress as subject


class P7ProgressTest(unittest.TestCase):
    def test_all_selected_inventory_ids_and_other_phases_preserved(self):
        inventory = json.loads((subject.SPEC / 'inventory.json').read_text())
        before = json.loads((subject.SPEC / 'progress.json').read_text())
        expected = {entry['id'] for values in inventory.values() if isinstance(values, list)
                    for entry in values if isinstance(entry, dict) and 'id' in entry and subject.selected(entry)}
        progress = copy.deepcopy(before)
        for key in expected:
            progress['items'].pop(key, None)
        with tempfile.TemporaryDirectory() as tmp:
            spec = Path(tmp)
            (spec / 'inventory.json').write_text(json.dumps(inventory))
            (spec / 'progress.json').write_text(json.dumps(progress))
            with patch.object(subject, 'SPEC', spec), redirect_stdout(io.StringIO()):
                subject.update()
            after = json.loads((spec / 'progress.json').read_text())
        self.assertIn('tool:McpTool:personal_list_tasks', expected)
        self.assertEqual(len(expected), 150)
        self.assertEqual(sum(key.startswith('tool:') for key in expected), 4)
        self.assertEqual(after['release_gates'], before['release_gates'])
        for key in expected:
            self.assertEqual(after['items'][key]['status'], 'implemented', key)
            self.assertTrue(after['items'][key]['evidence'], key)
        for key in before['items'].keys() - expected:
            self.assertEqual(after['items'][key], before['items'][key], key)

    def test_only_backup_case_in_governance_is_selected(self):
        path = 'backend/src/test/java/com/personalos/knowledge/governance/KnowledgeGovernanceIntegrationTest.java'
        self.assertTrue(subject.selected(dict(path=path, handler=dict(name=subject.BACKUP))))
        self.assertFalse(subject.selected(dict(path=path, handler=dict(name='sourceScopeVersionAndDateAreValidatedAndAuditable'))))
        self.assertFalse(subject.selected(dict(path='backend/src/main/java/com/personalos/reminder/ReminderService.java')))
