"""Own one fresh PostgreSQL database for guarded CI integration tests."""
import argparse
from dataclasses import dataclass, field
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import tempfile


class LifecycleError(RuntimeError):
    pass


@dataclass(frozen=True)
class Connection:
    host: str
    port: int
    owner: str
    password: str = field(repr=False)

    @classmethod
    def from_environment(cls, environment):
        host = environment.get('PGHOST', '127.0.0.1')
        if host != 'localhost':
            try:
                address = ipaddress.IPv4Address(host)
            except ipaddress.AddressValueError:
                raise LifecycleError('Use an explicit local PostgreSQL IPv4 endpoint') from None
            private = any(address in ipaddress.IPv4Network(network)
                          for network in ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16'))
            if not (address.is_loopback or private):
                raise LifecycleError('Public database endpoints are forbidden')
        try:
            port = int(environment.get('PGPORT', '5432'))
        except ValueError:
            raise LifecycleError('Invalid PostgreSQL port') from None
        owner = environment.get('PGUSER', '')
        password = environment.get('PGPASSWORD', '')
        if not 1 <= port <= 65535 or not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]{0,62}', owner) or not password:
            raise LifecycleError('Explicit PostgreSQL credentials and valid port are required')
        return cls(host, port, owner, password)

    def url(self, database):
        return f'jdbc:postgresql://{self.host}:{self.port}/{database}'


class Psql:
    def __init__(self, connection, environment, runner=subprocess.run):
        self.connection, self.environment, self.runner = connection, environment, runner

    def execute(self, statement, **variables):
        config = self.connection
        command = ['psql', '--no-psqlrc', '--no-password', '--quiet', '--tuples-only', '--no-align',
                   '--host', config.host, '--port', str(config.port), '--username', config.owner,
                   '--dbname', 'postgres', '--set', 'ON_ERROR_STOP=1']
        for key, value in variables.items():
            command.extend(['--set', f'{key}={value}'])
        # Never inherit PGOPTIONS, PGSERVICE or a default database from another job.
        environment = {key: value for key, value in self.environment.items() if not key.startswith('PG')}
        environment.update(PGPASSWORD=config.password, PGCONNECT_TIMEOUT='5')
        try:
            result = self.runner(command, input=statement, text=True, capture_output=True,
                                 env=environment, timeout=20, check=False)
        except (OSError, subprocess.TimeoutExpired):
            raise LifecycleError('PostgreSQL command did not complete; ownership was not adopted') from None
        if result.returncode:
            raise LifecycleError('PostgreSQL command failed; ownership was not adopted')
        return result.stdout.strip()

    def inspect(self, database):
        value = self.execute("""
            select json_build_object('database',d.datname,'oid',d.oid::text,
              'owner',pg_get_userbyid(d.datdba),'marker',shobj_description(d.oid,'pg_database'),
              'login',current_user,
              'connections',(select count(*) from pg_stat_activity a where a.datname=d.datname))
            from pg_catalog.pg_database d where d.datname=:'database';
            """, database=database)
        return json.loads(value) if value else None


def state_path(value):
    original = Path(value)
    if original.is_symlink():
        raise LifecycleError('State files must not be symbolic links')
    result = original.parent.resolve(strict=True) / original.name
    if 'onedrive' in str(result).lower() or not re.fullmatch(r'[A-Za-z0-9_-]+\.json', result.name):
        raise LifecycleError('Use an explicit state JSON file outside OneDrive')
    return result


def save_state(path, record, exclusive=False):
    if exclusive:
        with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w', encoding='utf-8') as output:
            json.dump(record, output, indent=2)
        return
    with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', dir=path.parent, delete=False) as output:
        temporary = Path(output.name)
        json.dump(record, output, indent=2)
    try:
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


