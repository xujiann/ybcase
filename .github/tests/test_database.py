import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('ci_database_lifecycle', ROOT / '.github/scripts/test_database.py')
DB = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = DB
SPEC.loader.exec_module(DB)
NONCE = 'abcdef1234567890abcdef1234567890'


class FakePsql:
    def __init__(self):
        self.row = None
        self.operations = []
        self.fail_create = self.fail_comment = self.fail_drop = False

    def execute(self, statement, **variables):
        operation = statement.split()[0]
        self.operations.append((operation, variables))
        if operation == 'create':
            if self.fail_create or self.row is not None:
                raise DB.LifecycleError('Database already exists')
            self.row = {'database': variables['database'], 'oid': '12345', 'owner': variables['owner'],
                        'marker': None, 'login': variables['owner'], 'connections': 0}
        elif operation == 'comment':
            if self.fail_comment:
                raise DB.LifecycleError('Comment failed')
            self.row['marker'] = variables['marker']
        elif operation == 'drop':
            if self.fail_drop:
                raise DB.LifecycleError('Drop rejected')
            self.row = None
        else:
            raise AssertionError('Unexpected statement')
        return ''

    def inspect(self, database):
        self.operations.append(('inspect', {'database': database}))
        return None if self.row is None else dict(self.row)


