#!/usr/bin/env bash
# Only a prepared, tested and pushed candidate may activate. No overwrite/health-only path.
set -euo pipefail
if [ "$#" -ne 2 ] || [ "$1" != --release-dir ]; then
  echo 'Usage: upgrade.sh --release-dir /opt/ybcase/releases/<reviewed-id>' >&2
  echo 'Stage with package-release.py, run release.py prepare, then provide tests.passed, pilot.passed and git.json.' >&2
  exit 1
fi
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
exec python3 "$SCRIPT_DIR/release.py" --release-dir "$2" activate
