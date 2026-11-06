#!/usr/bin/env bash
# Database and FILE attachments are one backup set. Failures must not report success.
set -euo pipefail
YBCASE_HOME=${YBCASE_HOME:-/opt/ybcase}
cd "$YBCASE_HOME"
DIR=${YBCASE_BACKUP_DIR:-$YBCASE_HOME/backup}
STAMP=$(date +%F-%H%M%S)
mkdir -p "$DIR"
DUMP="$DIR/db-$STAMP.dump"
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
# The configured mode controls new uploads, not where existing attachments live.
# Historical FILE rows must stay in the backup set after switching back to DB.
N_DB=$(docker compose exec -T db psql -U hip -d ybcase -tAc 'select count(*) from case_attachment where file_path is not null' | tr -d '\r\n')
if [[ ! "$N_DB" =~ ^[0-9]+$ ]]; then
    echo '!! invalid external attachment count'; exit 1
fi
if [ "$STORAGE" = FILE ] || [ "$N_DB" -gt 0 ]; then
    if ! docker compose exec -T app test -d /app/data/attachments; then
        echo '!! FILE storage directory is not mounted'; exit 1
    fi
    ATT="$DIR/att-$STAMP.tar"
    if ! docker compose exec -T app tar -C /app/data -cf - attachments > "$ATT"; then
        echo '!! attachment archive failed'; rm -f -- "$ATT"; exit 1
    fi
    N_TAR=$(tar -tf "$ATT" | awk 'substr($0,length($0),1)!="/" {n++} END {print n+0}')
    if [[ ! "$N_TAR" =~ ^[0-9]+$ ]]; then
        echo '!! invalid attachment count'; rm -f -- "$ATT"; exit 1
    fi
    if [ "$N_TAR" -lt "$N_DB" ]; then
        echo "!! incomplete attachments: database=$N_DB archive=$N_TAR"; rm -f -- "$ATT"; exit 1
    fi
fi
shopt -s nullglob
for pat in db- att-; do
    files=("$DIR/$pat"*)
    if [ "${#files[@]}" -gt 14 ]; then
        index=0
        while IFS= read -r file; do
            index=$((index+1))
            if [ "$index" -gt 14 ]; then rm -f -- "$file"; fi
        done < <(printf '%s\n' "${files[@]}" | sort -r)
    fi
done
echo "Backup completed: $DUMP"
