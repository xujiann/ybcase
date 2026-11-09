#!/usr/bin/env python3
"""Restore a verified backup into a newly owned database and attachment directory."""
import argparse
from collections import Counter
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import re
import secrets
import shutil
import subprocess
import tarfile
import time

spec = importlib.util.spec_from_file_location('backup_set', Path(__file__).with_name('backup-set.py'))
sets = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sets)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def quote(value):
    return '"' + value.replace('"', '""') + '"'


class Rehearsal:
    def __init__(self, args):
        self.args = args
        self.work = args.work_directory.resolve()
        require('onedrive' not in str(self.work).lower(), 'OneDrive is not a rehearsal destination')
        self.prefix = ['docker', 'compose', 'exec', '-T', 'db'] if args.transport == 'docker-compose' else []
        self.common = ['-U', args.user]
        if args.transport == 'native':
            self.common += ['-h', args.host, '-p', str(args.port)]
        self.env = {**os.environ, 'PGTZ': 'Asia/Shanghai'}

    def command(self, executable, *args, **kwargs):
        result = subprocess.run(self.prefix + [executable] + self.common + list(args),
                                cwd=self.args.home, env=self.env, capture_output=True, **kwargs)
        if result.returncode:
            with (self.work / 'commands-error.log').open('ab') as stream:
                stream.write(result.stderr)
            raise ValueError('PostgreSQL command failed; inspect private commands-error.log')
        return result.stdout

    def sql(self, database, statement):
        return self.command('psql', '-XqAt', '-v', 'ON_ERROR_STOP=1', '-d', database,
                            input=statement.encode()).decode().strip()

    def identity(self, name):
        require(bool(re.fullmatch(r'ybcase_restore_drill_[0-9a-f]{12}', name)), 'Unsafe restore database name')
        raw = self.sql('postgres', "select json_build_object('oid',oid,'owner',pg_get_userbyid(datdba),"
                       "'comment',shobj_description(oid,'pg_database')) from pg_database where datname='" + name + "'")
        return json.loads(raw) if raw else None

    def inventory(self, database, baseline):
        columns = baseline['columns'] if baseline else json.loads(self.sql(database, """
            select jsonb_object_agg(table_name,cols) from (select table_name,
            jsonb_agg(column_name order by ordinal_position) cols from information_schema.columns
            where table_schema='public' group by table_name)x
            """))
        tables = {}
        for table, fields in sorted(columns.items()):
            selected = ','.join(map(quote, fields))
            tables[table] = json.loads(self.sql(database,
                "select jsonb_build_object('count',count(*),'hashes',coalesce(jsonb_agg(digest order by digest),'[]'::jsonb)) "
                "from (select md5(row_to_json(x)::text) digest from (select " + selected
                + ' from public.' + quote(table) + ')x)y'))
        if baseline:
            require(tables == baseline['tables'], 'Restored legacy rows differ from expected baseline')
        return {'columns': columns, 'tables': tables}

    def restore(self):
        require(not self.work.exists(), 'Use a new rehearsal work directory')
        self.work.mkdir(parents=True, mode=0o700)
        require(self.args.manifest.name == self.args.manifest.as_posix().split('/')[-1], 'Invalid manifest')
        folder = self.args.manifest.resolve().parent
        value = sets.validate_set(folder, sets.read(self.args.manifest))
        baseline = json.loads(self.args.baseline.read_text(encoding='utf-8')) if self.args.baseline else None
        name = 'ybcase_restore_drill_' + secrets.token_hex(6)
        require(self.identity(name) is None, 'Restore target already exists')
        token = 'restore-rehearsal:' + secrets.token_hex(16)
        state = {'format': 1, 'database': name, 'created': False, 'transport': self.args.transport,
                 'home': str(Path(self.args.home).resolve()), 'host': self.args.host, 'port': self.args.port,
                 'user': self.args.user, 'backupManifestSha256': sets.sha(self.args.manifest),
                 'startedAtEpoch': time.time()}
        sets.write_json(self.work / 'state.json', state)
        self.command('createdb', '-O', self.args.user, name)
        self.sql('postgres', 'comment on database ' + quote(name) + " is '" + token + "'")
        state.update(created=True, identity=self.identity(name))
        sets.write_json(self.work / 'state.json', state)
        dump = folder / value['files']['database']['name']
        with dump.open('rb') as stream:
            self.command('pg_restore', '--exit-on-error', '--single-transaction', '--no-owner', '--no-acl',
                         '-d', name, stdin=stream)
        inventory = self.inventory(name, baseline)
        sets.write_json(self.work / 'restored-inventory.json', inventory)
        files = {}
        if value['attachmentRequired']:
            archive_path = folder / value['files']['attachments']['name']
            sets.attachment_count(archive_path)
            with tarfile.open(archive_path) as archive:
                for member in archive:
                    relative = PurePosixPath(member.name)
                    target = self.work / relative
                    require(target.resolve().is_relative_to(self.work), 'Unsafe extracted path')
                    if member.isdir():
                        target.mkdir(parents=True, exist_ok=True)
                    else:
                        target.parent.mkdir(parents=True, exist_ok=True)
                        with target.open('xb') as output, archive.extractfile(member) as source:
                            shutil.copyfileobj(source, output)
                        files[str(relative)] = {'bytes': target.stat().st_size, 'sha256': sets.sha(target)}
        result = {'passed': True, 'database': name, 'originalDatabaseTouched': False,
                  'scope': 'new database restore and attachment inventory',
                  'legacyRowsCompared': baseline is not None, 'tables': len(inventory['tables']),
                  'attachmentFiles': len(files), 'attachments': files,
                  'elapsedSeconds': round(time.time() - state['startedAtEpoch'], 3),
                  'offsiteHostAcceptance': False}
        sets.write_json(self.work / 'result.json', result)
        return result

    def cleanup(self):
        state = sets.read(self.work / 'state.json')
        require(state.get('created') is True and not state.get('cleaned'), 'No owned database to clean')
        for key, expected in (('transport', self.args.transport), ('home', str(Path(self.args.home).resolve())),
                              ('host', self.args.host), ('port', self.args.port), ('user', self.args.user)):
            require(state.get(key) == expected, 'Rehearsal connection identity changed')
        require(self.identity(state['database']) == state['identity'], 'Restore database identity changed')
        count = self.sql('postgres', "select count(*) from pg_stat_activity where datname='" + state['database'] + "'")
        require(count == '0', 'Restore database has active connections; no forced termination')
        self.command('dropdb', state['database'])
        require(self.identity(state['database']) is None, 'Owned database cleanup not verified')
        sets.write_json(self.work / 'state.json', {**state, 'cleaned': True})
        return {'passed': True, 'ownDatabaseRemoved': True, 'evidenceAndFilesRetained': True,
                'originalDatabaseTouched': False}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=('restore', 'cleanup'))
    parser.add_argument('--manifest', type=Path)
    parser.add_argument('--baseline', type=Path)
    parser.add_argument('--work-directory', required=True, type=Path)
    parser.add_argument('--transport', choices=('native', 'docker-compose'), default='docker-compose')
    parser.add_argument('--home', default='/opt/ybcase')
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=5432)
    parser.add_argument('--user', default='hip')
    args = parser.parse_args()
    try:
        if args.phase == 'restore': require(args.manifest is not None, 'An explicit complete manifest is required')
        outcome = getattr(Rehearsal(args), args.phase)()
        print(json.dumps({key: value for key, value in outcome.items() if key != 'attachments'}))
    except (ValueError, OSError, KeyError, tarfile.TarError) as error:
        raise SystemExit('Restore rehearsal refused or failed: ' + str(error))
