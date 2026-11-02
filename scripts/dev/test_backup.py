"""备份校验必须拒绝篡改、路径穿越、重复归档成员。"""
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import subprocess
import importlib.util
import backup


class BackupValidationTest(unittest.TestCase):
    def archive(self, directory, files):
        path = directory / 'backup.tar'
        with tarfile.open(path, 'w') as tar:
            for name, data in files:
                info = tarfile.TarInfo(name)
                info.size = len(data)
                tar.addfile(info, io.BytesIO(data))
        return path

    def test_tamper_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            manifest = json.dumps({'formatVersion': 1, 'sha256': {'database.dump': 'wrong'}}).encode()
            path = self.archive(root, [('manifest.json', manifest), ('database.dump', b'data')])
            with self.assertRaisesRegex(ValueError, '校验失败'):
                backup.unpack(path, root / 'out')

    def test_traversal_and_duplicate_are_rejected(self):
        for files in [[('../outside', b'bad')], [('database.dump', b'a'), ('database.dump', b'b')]]:
            with self.subTest(files=files), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                with self.assertRaises(ValueError):
                    backup.unpack(self.archive(root, files), root / 'out')
                self.assertFalse((root / 'outside').exists())

    def test_checksum_required_before_docker(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'bad.tar'
            path.write_bytes(b'broken')
            with self.assertRaisesRegex(ValueError, 'SHA-256'):
                backup.verify(path, {})

    def test_partial_dump_is_never_published_or_pruned(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            old = root / 'pre-clean-test.dump'
            old.write_bytes(b'existing backup')
            def fail(*args, **kwargs):
                kwargs['stdout'].write(b'incomplete')
                raise subprocess.CalledProcessError(1, 'pg_dump')
            with patch.object(backup, 'pg', side_effect=fail):
                with self.assertRaises(subprocess.CalledProcessError):
                    backup.backup({'BACKUP_DIR': str(root)})
            self.assertEqual(list(root.iterdir()), [old])

    def test_generated_vapid_keys_are_private_and_not_overwritten(self):
        spec = importlib.util.spec_from_file_location('vapid', Path(__file__).with_name('generate-push-keys.py'))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'vapid.env'
            module.generate(path)
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            original = path.read_bytes()
            with self.assertRaises(FileExistsError):
                module.generate(path)
            self.assertEqual(path.read_bytes(), original)


if __name__ == '__main__':
    unittest.main()

class S3SnapshotTest(unittest.TestCase):
    def config(self):
        return {'S3_ENDPOINT': 'http://127.0.0.1:8333', 'S3_BUCKET': 'personalos',
                'S3_NAMESPACE': 'unit-test', 'S3_ACCESS_KEY': 'test', 'S3_SECRET_KEY': 'test'}

    def response(self, data, owner=None):
        response = io.BytesIO(data)
        response.headers = {'x-amz-meta-personalos-owner': owner} if owner else {}
        return response

    def listing(self, key, size=4, tail=''):
        return ('<ListBucketResult><Contents><Key>' + key + '</Key><Size>' + str(size)
                + '</Size></Contents>' + tail + '</ListBucketResult>').encode()

    def test_remote_bytes_are_backed_up_and_local_fallback_has_priority(self):
        snapshot = backup.S3Snapshot(self.config())
        identity = '00000000-0000-4000-8000-000000000001'
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp)
            responses = [self.response(self.listing(snapshot.prefix + identity)), self.response(b'data', snapshot.owner)]
            with patch.object(snapshot, 'get', side_effect=responses):
                self.assertEqual(snapshot.copy_to(target), 1)
            self.assertEqual((target / identity).read_bytes(), b'data')
            (target / identity).write_bytes(b'local')
            with patch.object(snapshot, 'get', return_value=self.response(self.listing(snapshot.prefix + identity))) as request:
                self.assertEqual(snapshot.copy_to(target), 0)
                self.assertEqual(request.call_count, 1)
            self.assertEqual((target / identity).read_bytes(), b'local')

    def test_bad_keys_wrong_owner_and_truncated_objects_fail(self):
        snapshot = backup.S3Snapshot(self.config())
        identity = '00000000-0000-4000-8000-000000000001'
        cases = [[self.response(self.listing('outside/' + identity))],
                 [self.response(self.listing(snapshot.prefix + '../outside'))],
                 [self.response(self.listing(snapshot.prefix + identity)), self.response(b'data', 'other-owner')],
                 [self.response(self.listing(snapshot.prefix + identity, 5)), self.response(b'data', snapshot.owner)]]
        for responses in cases:
            with self.subTest(), tempfile.TemporaryDirectory() as tmp:
                with patch.object(snapshot, 'get', side_effect=responses):
                    with self.assertRaises(ValueError):
                        snapshot.copy_to(Path(tmp))

    def test_incomplete_credentials_are_rejected(self):
        with self.assertRaises(ValueError):
            backup.S3Snapshot({'S3_ENDPOINT': 'http://127.0.0.1:8333'})
