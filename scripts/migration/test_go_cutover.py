import unittest

from run_go_cutover_tests import differences, normalize


class CutoverStoragePrecisionTest(unittest.TestCase):
    def test_original_nanoseconds_and_database_rounding_agree(self):
        self.assertEqual(normalize("2026-10-26T06:56:26.965619591Z"), normalize("2026-10-26T06:56:26.965620Z"))
        self.assertEqual(normalize("2026-10-26T06:56:26.999999591Z"), normalize("2026-10-26T06:56:27Z"))
        self.assertEqual(normalize("2026-10-26T06:56:26.96562Z"), normalize("2026-10-26T06:56:26.965620Z"))

    def test_one_millisecond_and_null_are_not_hidden(self):
        instant = normalize("2026-10-26T06:56:26.965620Z")
        self.assertNotEqual(instant, normalize("2026-10-26T06:56:26.966620Z"))
        self.assertEqual(normalize(None), None)
        self.assertNotEqual(instant, normalize(None))
        self.assertTrue(differences({"createdAt": instant}, {"createdAt": None}))

    def test_only_export_timestamp_is_omitted(self):
        old = {"exportedAt": "earlier", "content": "unchanged", "dueAt": "2026-10-26T06:56:26Z"}
        new = dict(old, exportedAt="later")
        self.assertEqual(normalize(old), normalize(new))
        self.assertTrue(differences(normalize(old), normalize(dict(new, dueAt="2026-10-27T06:56:26Z"))))
