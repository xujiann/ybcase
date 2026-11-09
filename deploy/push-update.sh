#!/usr/bin/env bash
# Upload only an already packaged candidate; prepare does not switch production.
set -euo pipefail
if [ "$#" -lt 2 ] || [ "$#" -gt 3 ]; then
  echo 'Usage: push-update.sh <local-package-directory> <release-id> [ssh-host]' >&2
  exit 1
fi
PACKAGE=$(cd -- "$1" && pwd)
ID=$2
HOST=${3:-ybcase-hk}
[[ "$ID" =~ ^[A-Za-z0-9][A-Za-z0-9_-]{0,95}$ ]] || { echo 'Invalid release id' >&2; exit 1; }
[[ "$HOST" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]{0,95}$ ]] || { echo 'Use an SSH config host alias' >&2; exit 1; }
for file in release.py data-audit.py manifest.json payload.tar.gz release-config.json; do
  [ -s "$PACKAGE/$file" ] && [ ! -L "$PACKAGE/$file" ] || { echo "Missing candidate file: $file" >&2; exit 1; }
done
REMOTE="/opt/ybcase/releases/$ID"
ssh -o BatchMode=yes "$HOST" "umask 077; mkdir -p /opt/ybcase/releases; mkdir '$REMOTE'"
scp "$PACKAGE/release.py" "$PACKAGE/data-audit.py" "$PACKAGE/manifest.json" \
    "$PACKAGE/payload.tar.gz" "$PACKAGE/release-config.json" "$HOST:$REMOTE/"
ssh -o BatchMode=yes "$HOST" "python3 '$REMOTE/release.py' --release-dir '$REMOTE' prepare"
echo 'Candidate prepared. Complete matching full regression, pilot and pushed-commit gates before activation.'
