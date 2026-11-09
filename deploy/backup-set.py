#!/usr/bin/env python3
"""Commit and verify complete local backup sets. No database credentials are recorded."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import tarfile
import tempfile
import time


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()


def write_json(path, value):
    with tempfile.NamedTemporaryFile('w', encoding='utf-8', dir=path.parent,
                                     prefix='.backup-', delete=False) as stream:
        temporary = Path(stream.name)
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
        stream.flush()
        os.fsync(stream.fileno())
    os.chmod(temporary, 0o600)
    temporary.replace(path)
    if os.name != 'nt':
        descriptor = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)


def read(path):
    require(path.is_file() and not path.is_symlink(), 'Backup metadata missing or unsafe')
    return json.loads(path.read_text(encoding='utf-8'))


def stamp(value):
    require(bool(re.fullmatch(r'[0-9]{4}-[0-9]{2}-[0-9]{2}-[0-9]{6}(?:-[0-9]{6,20})?', value)),
            'Invalid backup attempt identifier')
    return value


def attachment_members(path):
    seen = set()
    files = {}
    with tarfile.open(path) as archive:
        for member in archive:
            item = PurePosixPath(member.name)
            require(not item.is_absolute() and '..' not in item.parts and item.parts
                    and item.parts[0] == 'attachments' and str(item) not in seen
                    and (member.isfile() or member.isdir()), 'Invalid attachment archive member')
            seen.add(str(item))
            if member.isfile():
                # Read every byte so truncated archives are rejected before commit.
                stream = archive.extractfile(member)
                require(stream is not None, 'Unreadable attachment member')
                size = sum(len(block) for block in iter(lambda: stream.read(1024 * 1024), b''))
                require(size == member.size, 'Truncated attachment member')
                files[str(item)] = size
    require('attachments' in seen, 'Attachment root missing')
    return files


def attachment_count(path):
    return len(attachment_members(path))


def external_references(path):
    rows = read(path)
    require(isinstance(rows, list), 'Invalid external attachment metadata')
    result = {}
    for row in rows:
        raw = row.get('file_path')
        require(isinstance(raw, str) and '\\' not in raw and '..' not in PurePosixPath(raw).parts,
                'Unsafe external attachment reference')
        normalized = str(PurePosixPath(raw))
        prefixes = ('/app/data/attachments/', 'data/attachments/')
        prefix = next((value for value in prefixes if normalized.startswith(value)), None)
        require(prefix is not None, 'External attachment is outside the backed-up volume')
        name = 'attachments/' + normalized[len(prefix):]
        size = row.get('size_bytes')
        require(isinstance(size, int) and not isinstance(size, bool) and size > 0, 'Invalid external attachment size')
        require(name not in result or result[name] == size, 'Conflicting attachment references')
        result[name] = size
    return rows, result


def validate_set(folder, value, verify_bytes=True):
    require(value.get('format') == 1 and value.get('status') == 'COMPLETE', 'Incomplete backup set')
    identifier = stamp(value['attemptId'])
    expected = {'database': 'db-' + identifier + '.dump'}
    if value.get('attachmentRequired'):
        expected['attachments'] = 'att-' + identifier + '.tar'
    if value.get('externalReferencesRecorded'):
        expected['references'] = 'files-' + identifier + '.json'
    files = value.get('files')
    require(isinstance(files, dict) and set(files) == set(expected), 'Backup set members missing')
    for kind, name in expected.items():
        record = files[kind]
        require(record.get('name') == name and record.get('bytes', 0) > 0
                and bool(re.fullmatch(r'[0-9a-f]{64}', record.get('sha256', ''))), 'Unsafe backup member')
        path = folder / name
        require(path.is_file() and not path.is_symlink(), 'Backup member missing or unsafe')
        if verify_bytes:
            require(path.stat().st_size == record['bytes'] and sha(path) == record['sha256'],
                    'Backup member checksum mismatch')
    return value


def begin(folder, identifier):
    identifier = stamp(identifier)
    require(not (folder / ('set-' + identifier + '.json')).exists(), 'Backup set already exists')
    write_json(folder / 'last-attempt.json', {'format': 1, 'attemptId': identifier,
               'status': 'RUNNING', 'startedAtEpoch': time.time(), 'phase': 'database'})


def fail(folder, identifier, phase):
    previous = read(folder / 'last-attempt.json')
    require(previous.get('attemptId') == stamp(identifier), 'Another backup attempt owns the status')
    require(previous.get('status') == 'RUNNING', 'Attempt already ended')
    require(bool(re.fullmatch(r'[a-z-]{1,40}', phase)), 'Invalid backup phase')
    write_json(folder / 'last-attempt.json', {**previous, 'status': 'FAILED',
               'phase': phase, 'finishedAtEpoch': time.time()})


def complete(folder, identifier, required, expected_count, references_path=None):
    attempt = read(folder / 'last-attempt.json')
    require(attempt.get('attemptId') == stamp(identifier) and attempt.get('status') == 'RUNNING',
            'Attempt is not the current running backup')
    require(expected_count >= 0, 'Invalid external attachment count')
    required = required or expected_count > 0
    members = {'database': folder / ('db-' + identifier + '.dump')}
    require(members['database'].is_file() and not members['database'].is_symlink()
            and members['database'].stat().st_size > 10240, 'Database dump missing or too small')
    actual_count = 0
    references = None
    if references_path is not None:
        require(references_path.resolve() == (folder / ('files-' + identifier + '.json')).resolve(),
                'Reference metadata must belong to this backup set')
        rows, references = external_references(references_path)
        require(len(rows) == expected_count, 'External attachment metadata changed during backup; retry')
        members['references'] = references_path
    if required:
        members['attachments'] = folder / ('att-' + identifier + '.tar')
        require(members['attachments'].is_file() and not members['attachments'].is_symlink(),
                'Attachment archive missing')
        archived = attachment_members(members['attachments'])
        actual_count = len(archived)
        if references is None:
            require(actual_count >= expected_count, 'Attachment archive incomplete')
        else:
            require(all(archived.get(name) == size for name, size in references.items()),
                    'A referenced attachment is missing or has the wrong size')
    now = time.time()
    value = {'format': 1, 'attemptId': identifier, 'status': 'COMPLETE',
             'startedAtEpoch': attempt['startedAtEpoch'], 'completedAtEpoch': now,
             'completedAtUtc': datetime.fromtimestamp(now, timezone.utc).isoformat(),
             'attachmentRequired': required, 'expectedExternalAttachments': expected_count,
             'externalReferencesRecorded': references_path is not None,
             'archivedAttachmentFiles': actual_count,
             'files': {kind: {'name': path.name, 'bytes': path.stat().st_size, 'sha256': sha(path)}
                       for kind, path in members.items()}}
    validate_set(folder, value)
    target = folder / ('set-' + identifier + '.json')
    require(not target.exists(), 'Backup set commit already exists')
    write_json(target, value)
    write_json(folder / 'last-success.json', {'format': 1, 'attemptId': identifier,
               'manifest': target.name, 'completedAtEpoch': now})
    write_json(folder / 'last-attempt.json', {**attempt, 'status': 'COMPLETE', 'phase': 'complete',
               'manifest': target.name, 'finishedAtEpoch': now})
    return value


def check(folder, max_age_hours=30, max_running_hours=2):
    attempt = read(folder / 'last-attempt.json')
    stamp(attempt['attemptId'])
    require(attempt.get('status') in ('RUNNING', 'COMPLETE'), 'Most recent backup attempt failed')
    now = time.time()
    require(attempt.get('startedAtEpoch', now + 1) <= now + 300, 'Backup attempt timestamp invalid')
    if attempt['status'] == 'RUNNING':
        require(now - attempt['startedAtEpoch'] <= max_running_hours * 3600, 'Backup attempt has stalled')
    success = read(folder / 'last-success.json')
    identifier = stamp(success['attemptId'])
    require(success.get('manifest') == 'set-' + identifier + '.json', 'Success marker mismatch')
    value = validate_set(folder, read(folder / success['manifest']))
    require(value['completedAtEpoch'] == success['completedAtEpoch'], 'Success timestamp mismatch')
    age = now - value['completedAtEpoch']
    require(-300 <= age <= max_age_hours * 3600, 'Complete backup set missing or too old')
    return {'passed': True, 'attemptStatus': attempt['status'], 'manifest': success['manifest'],
            'ageHours': round(age / 3600, 2)}


def prune(folder, keep=14):
    require(keep >= 1, 'At least one complete backup must be retained')
    values = []
    for path in folder.glob('set-*.json'):
        value = validate_set(folder, read(path), verify_bytes=False)
        require(path.name == 'set-' + value['attemptId'] + '.json', 'Manifest name mismatch')
        values.append((value['completedAtEpoch'], path, value))
    for _, path, value in sorted(values, key=lambda item: item[0], reverse=True)[keep:]:
        # Only members claimed by a structurally valid completed set are eligible.
        # Legacy or failed loose files are never guessed into a deletion batch.
        for member in value['files'].values():
            (folder / member['name']).unlink()
        path.unlink()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('begin', 'fail', 'complete', 'check', 'prune'))
    parser.add_argument('--directory', required=True, type=Path)
    parser.add_argument('--attempt')
    parser.add_argument('--phase', default='unknown')
    parser.add_argument('--attachments', action='store_true')
    parser.add_argument('--external-count', type=int, default=0)
    parser.add_argument('--external-metadata', type=Path)
    parser.add_argument('--max-age-hours', type=float, default=30)
    parser.add_argument('--keep', type=int, default=14)
    args = parser.parse_args()
    folder = args.directory.resolve()
    require(folder.is_dir() and not args.directory.is_symlink(), 'Backup directory missing or unsafe')
    if args.command == 'begin': begin(folder, args.attempt)
    elif args.command == 'fail': fail(folder, args.attempt, args.phase)
    elif args.command == 'complete': complete(folder, args.attempt, args.attachments, args.external_count, args.external_metadata)
    elif args.command == 'prune': prune(folder, args.keep)
    else: print(json.dumps(check(folder, args.max_age_hours)))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError, TypeError, tarfile.TarError) as error:
        raise SystemExit('Backup set check failed: ' + str(error))
