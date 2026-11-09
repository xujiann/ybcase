#!/usr/bin/env bash
# Legacy entry points must fail closed and delegate only explicit reviewed candidates.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
TMP_ROOT=${YBCASE_TEST_TMP_ROOT:-/mnt/c/Users/drxuj/Temp}
mkdir -p "$TMP_ROOT"
WORK=$(mktemp -d "$TMP_ROOT/ybcase-upgrade-gate-test-XXXXXX")
case "$WORK" in "$TMP_ROOT"/ybcase-upgrade-gate-test-*) ;; *) exit 1;; esac
trap 'rm -rf -- "$WORK"' EXIT
mkdir -p "$WORK/bin"
cat > "$WORK/bin/python3" <<'MOCK'
#!/usr/bin/env bash
printf '%s\n' "$@" > "$MOCK_CALL"
exit "${MOCK_EXIT:-0}"
MOCK
chmod +x "$WORK/bin/python3"
export PATH="$WORK/bin:$PATH" MOCK_CALL="$WORK/command"
for entry in upgrade.sh server-build.sh; do
  for option in empty skip-backup no-pull prebuilt; do
    rm -f "$MOCK_CALL"
    args=()
    [ "$option" = empty ] || args+=(--"$option")
    rc=0
    bash "$ROOT/deploy/$entry" "${args[@]}" > "$WORK/result.log" 2>&1 || rc=$?
    [ "$rc" -ne 0 ] && [ ! -e "$MOCK_CALL" ]
    echo "PASS: $entry rejects $option bypass"
  done
  bash "$ROOT/deploy/$entry" --release-dir /opt/ybcase/releases/fixture > "$WORK/result.log"
  grep -qx '/opt/ybcase/releases/fixture' "$MOCK_CALL"
  grep -qx activate "$MOCK_CALL"
  grep -q '/release.py$' "$MOCK_CALL"
  echo "PASS: $entry delegates gated activation"
  export MOCK_EXIT=42
  rc=0
  bash "$ROOT/deploy/$entry" --release-dir /opt/ybcase/releases/fixture > "$WORK/result.log" 2>&1 || rc=$?
  [ "$rc" = 42 ]
  unset MOCK_EXIT
  echo "PASS: $entry propagates gate failure"
done