class Lifecycle:
    def __init__(self, path, connection, psql):
        self.path, self.connection, self.psql = state_path(path), connection, psql

    def read(self):
        record = json.loads(self.path.read_text(encoding='utf-8'))
        config = self.connection
        if (record.get('format') != 1
                or not re.fullmatch(r'ybcase_authowner_test_20261028_(?!00000000)[a-f0-9]{8}', record.get('database', ''))
                or not re.fullmatch(r'ybcase-test:[a-f0-9]{32}', record.get('marker', ''))
                or (record.get('host'), record.get('port'), record.get('owner')) != (config.host, config.port, config.owner)
                or record.get('jdbcUrl') != config.url(record['database'])
                or record.get('phase') not in ('planned', 'created', 'authorized', 'cleaned')):
            raise LifecycleError('Recorded database authorization does not match this endpoint')
        if record['phase'] != 'planned' and not re.fullmatch(r'[1-9][0-9]{0,9}', str(record.get('oid', ''))):
            raise LifecycleError('Recorded database OID is invalid')
        return record

    def verify(self, record, actual, idle=False):
        if actual is None or any(str(actual.get(key)) != str(record[key])
                                 for key in ('database', 'oid', 'owner', 'marker')):
            raise LifecycleError('Actual database name, OID, owner or marker differs; no database removed')
        if actual.get('login') != record['owner'] or (idle and actual.get('connections') != 0):
            raise LifecycleError('Database login or active-connection boundary differs; no database removed')

    def provision(self, github_environment):
        nonce = secrets.token_hex(16)
        while nonce.startswith('00000000'):
            nonce = secrets.token_hex(16)
        database = 'ybcase_authowner_test_20261028_' + nonce[:8]
        config = self.connection
        record = {'format': 1, 'database': database, 'oid': None, 'owner': config.owner,
                  'marker': 'ybcase-test:' + nonce, 'host': config.host, 'port': config.port,
                  'jdbcUrl': config.url(database), 'phase': 'planned'}
        save_state(self.path, record, exclusive=True)
        # A failed CREATE (including a name collision) never reaches COMMENT or adopts that database.
        self.psql.execute('create database :"database" owner :"owner" template template0;',
                          database=database, owner=config.owner)
        actual = self.psql.inspect(database)
        if (actual is None or actual.get('owner') != config.owner or actual.get('login') != config.owner
                or actual.get('marker') is not None or actual.get('connections') != 0
                or actual.get('database') != database or not re.fullmatch(r'[1-9][0-9]{0,9}', str(actual.get('oid', '')))):
            raise LifecycleError('New database identity was not confirmed; no marker written')
        record.update(oid=str(actual['oid']), phase='created')
        save_state(self.path, record)
        self.psql.execute('comment on database :"database" is :\'marker\';', database=database, marker=record['marker'])
        self.verify(record, self.psql.inspect(database), idle=True)
        record['phase'] = 'authorized'
        save_state(self.path, record)
        self.export(record, github_environment)
        return record

    @staticmethod
    def export(record, destination):
        target = Path(destination)
        if target.is_symlink() or 'onedrive' in str(target.resolve()).lower():
            raise LifecycleError('Unsafe GitHub environment output')
        values = {'SPRING_DATASOURCE_URL': record['jdbcUrl'], 'SPRING_DATASOURCE_USERNAME': record['owner'],
                  'YBCASE_TEST_DATABASE_URL': record['jdbcUrl'], 'YBCASE_TEST_DATABASE_OID': record['oid'],
                  'YBCASE_TEST_DATABASE_OWNER': record['owner'], 'YBCASE_TEST_DATABASE_MARKER': record['marker']}
        if any('\n' in str(value) or '\r' in str(value) for value in values.values()):
            raise LifecycleError('Invalid multiline environment value')
        with target.open('a', encoding='utf-8', newline='\n') as output:
            output.write(''.join(f'{key}={value}\n' for key, value in values.items()))

    def check(self):
        record = self.read()
        if record['phase'] != 'authorized':
            raise LifecycleError('Only a successfully authorized database may be checked')
        self.verify(record, self.psql.inspect(record['database']))
        return record

    def cleanup(self):
        if not self.path.exists():
            return {'phase': 'not-created'}
        record = self.read()
        if record['phase'] not in ('authorized', 'created', 'cleaned'):
            raise LifecycleError('No confirmed creation receipt; refusing to adopt or remove a database')
        actual = self.psql.inspect(record['database'])
        if actual is not None:
            if record['phase'] == 'cleaned':
                raise LifecycleError('A database reappeared after cleanup; refusing to adopt it')
            self.verify(record, actual, idle=True)
            # No FORCE, connection termination, fixed-name fallback or unrelated database operation.
            self.psql.execute('drop database :"database";', database=record['database'])
            if self.psql.inspect(record['database']) is not None:
                raise LifecycleError('The owned database still exists')
        record['phase'] = 'cleaned'
        save_state(self.path, record)
        return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('provision', 'check', 'cleanup'))
    parser.add_argument('--state', required=True)
    parser.add_argument('--github-env', default=os.environ.get('GITHUB_ENV'))
    args = parser.parse_args()
    if args.action == 'provision' and not args.github_env:
        parser.error('provision requires --github-env or GITHUB_ENV')
    try:
        connection = Connection.from_environment(os.environ)
        lifecycle = Lifecycle(args.state, connection, Psql(connection, os.environ))
        record = lifecycle.provision(args.github_env) if args.action == 'provision' else getattr(lifecycle, args.action)()
        print(json.dumps({'passed': True, 'action': args.action, 'database': record.get('database'),
                          'oid': record.get('oid'), 'phase': record['phase']}))
    except (LifecycleError, OSError, ValueError) as error:
        parser.exit(1, f'CI database operation refused: {error}\n')


if __name__ == '__main__':
    main()
