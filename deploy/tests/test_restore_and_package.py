import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value)
    return value
restore = load('restore', ROOT/'deploy/restore-rehearsal.py')
package = load('package', ROOT/'deploy/package-release.py')


class FakePostgres(restore.Rehearsal):
    def __init__(self, args):
        super().__init__(args)
        self.databases = {}; self.commands = []; self.active = 0

    def command(self, executable, *args, **kwargs):
        self.commands.append((executable, args))
        if executable == 'createdb':
            name = args[-1]
            if name in self.databases: raise AssertionError('Never replace a preexisting database')
            self.databases[name] = {'oid': 81234, 'owner': self.args.user, 'comment': None}
        elif executable == 'pg_restore':
            if '--single-transaction' not in args or '--exit-on-error' not in args: raise AssertionError('Atomic restore required')
            if 'stdin' not in kwargs or 'input' in kwargs: raise AssertionError('Stream the dump')
            assert kwargs['stdin'].read(1) == b'x'
        elif executable == 'dropdb':
            del self.databases[args[-1]]
        else: raise AssertionError('Unexpected executable: '+executable)
        return b''

    def sql(self, database, statement):
        if "from pg_database where datname='" in statement:
            name=statement.split("datname='")[1].split("'")[0]
            return json.dumps(self.databases[name]) if name in self.databases else ''
        if statement.startswith('comment on database '):
            name=re.search(r'"([^"]+)"',statement).group(1)
            self.databases[name]['comment']=statement.split(" is '")[1][:-1]
            return ''
        if 'pg_stat_activity' in statement: return str(self.active)
        if 'information_schema.columns' in statement: return json.dumps({'case_file':['id']})
        if 'jsonb_build_object' in statement: return json.dumps({'count':1,'hashes':['fixture-row-digest']})
        raise AssertionError('Unexpected SQL')


class RestoreAndPackage(unittest.TestCase):
    def setUp(self):
        root=Path('C:/Users/drxuj/Temp') if os.name=='nt' else Path('/mnt/c/Users/drxuj/Temp')
        root.mkdir(parents=True,exist_ok=True)
        self.tmp=tempfile.TemporaryDirectory(dir=root,prefix='ybcase-restore-tool-');self.addCleanup(self.tmp.cleanup)
        self.folder=Path(self.tmp.name);self.source=self.folder/'source';self.source.mkdir()
        self.identifier='2026-11-08-120001-000001'
        restore.sets.begin(self.source,self.identifier)
        self.dump=self.source/('db-'+self.identifier+'.dump');self.dump.write_bytes(b'x'*12000)
        restore.sets.complete(self.source,self.identifier,False,0)
        self.manifest=self.source/('set-'+self.identifier+'.json')

    def args(self):
        return argparse.Namespace(work_directory=self.folder/'rehearsal',transport='native',
            home=str(self.folder),user='hip',host='127.0.0.1',port=5432,manifest=self.manifest,baseline=None)

    def test_restore_inventory_then_identity_checked_cleanup_only_owns_new_database(self):
        drill=FakePostgres(self.args());result=drill.restore()
        self.assertTrue(result['passed']);self.assertFalse(result['originalDatabaseTouched'])
        self.assertFalse(result['legacyRowsCompared']);self.assertFalse(result['offsiteHostAcceptance'])
        self.assertRegex(result['database'],r'^ybcase_restore_drill_[0-9a-f]{12}$')
        self.assertTrue(drill.cleanup()['ownDatabaseRemoved']);self.assertEqual({},drill.databases)
        self.assertTrue((drill.work/'result.json').is_file())

    def test_changed_database_or_active_connection_cannot_be_dropped(self):
        drill=FakePostgres(self.args());result=drill.restore();name=result['database']
        drill.databases[name]['oid']=99999
        with self.assertRaisesRegex(ValueError,'identity changed'):drill.cleanup()
        drill.databases[name]['oid']=81234;drill.active=1
        with self.assertRaisesRegex(ValueError,'active connections'):drill.cleanup()
        self.assertNotIn('dropdb',[command[0] for command in drill.commands])

    def test_tampered_backup_is_rejected_before_database_creation(self):
        self.dump.write_bytes(b'changed')
        drill=FakePostgres(self.args())
        with self.assertRaisesRegex(ValueError,'checksum mismatch'):drill.restore()
        self.assertEqual([],drill.commands)

    def test_baseline_row_mismatch_cannot_pass_restore_acceptance(self):
        args=self.args();args.baseline=self.folder/'baseline.json'
        args.baseline.write_text(json.dumps({'columns':{'case_file':['id']},'tables':{'case_file':{'count':1,'hashes':['different']}}}))
        drill=FakePostgres(args)
        with self.assertRaisesRegex(ValueError,'rows differ'):drill.restore()
        self.assertFalse((drill.work/'result.json').exists())
        self.assertTrue(drill.cleanup()['passed'])

    def test_production_database_name_is_never_an_allowed_restore_identity(self):
        drill=FakePostgres(self.args())
        with self.assertRaisesRegex(ValueError,'Unsafe restore database'):drill.identity('ybcase')
        self.assertEqual([],drill.commands)

    def test_candidate_packaging_binds_migrations_and_excludes_secrets(self):
        jar=self.folder/'candidate.jar'
        with zipfile.ZipFile(jar,'w') as archive:archive.writestr('BOOT-INF/classes/db/migration/V59__test.sql','select 1;')
        dist=self.folder/'dist';(dist/'assets').mkdir(parents=True)
        (dist/'index.html').write_text('<html></html>');(dist/'assets/app.js').write_text('export {}')
        args=argparse.Namespace(output=self.folder/'package',jar=jar,dist=dist,baseline_schema=50,target_schema=58,
                               baseline_jar_sha256='a'*64,baseline_index_sha256='b'*64)
        with self.assertRaisesRegex(ValueError,'schema does not match'):package.package(args)
        self.assertFalse(args.output.exists());args.target_schema=59
        result=package.package(args)
        self.assertTrue(result['passed']);self.assertTrue((args.output/'payload.tar.gz').is_file())
        manifest=json.loads((args.output/'manifest.json').read_text())
        self.assertIn('backup-set.py',manifest);self.assertIn('release.py',manifest);self.assertNotIn('.env',manifest)
        with self.assertRaisesRegex(ValueError,'new private directory'):package.package(args)


if __name__=='__main__':unittest.main()
