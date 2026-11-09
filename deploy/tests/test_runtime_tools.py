import argparse
import hashlib
import http.server
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value)
    return value
capacity = load('capacity', ROOT / 'tools/capacity-check.py')
transfer = load('transfer', ROOT / 'deploy/backup-transfer.py')


class CapacityGuards(unittest.TestCase):
    def proof(self, base='http://127.0.0.1:45050/api'):
        return {'isolated': True, 'baseUrl': base, 'database': 'ybcase_authowner_test_20261028_abcdef12',
                'jarSha256': 'a' * 64, 'caseCount': 100, 'checkedAtEpoch': time.time()}

    def test_public_default_and_mismatched_sources_are_refused(self):
        for base in ('https://43.129.201.125.sslip.io/api', 'http://127.0.0.1:8090/api',
                     'http://user:password@127.0.0.1:45050/api', 'http://127.0.0.1:45050/other'):
            with self.subTest(base=base), self.assertRaises(ValueError):
                capacity.validate(base, self.proof(base), ['/bureau/cases'])

    def test_stale_empty_and_nonisolated_fixture_are_refused(self):
        for field, value in (('isolated', False), ('database', 'ybcase'), ('caseCount', 0),
                             ('checkedAtEpoch', time.time() - 7200), ('jarSha256', 'unknown')):
            proof = self.proof(); proof[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                capacity.validate(proof['baseUrl'], proof, ['/bureau/cases'])

    def test_only_scoped_read_paths_are_allowed(self):
        proof = self.proof()
        for path in ('/auth/login', '/system/users', '/bureau/cases/1/close', '/bureau/cases/../users'):
            with self.subTest(path=path), self.assertRaises(ValueError):
                capacity.validate(proof['baseUrl'], proof, [path])

    def measure(self, handler):
        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        self.addCleanup(server.server_close); self.addCleanup(server.shutdown)
        root = Path('C:/Users/drxuj/Temp') if os.name == 'nt' else Path('/mnt/c/Users/drxuj/Temp')
        root.mkdir(parents=True, exist_ok=True)
        tmp = tempfile.TemporaryDirectory(dir=root, prefix='ybcase-capacity-test-'); self.addCleanup(tmp.cleanup)
        folder = Path(tmp.name); base = f'http://127.0.0.1:{server.server_port}/api'
        (folder / 'proof.json').write_text(json.dumps(self.proof(base)))
        (folder / 'paths.json').write_text(json.dumps(['/bureau/cases']))
        args = argparse.Namespace(isolation_proof=folder/'proof.json', paths=folder/'paths.json',
                base_url=base, workers=2, requests=8, p95_ms=1000, max_error_rate=0, timeout=3)
        with patch.dict(os.environ, YBCASE_LOAD_TOKEN='isolated-fixture-token'):
            return capacity.measure(args)

    def test_successful_workload_records_explicit_targets(self):
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *_): pass
            def do_GET(self):
                self.send_response(200); self.end_headers(); self.wfile.write(b'{"code":0,"data":[]}')
        result = self.measure(Handler)
        self.assertTrue(result['passed']); self.assertEqual(8, result['requests'])
        self.assertEqual(0, result['errors']); self.assertEqual(1000, result['targets']['p95Ms'])

    def test_business_failure_counts_as_error_and_redirects_are_not_followed(self):
        class Failure(http.server.BaseHTTPRequestHandler):
            followed = 0
            def log_message(self, *_): pass
            def do_GET(self):
                if self.path == '/forbidden':
                    Failure.followed += 1
                    self.send_response(200); self.end_headers(); self.wfile.write(b'{"code":0}')
                else:
                    self.send_response(302); self.send_header('Location', '/forbidden'); self.end_headers()
        result = self.measure(Failure)
        self.assertFalse(result['passed']); self.assertEqual(8, result['errors']); self.assertEqual(0, Failure.followed)


