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
