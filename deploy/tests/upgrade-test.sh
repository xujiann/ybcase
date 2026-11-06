#!/usr/bin/env bash
# Exercise the real upgrade script; all commands and state stay in a disposable home.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
TMP_ROOT=${YBCASE_TEST_TMP_ROOT:-/mnt/c/Users/drxuj/Temp}
mkdir -p "$TMP_ROOT"
WORK=$(mktemp -d "$TMP_ROOT/ybcase-upgrade-test-XXXXXX")
case "$WORK" in *OneDrive*) exit 1;; esac
trap 'rm -rf -- "$WORK"' EXIT
mkdir -p "$WORK/bin"
cat > "$WORK/bin/sudo" <<'MOCK'
#!/usr/bin/env bash
exec "$@"
MOCK
cat > "$WORK/bin/sleep" <<'MOCK'
#!/usr/bin/env bash
exit 0
MOCK
cat > "$WORK/bin/docker" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$YBCASE_HOME/commands"
case "$*" in
  *'up -d --force-recreate app'*) [ "${MOCK_FAIL:-}" != recreate ] ;;
  *'exec -T app sh -c'*health*)
    if [ "${MOCK_FAIL:-}" = health ]; then printf '{"status":"DOWN"}\n'; else printf '{"status":"UP"}\n'; fi ;;
  *'logs --tail 40 app'*|*'compose ps'*) : ;;
  *) echo "Unexpected command: $*" >&2; exit 99 ;;
esac
MOCK
chmod +x "$WORK/bin/"*
export PATH="$WORK/bin:$PATH"
failures=0
run_case() {
  local name=$1 setup=$2 expected=$3 option=${4:-}
  export YBCASE_HOME="$WORK/$name" MOCK_FAIL=
  mkdir -p "$YBCASE_HOME"
  printf 'new release' > "$YBCASE_HOME/ybcase-server.jar"
  printf 'previous release' > "$YBCASE_HOME/ybcase-server.jar.prev"
  cat > "$YBCASE_HOME/backup.sh" <<'MOCK'
#!/usr/bin/env bash
printf 'backup\n' >> "$YBCASE_HOME/commands"
[ "${MOCK_FAIL:-}" != backup ]
MOCK
  chmod +x "$YBCASE_HOME/backup.sh"
  case "$setup" in
    no-backup) rm "$YBCASE_HOME/backup.sh" ;;
    no-prev) rm "$YBCASE_HOME/ybcase-server.jar.prev" ;;
    same-prev) cp "$YBCASE_HOME/ybcase-server.jar" "$YBCASE_HOME/ybcase-server.jar.prev" ;;
    empty-prev) : > "$YBCASE_HOME/ybcase-server.jar.prev" ;;
    no-jar) rm "$YBCASE_HOME/ybcase-server.jar" ;;
    backup|recreate|health) export MOCK_FAIL=$setup ;;
  esac
  # Only remap the hard-coded production directory when checking an older script.
  sed 's@^cd /opt/ybcase$@cd "${YBCASE_HOME:-/opt/ybcase}"@' "$ROOT/deploy/upgrade.sh" > "$YBCASE_HOME/upgrade.sh"
  local rc=0
  bash "$YBCASE_HOME/upgrade.sh" ${option:+"$option"} > "$YBCASE_HOME/result.log" 2>&1 || rc=$?
  local good=1
  if [ "$expected" = success ]; then
    if [ "$rc" -ne 0 ] || ! grep -q '^==> 升级成功' "$YBCASE_HOME/result.log"; then good=0; fi
  elif [ "$rc" -eq 0 ] || grep -q '^==> 升级成功' "$YBCASE_HOME/result.log"; then good=0
  fi
  if [ "$expected" = before-restart ] && grep -q 'force-recreate' "$YBCASE_HOME/commands" 2>/dev/null; then good=0; fi
  if [ "$setup" = recreate ] && grep -q 'exec -T app' "$YBCASE_HOME/commands"; then good=0; fi
  if [ "$good" = 1 ]; then echo "PASS: $name"; else echo "FAIL: $name (exit $rc)"; cat "$YBCASE_HOME/result.log"; cat "$YBCASE_HOME/commands"; failures=$((failures+1)); fi
}
run_case missing-backup no-backup before-restart
run_case backup-failure backup before-restart
run_case missing-rollback no-prev before-restart
run_case invalid-rollback empty-prev before-restart
run_case missing-artifact no-jar before-restart
run_case unknown-option ok before-restart --not-a-real-option
run_case recreate-failure recreate failure
run_case health-failure health failure
run_case normal-upgrade ok success
run_case caller-backed-up no-backup success --skip-backup
run_case frontend-only-update same-prev success --skip-backup
[ "$failures" -eq 0 ]