@unittest.skipUnless(os.environ.get('YBCASE_TEST_AGE_BIN'), 'Real age binary required for encryption smoke tests')
class EncryptedTransfer(unittest.TestCase):
    def setUp(self):
        root = Path('/mnt/c/Users/drxuj/Temp')
        root.mkdir(parents=True, exist_ok=True)
        self.tmp = tempfile.TemporaryDirectory(dir=root, prefix='ybcase-encrypted-test-'); self.addCleanup(self.tmp.cleanup)
        self.folder = Path(self.tmp.name); self.source = self.folder/'source'; self.source.mkdir()
        self.destination = self.folder/'offsite-simulation'; self.destination.mkdir()
        self.binary = Path(os.environ['YBCASE_TEST_AGE_BIN']).resolve()
        self.identity = self.folder/'identity.private.txt'
        subprocess.run([str(self.binary.with_name('age-keygen')), '-o', str(self.identity)], capture_output=True, check=True)
        public = subprocess.check_output([str(self.binary.with_name('age-keygen')), '-y', str(self.identity)]).decode().strip()
        self.recipients = self.folder/'recipients.txt'; self.recipients.write_text(public+'\n')
        self.identifier = '2026-11-08-120001-000001'
        transfer.sets.begin(self.source, self.identifier)
        self.database = self.source/('db-'+self.identifier+'.dump')
        self.database.write_bytes(b'PRIVATE-SYNTHETIC-BACKUP-ONLY-2026!' * 1000)
        transfer.sets.complete(self.source,self.identifier,False,0)
        self.manifest = self.source/('set-'+self.identifier+'.json')

    def export(self):
        result = transfer.export_set(argparse.Namespace(manifest=self.manifest, destination=self.destination,
                            recipients=self.recipients, age_binary=str(self.binary)))
        encrypted = self.destination/result['encryptedFile']
        receipt = self.destination/(encrypted.name+'.receipt.json')
        return result, encrypted, receipt

    def test_real_encryption_roundtrip_and_source_bytes_remain_unchanged(self):
        before = self.database.read_bytes()
        result, encrypted, receipt = self.export()
        self.assertTrue(result['passed']); self.assertFalse(result['offsiteAcceptance'])
        self.assertNotIn(b'PRIVATE-SYNTHETIC-BACKUP-ONLY-2026!', encrypted.read_bytes())
        imported = self.folder/'restored-copy'
        response = transfer.import_set(argparse.Namespace(encrypted=encrypted,receipt=receipt,identity=self.identity,
                          destination=imported,age_binary=str(self.binary)))
        self.assertTrue(response['integrityVerified']); self.assertFalse(response['databaseRestored'])
        self.assertEqual(before,(imported/self.database.name).read_bytes());self.assertEqual(before,self.database.read_bytes())

    def test_corrupted_ciphertext_is_not_committed_even_if_transport_hash_is_updated(self):
        result, encrypted, receipt = self.export()
        content = bytearray(encrypted.read_bytes()); content[-1] ^= 1; encrypted.write_bytes(content)
        metadata = json.loads(receipt.read_text());metadata['encryptedSha256']=transfer.sets.sha(encrypted)
        receipt.write_text(json.dumps(metadata))
        target=self.folder/'must-not-exist'
        with self.assertRaises((ValueError,tarfile.TarError,EOFError)):
            transfer.import_set(argparse.Namespace(encrypted=encrypted,receipt=receipt,identity=self.identity,
                destination=target,age_binary=str(self.binary)))
        self.assertFalse(target.exists());self.assertFalse(list(self.folder.glob('ybcase-import-*')))

    def test_private_key_is_not_accepted_as_public_recipient_configuration(self):
        with self.assertRaisesRegex(ValueError,'public recipients'):
            transfer.export_set(argparse.Namespace(manifest=self.manifest,destination=self.destination,
                    recipients=self.identity,age_binary=str(self.binary)))
        self.assertEqual([], list(self.destination.iterdir()))


if __name__=='__main__':
    unittest.main()
