import importlib.util
import io
import json
from pathlib import Path
import tempfile
import tarfile
import time
import unittest

spec = importlib.util.spec_from_file_location('backup_sets', Path(__file__).parents[1] / 'backup-set.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class BackupSets(unittest.TestCase):
    def setUp(self):
        root = Path('C:/Users/drxuj/Temp') if __import__('os').name == 'nt' else Path('/mnt/c/Users/drxuj/Temp')
        root.mkdir(parents=True, exist_ok=True)
        self.assertNotIn('onedrive', str(root.resolve()).lower())
        self.tmp = tempfile.TemporaryDirectory(dir=root, prefix='ybcase-complete-set-')
        self.addCleanup(self.tmp.cleanup)
        self.folder = Path(self.tmp.name)

    def create(self, identifier='2026-11-08-120001-000001', attachments=False):
        m.begin(self.folder, identifier)
        (self.folder / ('db-' + identifier + '.dump')).write_bytes(b'database' * 2000)
        if attachments:
            with tarfile.open(self.folder / ('att-' + identifier + '.tar'), 'w') as archive:
                directory = tarfile.TarInfo('attachments'); directory.type = tarfile.DIRTYPE
                archive.addfile(directory)
                file = tarfile.TarInfo('attachments/source.bin'); file.size = 4
                archive.addfile(file, io.BytesIO(b'data'))
        return m.complete(self.folder, identifier, attachments, int(attachments))

    def test_complete_database_and_historical_file_set_is_verified(self):
        value = self.create(attachments=True)
        self.assertEqual(2, len(value['files']))
        self.assertTrue(m.check(self.folder)['passed'])

    def test_database_success_attachment_failure_does_not_commit(self):
        identifier = '2026-11-08-120001-000001'
        m.begin(self.folder, identifier)
        (self.folder / ('db-' + identifier + '.dump')).write_bytes(b'database' * 2000)
        with self.assertRaisesRegex(ValueError, 'Attachment archive missing'):
            m.complete(self.folder, identifier, True, 1)
        m.fail(self.folder, identifier, 'attachments')
        self.assertFalse(list(self.folder.glob('set-*.json')))
        with self.assertRaisesRegex(ValueError, 'Most recent backup attempt failed'):
            m.check(self.folder)

    def test_recent_failure_overrides_a_fresh_older_success(self):
        self.create()
        m.begin(self.folder, '2026-11-08-120002-000002')
        m.fail(self.folder, '2026-11-08-120002-000002', 'attachments')
        with self.assertRaisesRegex(ValueError, 'Most recent backup attempt failed'):
            m.check(self.folder)

    def test_missing_or_changed_member_is_not_healthy(self):
        value = self.create(attachments=True)
        path = self.folder / value['files']['attachments']['name']
        path.write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'checksum mismatch'):
            m.check(self.folder)
        path.unlink()
        with self.assertRaisesRegex(ValueError, 'member missing'):
            m.check(self.folder)

    def test_incomplete_attachment_count_and_unsafe_members_are_rejected(self):
        identifier = '2026-11-08-120001-000001'
        m.begin(self.folder, identifier)
        (self.folder / ('db-' + identifier + '.dump')).write_bytes(b'database' * 2000)
        archive = self.folder / ('att-' + identifier + '.tar')
        with tarfile.open(archive, 'w') as stream:
            item = tarfile.TarInfo('../outside'); item.size = 1
            stream.addfile(item, io.BytesIO(b'x'))
        with self.assertRaisesRegex(ValueError, 'Invalid attachment archive member'):
            m.complete(self.folder, identifier, True, 1)

    def test_stalled_attempt_and_expired_success_are_rejected(self):
        value = self.create()
        m.begin(self.folder, '2026-11-08-120002-000002')
        status = m.read(self.folder / 'last-attempt.json')
        status['startedAtEpoch'] = time.time() - 3 * 3600
        m.write_json(self.folder / 'last-attempt.json', status)
        with self.assertRaisesRegex(ValueError, 'stalled'):
            m.check(self.folder)
        status['status'] = 'COMPLETE'
        m.write_json(self.folder / 'last-attempt.json', status)
        with self.assertRaisesRegex(ValueError, 'too old'):
            m.check(self.folder, max_age_hours=-1)

    def test_retention_removes_whole_sets_and_keeps_loose_legacy_files(self):
        for n in range(1, 4):
            self.create(f'2026-11-08-12000{n}-00000{n}', attachments=True)
        legacy = self.folder / 'db-2000-01-01.dump'; legacy.write_bytes(b'old unclaimed file')
        m.prune(self.folder, 2)
        self.assertEqual(2, len(list(self.folder.glob('set-*.json'))))
        self.assertEqual(2, len(list(self.folder.glob('att-*.tar'))))
        self.assertEqual(3, len(list(self.folder.glob('db-*.dump'))))
        self.assertTrue(legacy.exists())
        self.assertTrue(m.check(self.folder)['passed'])

    def test_wrong_attempt_cannot_replace_running_status(self):
        m.begin(self.folder, '2026-11-08-120001-000001')
        before = (self.folder / 'last-attempt.json').read_bytes()
        with self.assertRaisesRegex(ValueError, 'Another backup attempt'):
            m.fail(self.folder, '2026-11-08-120002-000002', 'database')
        self.assertEqual(before, (self.folder / 'last-attempt.json').read_bytes())

    def test_an_orphan_file_cannot_hide_a_missing_referenced_attachment(self):
        original = self.create(attachments=True)
        identifier = '2026-11-08-120002-000002'
        m.begin(self.folder, identifier)
        (self.folder / ('db-' + identifier + '.dump')).write_bytes(b'database' * 2000)
        (self.folder / ('att-' + identifier + '.tar')).write_bytes(
            (self.folder / original['files']['attachments']['name']).read_bytes())
        references = self.folder / ('files-' + identifier + '.json')
        references.write_text(json.dumps([{'file_path': './data/attachments/missing.bin', 'size_bytes': 4}]))
        # There is one file and one DB reference, but their identities differ.
        with self.assertRaisesRegex(ValueError, 'referenced attachment is missing'):
            m.complete(self.folder, identifier, True, 1, references)
        references.write_text(json.dumps([{'file_path': './data/attachments/source.bin', 'size_bytes': 4}]))
        complete = m.complete(self.folder, identifier, True, 1, references)
        self.assertTrue(complete['externalReferencesRecorded'])
        self.assertTrue(m.check(self.folder)['passed'])


if __name__ == '__main__':
    unittest.main()
