#!/usr/bin/env bash
# Run the real backup script with a fake docker executable in a disposable home.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
TMP_ROOT=${YBCASE_TEST_TMP_ROOT:-/mnt/c/Users/drxuj/Temp}
mkdir -p "$TMP_ROOT"
WORK=$(mktemp -d "$TMP_ROOT/ybcase-backup-test-XXXXXX")
case "$WORK" in *OneDrive*) echo 'OneDrive is forbidden'; exit 1;; esac
trap 'rm -rf -- "$WORK"' EXIT
mkdir -p "$WORK/bin"
cat > "$WORK/bin/docker" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
case "$*" in
  *pg_dump*)
    if [ "${MOCK_FAIL:-}" = dump ]; then exit 42; fi
    head -c 12000 /dev/zero ;;
  *pg_restore*)
    cat >/dev/null
    if [ "${MOCK_FAIL:-}" = verify ]; then exit 43; fi ;;
  *attachment_storage*) printf '%s\n' "${MOCK_STORAGE:-DB}" ;;
  *'test -d'*)
    if [ "${MOCK_FAIL:-}" = mount ]; then exit 44; fi ;;
  *'tar -C'*)
    if [ "${MOCK_FAIL:-}" = tar ]; then exit 45; fi
    tar -C "$MOCK_SOURCE" -cf - attachments ;;
  *'count(*)'*) printf '%s\n' "${MOCK_DB_COUNT:-0}" ;;
  *) echo "Unexpected docker call: $*" >&2; exit 99 ;;
esac
MOCK
chmod +x "$WORK/bin/docker"
export PATH="$WORK/bin:$PATH"
export MOCK_SOURCE="$WORK/source"
mkdir -p "$MOCK_SOURCE/attachments"
run_case() {
    local name=$1 expected=$2
    export YBCASE_HOME="$WORK/$name" YBCASE_BACKUP_DIR="$WORK/$name/backup"
    mkdir -p "$YBCASE_HOME"
    local status=0
    bash "$ROOT/deploy/backup.sh" >"$WORK/$name.log" 2>&1 || status=$?
    if [ "$expected" = success ]; then
        [ "$status" -eq 0 ] && grep -q 'Backup completed' "$WORK/$name.log"
        [ "$(find "$YBCASE_BACKUP_DIR" -name 'db-*.dump' | wc -l)" -eq 1 ]
        if [ "$MOCK_STORAGE" = FILE ] || [ "$MOCK_DB_COUNT" -gt 0 ]; then
            [ "$(find "$YBCASE_BACKUP_DIR" -name 'att-*.tar' | wc -l)" -eq 1 ]
        else
            [ "$(find "$YBCASE_BACKUP_DIR" -name 'att-*.tar' | wc -l)" -eq 0 ]
        fi
    else
        [ "$status" -ne 0 ] && ! grep -q 'Backup completed' "$WORK/$name.log"
    fi
    echo "PASS: $name ($status)"
}
export MOCK_STORAGE=DB MOCK_FAIL= MOCK_DB_COUNT=0
run_case db-without-attachments success
export MOCK_STORAGE=FILE
run_case file-empty success
printf 'attachment' > "$MOCK_SOURCE/attachments/a.txt"
export MOCK_DB_COUNT=1
run_case file-nonempty success
export MOCK_STORAGE=DB
run_case db-with-historical-file success
tar -tf "$YBCASE_BACKUP_DIR"/att-*.tar | grep -qx 'attachments/a.txt'
export MOCK_FAIL=mount
run_case db-historical-missing-mount failure
export MOCK_FAIL= MOCK_DB_COUNT=2
run_case db-historical-missing-attachment failure
export MOCK_DB_COUNT=invalid
run_case db-invalid-external-count failure
export MOCK_STORAGE=FILE MOCK_DB_COUNT=1
export MOCK_FAIL=dump
run_case dump-failure failure
export MOCK_FAIL=verify
run_case validation-failure failure
export MOCK_FAIL=mount
run_case missing-mount failure
export MOCK_FAIL=tar
run_case archive-failure failure
export MOCK_FAIL= MOCK_DB_COUNT=2
run_case missing-attachment failure
export MOCK_STORAGE=DB MOCK_DB_COUNT=0
export YBCASE_HOME="$WORK/rotation" YBCASE_BACKUP_DIR="$WORK/rotation/backup"
mkdir -p "$YBCASE_BACKUP_DIR"
for n in $(seq -w 1 20); do touch "$YBCASE_BACKUP_DIR/db-2000-01-$n.dump"; done
bash "$ROOT/deploy/backup.sh" > "$WORK/rotation.log" 2>&1
[ "$(find "$YBCASE_BACKUP_DIR" -name 'db-*.dump' | wc -l)" -eq 14 ]
grep -q 'Backup completed' "$WORK/rotation.log"
echo 'PASS: rotation keeps latest 14 database dumps without attachment archives'
