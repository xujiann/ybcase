#!/usr/bin/env python3
"""Encrypt complete backup sets for controlled storage, or safely import an encrypted set."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import tempfile

spec = importlib.util.spec_from_file_location('backup_set', Path(__file__).with_name('backup-set.py'))
sets = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sets)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def export_set(args):
    manifest = args.manifest.resolve()
    value = sets.validate_set(manifest.parent, sets.read(manifest))
    destination = args.destination.resolve()
    require(destination.is_dir() and not args.destination.is_symlink()
            and not destination.is_relative_to(manifest.parent)
            and 'onedrive' not in str(destination).lower(), 'Use an existing separate controlled destination')
    recipients = args.recipients.read_text(encoding='utf-8')
    values = [line.strip() for line in recipients.splitlines() if line.strip() and not line.startswith('#')]
    require(1 <= len(values) <= 20 and len(recipients) <= 16384
            and all(re.fullmatch(r'age1[0-9a-z]{50,100}', item) for item in values),
            'Provide 1..20 age public recipients, never private identities')
    name = 'backup-' + value['attemptId'] + '.tar.age'
    target = destination / name
    receipt_path = destination / (name + '.receipt.json')
    require(not target.exists() and not receipt_path.exists(), 'Export already exists')
    descriptor, temporary_name = tempfile.mkstemp(prefix='.backup-encrypted-', dir=destination)
    temporary = Path(temporary_name)
    process = None
    try:
        with os.fdopen(descriptor, 'wb') as output:
            process = subprocess.Popen([args.age_binary, '--encrypt', '-R', str(args.recipients.resolve())],
                                       stdin=subprocess.PIPE, stdout=output, stderr=subprocess.PIPE)
            with tarfile.open(fileobj=process.stdin, mode='w|') as archive:
                archive.add(manifest, arcname=manifest.name, recursive=False)
                for record in value['files'].values():
                    archive.add(manifest.parent / record['name'], arcname=record['name'], recursive=False)
            process.stdin.close()
            error = process.stderr.read()
            require(process.wait() == 0, 'Encryption failed; no export was committed')
            output.flush(); os.fsync(output.fileno())
        with temporary.open('rb') as encrypted:
            prefix = b'age-encryption.org/v1\n'
            require(encrypted.read(len(prefix)) == prefix, 'Unexpected encrypted format')
        sets.validate_set(manifest.parent, value)
        temporary.replace(target)
        receipt = {'format': 1, 'passed': True, 'encryptedFile': name, 'encryptedSha256': sets.sha(target),
                   'encryptedBytes': target.stat().st_size, 'manifest': manifest.name,
                   'manifestSha256': sets.sha(manifest), 'recipientsFileSha256': sets.sha(args.recipients),
                   'offsiteAcceptance': False, 'restoreVerified': False}
        sets.write_json(receipt_path, receipt)
        return receipt
    finally:
        if process and process.poll() is None:
            process.kill(); process.wait()
        if process:
            for stream in (process.stdin, process.stdout, process.stderr):
                if stream and not stream.closed:
                    try: stream.close()
                    except OSError: pass
        if temporary.exists(): temporary.unlink()


def import_set(args):
    receipt = sets.read(args.receipt)
    require(receipt.get('format') == 1 and receipt.get('passed') is True
            and args.encrypted.name == receipt.get('encryptedFile')
            and sets.sha(args.encrypted) == receipt.get('encryptedSha256'), 'Encrypted copy does not match trusted receipt')
    destination = args.destination.resolve()
    require(not destination.exists() and 'onedrive' not in str(destination).lower(), 'Use a new private import directory')
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix='ybcase-import-', dir=destination.parent))
    process = None
    try:
        os.chmod(temporary, 0o700)
        process = subprocess.Popen([args.age_binary, '--decrypt', '-i', str(args.identity.resolve()),
                                    str(args.encrypted.resolve())], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        seen = set()
        with tarfile.open(fileobj=process.stdout, mode='r|') as archive:
            for member in archive:
                require(member.isfile() and re.fullmatch(r'(?:(?:set|files)-[0-9-]+\.json|db-[0-9-]+\.dump|att-[0-9-]+\.tar)', member.name)
                        and member.name not in seen and member.size > 0, 'Unsafe imported backup member')
                seen.add(member.name)
                with (temporary / member.name).open('xb') as output, archive.extractfile(member) as source:
                    os.chmod(temporary / member.name, 0o600)
                    shutil.copyfileobj(source, output)
        process.stdout.close()
        error = process.stderr.read()
        require(process.wait() == 0, 'Decryption authentication failed; import not committed')
        manifest_name = receipt.get('manifest', '')
        require(manifest_name in seen and sets.sha(temporary / manifest_name) == receipt.get('manifestSha256'),
                'Imported manifest differs from trusted receipt')
        value = sets.validate_set(temporary, sets.read(temporary / manifest_name))
        require(seen == {manifest_name} | {item['name'] for item in value['files'].values()}, 'Unexpected imported files')
        temporary.replace(destination)
        return {'passed': True, 'importedManifest': str(destination / manifest_name),
                'integrityVerified': True, 'databaseRestored': False, 'offsiteAcceptance': False}
    finally:
        if process and process.poll() is None:
            process.kill(); process.wait()
        if process:
            for stream in (process.stdin, process.stdout, process.stderr):
                if stream and not stream.closed:
                    try: stream.close()
                    except OSError: pass
        if temporary.exists():
            require(temporary.resolve().is_relative_to(destination.parent)
                    and temporary.name.startswith('ybcase-import-'), 'Temporary import cleanup identity mismatch')
            shutil.rmtree(temporary)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='phase', required=True)
    export = sub.add_parser('export')
    export.add_argument('--manifest', type=Path, required=True)
    export.add_argument('--recipients', type=Path, required=True)
    incoming = sub.add_parser('import')
    incoming.add_argument('--encrypted', type=Path, required=True)
    incoming.add_argument('--receipt', type=Path, required=True)
    incoming.add_argument('--identity', type=Path, required=True)
    for command in (export, incoming):
        command.add_argument('--destination', type=Path, required=True)
        command.add_argument('--age-binary', default='age')
    args = parser.parse_args()
    try:
        print(json.dumps(export_set(args) if args.phase == 'export' else import_set(args)))
    except (ValueError, OSError, KeyError, tarfile.TarError) as error:
        raise SystemExit('Backup transfer refused or failed: ' + str(error))
