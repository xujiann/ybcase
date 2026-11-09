#!/usr/bin/env bash
# The uploader only stages a new private release; failures never activate production.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
TMP_ROOT=${YBCASE_TEST_TMP_ROOT:-/mnt/c/Users/drxuj/Temp}
mkdir -p "$TMP_ROOT"
WORK=$(mktemp -d "$TMP_ROOT/ybcase-upload-gate-test-XXXXXX")
case "$WORK" in "$TMP_ROOT"/ybcase-upload-gate-test-*) ;; *) exit 1;; esac
trap 'rm -rf -- "$WORK"' EXIT
mkdir -p "$WORK/bin" "$WORK/package"
for f in release.py data-audit.py manifest.json payload.tar.gz release-config.json; do
  printf candidate > "$WORK/package/$f"
done
cat > "$WORK/bin/ssh" <<'MOCK'
#!/usr/bin/env bash
printf 'ssh %s\n' "$*" >> "$MOCK_CALLS"
[ "${MOCK_FAIL:-}" != ssh ]
MOCK
cat > "$WORK/bin/scp" <<'MOCK'
#!/usr/bin/env bash
printf 'scp %s\n' "$*" >> "$MOCK_CALLS"
[ "${MOCK_FAIL:-}" != upload ]
MOCK
chmod +x "$WORK/bin/"*
export PATH="$WORK/bin:$PATH" MOCK_CALLS="$WORK/commands"
for scenario in success ssh upload invalid-id missing-file; do
  export MOCK_FAIL="$scenario"
  : > "$MOCK_CALLS"
  id=fixture
  [ "$scenario" != invalid-id ] || id='../production'
  if [ "$scenario" = missing-file ]; then mv "$WORK/package/payload.tar.gz" "$WORK/payload.saved"; fi
  rc=0
  bash "$ROOT/deploy/push-update.sh" "$WORK/package" "$id" > "$WORK/result.log" 2>&1 || rc=$?
  if [ "$scenario" = success ]; then
    [ "$rc" = 0 ]
    grep -q 'prepare' "$MOCK_CALLS"
    ! grep -Eq 'activate|force-recreate|rm -rf|compose up' "$MOCK_CALLS"
  else
    [ "$rc" -ne 0 ]
    ! grep -q 'prepare' "$MOCK_CALLS"
    if [ "$scenario" = invalid-id ] || [ "$scenario" = missing-file ]; then [ ! -s "$MOCK_CALLS" ]; fi
  fi
  [ "$scenario" != missing-file ] || mv "$WORK/payload.saved" "$WORK/package/payload.tar.gz"
  echo "PASS: gated-uploader-$scenario"
done
