#!/usr/bin/env python3
"""Measure a scoped isolated read workload against explicit latency/error targets."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import io
import ipaddress
import json
import math
import os
from pathlib import Path
import re
import statistics
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile

MAX_BYTES = 26 * 1024 * 1024
SAFE_PATH = re.compile(r'^/(?:bureau/(?:cases(?:/[1-9][0-9]*)?|my/tasks|stats/(?:overview|supervision|report|penalties/download|enforcement-work/download|interviews/download)|report-versions/batches(?:/[1-9][0-9]*(?:/rows|/changes|/download)?)?|integrations/readiness)|agency/my/tasks)(?:\?[A-Za-z0-9_.%=&-]+)?$')


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def validate(base, proof, paths):
    parsed = urllib.parse.urlsplit(base)
    if parsed.scheme != 'http' or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError('Use an isolated HTTP endpoint without credentials in its URL')
    try:
        local = parsed.hostname == 'localhost' or ipaddress.ip_address(parsed.hostname).is_private
    except ValueError:
        local = False
    if not local or not parsed.port or parsed.port in (80, 443, 8090) or parsed.path.rstrip('/') != '/api':
        raise ValueError('Production/public/default application endpoints are refused')
    if proof.get('isolated') is not True or proof.get('baseUrl') != base.rstrip('/'):
        raise ValueError('Matching isolation proof required')
    if not re.fullmatch(r'ybcase_(?:authowner_test_20261028_[0-9a-f]{8}|release_drill_[0-9]+)', proof.get('database', '')):
        raise ValueError('Dedicated test database identity required')
    if not re.fullmatch(r'[0-9a-f]{64}', proof.get('jarSha256', '')) or proof.get('caseCount', 0) < 1:
        raise ValueError('Nonempty fixture and exact build identity required')
    age = time.time() - proof.get('checkedAtEpoch', 0)
    if not -300 <= age <= 3600:
        raise ValueError('Refresh the isolation identity evidence before load testing')
    if not paths or any(not SAFE_PATH.fullmatch(path) or '..' in path for path in paths):
        raise ValueError('Only scoped read/report paths are allowed')


def measure(args):
    proof = json.loads(args.isolation_proof.read_text(encoding='utf-8'))
    paths = json.loads(args.paths.read_text(encoding='utf-8'))
    validate(args.base_url, proof, paths)
    if not 1 <= args.workers <= 32 or not 1 <= args.requests <= 100000:
        raise ValueError('Workers must be 1..32 and request count 1..100000')
    if args.p95_ms <= 0 or not 0 <= args.max_error_rate <= 1:
        raise ValueError('Explicit positive latency and valid error-rate targets required')
    token = os.environ.get('YBCASE_LOAD_TOKEN', '')
    if not token or any(ord(c) < 32 for c in token):
        raise ValueError('Set YBCASE_LOAD_TOKEN from the isolated environment')
    def request(index):
        path = paths[index % len(paths)]
        started = time.perf_counter()
        passed, status, size = False, 0, 0
        try:
            req = urllib.request.Request(args.base_url.rstrip('/') + path,
                                         headers={'Authorization': 'Bearer ' + token})
            with urllib.request.build_opener(NoRedirect()).open(req, timeout=args.timeout) as response:
                status = response.status
                body = response.read(MAX_BYTES + 1)
                size = len(body)
                if status == 200 and size <= MAX_BYTES:
                    if '/download' in path:
                        with zipfile.ZipFile(io.BytesIO(body)) as archive:
                            passed = archive.testzip() is None and 'xl/workbook.xml' in archive.namelist()
                    else:
                        passed = json.loads(body).get('code') == 0
        except (OSError, ValueError, zipfile.BadZipFile, urllib.error.URLError):
            pass
        return {'path': path, 'passed': passed, 'status': status, 'bytes': size,
                'milliseconds': round((time.perf_counter() - started) * 1000, 3)}
    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        rows = list(pool.map(request, range(args.requests)))
    elapsed = time.perf_counter() - started
    latency = sorted(row['milliseconds'] for row in rows)
    errors = sum(not row['passed'] for row in rows)
    p95 = latency[math.ceil(len(latency) * .95) - 1]
    error_rate = errors / len(rows)
    return {'passed': p95 <= args.p95_ms and error_rate <= args.max_error_rate,
            'scope': 'isolated read and report workload; no production capacity certification',
            'database': proof['database'], 'jarSha256': proof['jarSha256'], 'fixtureCaseCount': proof['caseCount'],
            'workers': args.workers, 'requests': len(rows), 'errors': errors, 'errorRate': error_rate,
            'elapsedSeconds': round(elapsed, 3), 'requestsPerSecond': round(len(rows) / elapsed, 2),
            'averageMs': round(statistics.mean(latency), 2), 'p95Ms': p95,
            'targets': {'p95Ms': args.p95_ms, 'maxErrorRate': args.max_error_rate},
            'results': rows}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', required=True)
    parser.add_argument('--isolation-proof', required=True, type=Path)
    parser.add_argument('--paths', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--workers', type=int, default=4)
    parser.add_argument('--requests', type=int, default=100)
    parser.add_argument('--timeout', type=int, default=30)
    parser.add_argument('--p95-ms', type=float, required=True)
    parser.add_argument('--max-error-rate', type=float, required=True)
    args = parser.parse_args()
    try:
        result = measure(args)
        args.output.write_text(json.dumps(result, indent=2), encoding='utf-8')
        print(json.dumps({key: value for key, value in result.items() if key != 'results'}))
        raise SystemExit(0 if result['passed'] else 1)
    except (ValueError, OSError, KeyError) as error:
        raise SystemExit('Capacity check refused or failed: ' + str(error))
