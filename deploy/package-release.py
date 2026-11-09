#!/usr/bin/env python3
"""Create a private, checksummed candidate; this command never contacts or changes a server."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import tarfile
import zipfile

FILES = ('docker-compose.yml', 'Caddyfile', 'backup.sh', 'backup-set.py',
         'healthcheck.sh', 'upgrade.sh', 'release.py', 'data-audit.py',
         'backup-transfer.py', 'restore-rehearsal.py', 'capacity-check.py')


def sha(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()


def package(args):
    output = args.output.resolve()
    if 'onedrive' in str(output).lower() or output.exists():
        raise ValueError('Use a new private directory outside OneDrive')
    if not args.jar.is_file() or not (args.dist / 'index.html').is_file():
        raise ValueError('Completed JAR and frontend build required')
    with zipfile.ZipFile(args.jar) as archive:
        versions = [int(match.group(1)) for name in archive.namelist()
                    if (match := re.search(r'(?:^|/)db/migration/V([0-9]+)__[^/]+\.sql$', name))]
        if not versions or max(versions) != args.target_schema:
            raise ValueError('Declared target schema does not match packaged migrations')
    checksums = (args.baseline_jar_sha256, args.baseline_index_sha256)
    if not all(re.fullmatch(r'[0-9a-f]{64}', x) for x in checksums):
        raise ValueError('Exact baseline checksums required')
    if not 1 <= args.baseline_schema <= args.target_schema:
        raise ValueError('Invalid migration interval')
    sources = {'ybcase-server.jar': args.jar.resolve()}
    deploy = Path(__file__).resolve().parent
    sources.update({name: (deploy.parent / 'tools' / name if name == 'capacity-check.py' else deploy / name) for name in FILES})
    for path in args.dist.rglob('*'):
        if path.is_symlink():
            raise ValueError('Frontend symlinks are not allowed')
        if path.is_file():
            if any(part.startswith('.') for part in path.relative_to(args.dist).parts):
                raise ValueError('Hidden frontend files must not enter a release')
            sources['dist/' + path.relative_to(args.dist).as_posix()] = path
    if not any(x.startswith('dist/assets/') for x in sources):
        raise ValueError('Frontend assets missing')
    manifest = {name: sha(path) for name, path in sorted(sources.items())}
    output.mkdir(parents=True, mode=0o700)
    (output / 'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    config = {'format': 1, 'home': '/opt/ybcase', 'baselineSchema': args.baseline_schema,
              'targetSchema': args.target_schema, 'baselineJarSha256': checksums[0],
              'baselineIndexSha256': checksums[1]}
    (output / 'release-config.json').write_text(json.dumps(config, indent=2), encoding='utf-8')
    with tarfile.open(output / 'payload.tar.gz', 'w:gz') as archive:
        for name, path in sorted(sources.items()):
            archive.add(path, arcname=name, recursive=False)
    for name in ('release.py', 'data-audit.py'):
        (output / name).write_bytes(sources[name].read_bytes())
    return {'passed': True, 'files': len(manifest), 'targetSchema': args.target_schema,
            'jarSha256': manifest['ybcase-server.jar'], 'indexSha256': manifest['dist/index.html'],
            'payloadSha256': sha(output / 'payload.tar.gz')}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', required=True, type=Path)
    parser.add_argument('--dist', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--baseline-schema', required=True, type=int)
    parser.add_argument('--target-schema', required=True, type=int)
    parser.add_argument('--baseline-jar-sha256', required=True)
    parser.add_argument('--baseline-index-sha256', required=True)
    try:
        print(json.dumps(package(parser.parse_args())))
    except (ValueError, OSError, zipfile.BadZipFile) as error:
        raise SystemExit('Packaging failed: ' + str(error))