class LifecycleTests(unittest.TestCase):
    def setUp(self):
        temporary_root = Path(os.environ.get('YBCASE_TEST_TMP_ROOT', os.environ.get('RUNNER_TEMP',
                                   ROOT / 'data/recovery/ci-unit-temp')))
        temporary_root.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=temporary_root)
        self.addCleanup(self.temporary.cleanup)
        self.folder = Path(self.temporary.name)
        self.state = self.folder / 'database.json'
        self.environment = self.folder / 'github-env'
        self.connection = DB.Connection('127.0.0.1', 5432, 'hip', 'private-password')
        self.psql = FakePsql()
        self.lifecycle = DB.Lifecycle(self.state, self.connection, self.psql)

    def provision(self):
        with patch.object(DB.secrets, 'token_hex', return_value=NONCE):
            return self.lifecycle.provision(self.environment)

    def test_fresh_database_exports_matching_guard_identity_and_cleans_without_force(self):
        record = self.provision()
        self.assertEqual(record['database'], 'ybcase_authowner_test_20261028_abcdef12')
        self.assertEqual([name for name, _ in self.psql.operations], ['create', 'inspect', 'comment', 'inspect'])
        exported = dict(line.split('=', 1) for line in self.environment.read_text().splitlines())
        self.assertEqual(exported, {
            'SPRING_DATASOURCE_URL': record['jdbcUrl'], 'SPRING_DATASOURCE_USERNAME': 'hip',
            'YBCASE_TEST_DATABASE_URL': record['jdbcUrl'], 'YBCASE_TEST_DATABASE_OID': '12345',
            'YBCASE_TEST_DATABASE_OWNER': 'hip', 'YBCASE_TEST_DATABASE_MARKER': 'ybcase-test:' + NONCE})
        self.assertNotIn(self.connection.password, self.environment.read_text())
        self.assertEqual(self.lifecycle.check()['phase'], 'authorized')
        self.assertEqual(self.lifecycle.cleanup()['phase'], 'cleaned')
        self.assertIsNone(self.psql.row)
        self.assertEqual(self.lifecycle.cleanup()['phase'], 'cleaned')
        self.assertEqual([name for name, _ in self.psql.operations].count('drop'), 1)

    def test_create_collision_never_comments_or_adopts_an_existing_database(self):
        self.psql.fail_create = True
        with self.assertRaises(DB.LifecycleError):
            self.provision()
        self.assertEqual([name for name, _ in self.psql.operations], ['create'])
        self.assertEqual(json.loads(self.state.read_text())['phase'], 'planned')
        self.assertFalse(self.environment.exists())
        with self.assertRaises(DB.LifecycleError):
            self.lifecycle.cleanup()
        self.assertEqual(len(self.psql.operations), 1)

    def test_comment_failure_cannot_export_authorization_or_remove_an_unmarked_database(self):
        self.psql.fail_comment = True
        with self.assertRaises(DB.LifecycleError):
            self.provision()
        self.assertEqual(json.loads(self.state.read_text())['phase'], 'created')
        self.assertFalse(self.environment.exists())
        with self.assertRaises(DB.LifecycleError):
            self.lifecycle.cleanup()
        self.assertNotIn('drop', [name for name, _ in self.psql.operations])

    def test_an_existing_state_record_is_never_overwritten_by_another_provision(self):
        original = self.provision()
        calls = len(self.psql.operations)
        with self.assertRaises(FileExistsError):
            self.provision()
        self.assertEqual(len(self.psql.operations), calls)
        self.assertEqual(json.loads(self.state.read_text()), original)

    def test_reserved_legacy_zero_suffix_is_never_used_even_if_randomness_returns_it(self):
        with patch.object(DB.secrets, 'token_hex', side_effect=['0' * 32, NONCE]):
            record = self.lifecycle.provision(self.environment)
        self.assertTrue(record['database'].endswith('abcdef12'))

    def test_identity_changes_reject_cleanup_without_drop(self):
        record = self.provision()
        for key, changed in [('database', 'ybcase'), ('oid', '99999'), ('owner', 'other'),
                             ('marker', 'ybcase-test:' + '1' * 32), ('login', 'other')]:
            with self.subTest(field=key):
                self.psql.row = {**record, 'login': 'hip', 'connections': 0, key: changed}
                with self.assertRaises(DB.LifecycleError):
                    self.lifecycle.cleanup()
        self.assertNotIn('drop', [name for name, _ in self.psql.operations])

    def test_active_connections_refuse_cleanup_without_terminating_them(self):
        self.provision()
        self.psql.row['connections'] = 1
        with self.assertRaises(DB.LifecycleError):
            self.lifecycle.cleanup()
        self.assertNotIn('drop', [name for name, _ in self.psql.operations])

    def test_wrong_recorded_endpoint_or_owner_refuses_before_database_access(self):
        original = self.provision()
        calls = len(self.psql.operations)
        for key, value in [('host', '127.0.0.2'), ('port', 5433), ('owner', 'other'), ('jdbcUrl', 'jdbc:postgresql://127.0.0.1:5432/ybcase')]:
            with self.subTest(field=key):
                self.state.write_text(json.dumps({**original, key: value}))
                with self.assertRaises(DB.LifecycleError):
                    self.lifecycle.cleanup()
                self.assertEqual(len(self.psql.operations), calls)

    def test_invalid_or_reusable_recorded_name_refuses_before_database_access(self):
        original = self.provision()
        calls = len(self.psql.operations)
        for name in ['ybcase', 'ybcase_test', 'ybcase_authowner_test_20261028_00000000', 'x;drop database ybcase']:
            with self.subTest(name=name):
                self.state.write_text(json.dumps({**original, 'database': name, 'jdbcUrl': self.connection.url(name)}))
                with self.assertRaises(DB.LifecycleError):
                    self.lifecycle.cleanup()
                self.assertEqual(len(self.psql.operations), calls)

    def test_no_state_means_no_database_access_and_no_cleanup_claim(self):
        self.assertEqual(self.lifecycle.cleanup(), {'phase': 'not-created'})
        self.assertEqual(self.psql.operations, [])

    def test_failed_drop_does_not_claim_cleanup_and_can_be_explicitly_retried(self):
        self.provision()
        self.psql.fail_drop = True
        with self.assertRaises(DB.LifecycleError):
            self.lifecycle.cleanup()
        self.assertEqual(json.loads(self.state.read_text())['phase'], 'authorized')
        self.psql.fail_drop = False
        self.assertEqual(self.lifecycle.cleanup()['phase'], 'cleaned')

    def test_a_database_reappearing_after_cleanup_is_not_adopted(self):
        record = self.provision()
        self.lifecycle.cleanup()
        self.psql.row = {**record, 'login': 'hip', 'connections': 0}
        with self.assertRaises(DB.LifecycleError):
            self.lifecycle.cleanup()
        self.assertEqual([name for name, _ in self.psql.operations].count('drop'), 1)


