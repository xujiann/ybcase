#!/usr/bin/env bash
# Database and FILE attachments are one backup set. Failures must not report success.
set -euo pipefail
umask 077
YBCASE_HOME=${YBCASE_HOME:-/opt/ybcase}
cd "$YBCASE_HOME"
DIR=${YBCASE_BACKUP_DIR:-$YBCASE_HOME/backup}
STAMP=$(date +%F-%H%M%S-%N)
mkdir -p "$DIR"
[ ! -L "$DIR" ] || { echo '!! backup directory must not be a symlink'; exit 1; }
chmod 700 "$DIR"
exec 9>"$DIR/.backup.lock"
flock -n 9 || { echo '!! another backup is running'; exit 1; }
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
HELPER="$SCRIPT_DIR/backup-set.py"
[ -f "$HELPER" ] || { echo '!! missing backup-set.py'; exit 1; }
DUMP="$DIR/db-$STAMP.dump"
ATT="$DIR/att-$STAMP.tar"
REFS="$DIR/files-$STAMP.json"
PHASE=database
COMPLETE=0
python3 "$HELPER" begin --directory "$DIR" --attempt "$STAMP"
finish() {
    local status=$?
    if [ "$status" -ne 0 ] && [ "$COMPLETE" = 0 ]; then
        python3 "$HELPER" fail --directory "$DIR" --attempt "$STAMP" --phase "$PHASE" || true
        rm -f -- "$DUMP" "$ATT" "$REFS"
    fi
}
trap finish EXIT
if ! docker compose exec -T db pg_dump -U hip -Fc ybcase > "$DUMP"; then
    echo '!! pg_dump failed'; rm -f -- "$DUMP"; exit 1
fi
if [ "$(stat -c%s "$DUMP")" -le 10240 ]; then
    echo '!! database dump is unexpectedly small'; rm -f -- "$DUMP"; exit 1
fi
if ! docker compose exec -T db sh -c 'cat > /tmp/verify.dump && pg_restore --list /tmp/verify.dump >/dev/null && rm -f /tmp/verify.dump' < "$DUMP"; then
    echo '!! database dump validation failed'; rm -f -- "$DUMP"; exit 1
fi
STORAGE=$(docker compose exec -T db psql -U hip -d ybcase -tAc "select upper(btrim(cfg_value)) from sys_config where cfg_key='attachment_storage'" | tr -d '\r\n')
PHASE=attachments
# The configured mode controls new uploads, not where existing attachments live.
# Historical FILE rows must stay in the backup set after switching back to DB.
N_DB=$(docker compose exec -T db psql -U hip -d ybcase -tAc 'select count(*) from case_attachment where file_path is not null' | tr -d '\r\n')
if [[ ! "$N_DB" =~ ^[0-9]+$ ]]; then
    echo '!! invalid external attachment count'; exit 1
fi
docker compose exec -T db psql -U hip -d ybcase -tAc \
    "select coalesce(json_agg(json_build_object('file_path',file_path,'size_bytes',size_bytes) order by id),'[]'::json) from case_attachment where file_path is not null" > "$REFS"
if [ "$STORAGE" = FILE ] || [ "$N_DB" -gt 0 ]; then
    if ! docker compose exec -T app test -d /app/data/attachments; then
        echo '!! FILE storage directory is not mounted'; exit 1
    fi
    if ! docker compose exec -T app tar -C /app/data -cf - attachments > "$ATT"; then
        echo '!! attachment archive failed'; rm -f -- "$ATT"; exit 1
    fi
    N_TAR=$(tar -tf "$ATT" | awk 'substr($0,length($0),1)!="/" {n++} END {print n+0}')
    if [[ ! "$N_TAR" =~ ^[0-9]+$ ]]; then
        echo '!! invalid attachment count'; rm -f -- "$ATT"; exit 1
    fi
fi
PHASE=commit
options=()
if [ "$STORAGE" = FILE ] || [ "$N_DB" -gt 0 ]; then options+=(--attachments); fi
python3 "$HELPER" complete --directory "$DIR" --attempt "$STAMP" --external-count "$N_DB" --external-metadata "$REFS" "${options[@]}"
COMPLETE=1
python3 "$HELPER" prune --directory "$DIR" --keep 14
echo "Backup completed: $DUMP"
