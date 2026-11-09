#!/usr/bin/env bash
# Server-side activation deliberately shares the versioned release gate.
set -euo pipefail
if [ "$#" -ne 2 ] || [ "$1" != --release-dir ]; then
  echo 'Usage: server-build.sh --release-dir /opt/ybcase/releases/<reviewed-id>' >&2
  echo 'Build and test a candidate in an isolated database, package it, and complete prepare/pilot gates first.' >&2
  exit 1
fi
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
exec python3 "$SCRIPT_DIR/release.py" --release-dir "$2" activate