class PsqlBoundaryTests(unittest.TestCase):
    def test_uses_stdin_psql_variables_environment_credentials_and_explicit_admin_database(self):
        observed = []
        def run(command, **options):
            observed.append((command, options))
            return SimpleNamespace(returncode=0, stdout='')
        config = DB.Connection('127.0.0.1', 5432, 'hip', 'secret-password')
        psql = DB.Psql(config, {'PATH': '/bin', 'PGOPTIONS': '-c role=other', 'PGDATABASE': 'ybcase',
                                'PGSERVICE': 'other', 'PGPASSWORD': 'wrong'}, run)
        statement = 'create database :"database" owner :"owner" template template0;'
        psql.execute(statement, database='ybcase_authowner_test_20261028_abcdef12', owner='hip')
        command, options = observed[0]
        self.assertNotIn(config.password, repr(command))
        self.assertNotIn(config.password, repr(config))
        self.assertEqual(command[command.index('--dbname') + 1], 'postgres')
        self.assertIn('--no-psqlrc', command)
        self.assertIn('ON_ERROR_STOP=1', command)
        self.assertEqual(options['input'], statement)
        self.assertEqual(options['env'], {'PATH': '/bin', 'PGPASSWORD': config.password, 'PGCONNECT_TIMEOUT': '5'})
        self.assertNotIn('shell', options)
        self.assertEqual(options['timeout'], 20)

    def test_psql_failure_or_timeout_never_retries_a_statement(self):
        for outcome in ['failed', 'timeout']:
            with self.subTest(outcome=outcome):
                calls = []
                def run(*args, **kwargs):
                    calls.append(1)
                    if outcome == 'timeout':
                        raise subprocess.TimeoutExpired('psql', 20)
                    return SimpleNamespace(returncode=1, stdout='')
                with self.assertRaises(DB.LifecycleError):
                    DB.Psql(DB.Connection('127.0.0.1', 5432, 'hip', 'secret'), {}, run).execute('create database x;')
                self.assertEqual(len(calls), 1)

    def test_public_hostname_and_malformed_credentials_cannot_start_psql(self):
        base = {'PGUSER': 'hip', 'PGPASSWORD': 'secret'}
        for values in [{'PGHOST': '43.129.201.125'}, {'PGHOST': 'example.com'}, {'PGHOST': '--dbname=ybcase'},
                       {'PGPORT': '0'}, {'PGPORT': '65536'}, {'PGUSER': 'x;drop database ybcase'}, {'PGPASSWORD': ''}]:
            with self.subTest(values=values):
                with self.assertRaises(DB.LifecycleError):
                    DB.Connection.from_environment({**base, **values})

    def test_backend_workflow_provisions_and_always_cleans_instead_of_using_the_legacy_database(self):
        workflow = (ROOT / '.github/workflows/ci.yml').read_text(encoding='utf-8')
        backend = workflow.split('  backend:\n', 1)[1].split('  frontend:\n', 1)[0]
        self.assertIn('POSTGRES_DB: postgres', backend)
        self.assertNotIn('00000000', backend)
        self.assertLess(backend.index('test_database.py provision'), backend.index('mvn -B'))
        self.assertLess(backend.index('mvn -B'), backend.index('test_database.py cleanup'))
        self.assertIn('if: ${{ always() }}', backend)
        self.assertIn('"$RUNNER_TEMP/ybcase-test-database.json"', backend)


if __name__ == '__main__':
    unittest.main()
