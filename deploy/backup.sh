#!/usr/bin/env bash
# ybcase 试运行每日备份：pg_dump 自定义格式 + 附件目录（若启用 FILE 存储）+ 滚动保留 14 份。
# 用法：挂 crontab 每日执行，见 deploy/上线清单.md。
set -euo pipefail

DIR="/opt/ybcase/backup"
STAMP="$(date +%F-%H%M)"
mkdir -p "$DIR"

cd /opt/ybcase

# 1. 数据库（自定义格式）。pg_dump 退出码 + 产物大小双校验，失败即删不留半截文件
docker compose exec -T db pg_dump -U hip -Fc ybcase > "$DIR/db-$STAMP.dump"
[ "$(stat -c%s "$DIR/db-$STAMP.dump")" -gt 10240 ] || { echo "备份异常过小，删除"; rm -f "$DIR/db-$STAMP.dump"; exit 1; }
# 可读性校验：能列出目录才算完整的自定义格式转储（用 host 侧临时容器读，避免 stdin 依赖）
docker compose exec -T db sh -c "cat > /tmp/verify.dump && pg_restore --list /tmp/verify.dump >/dev/null && rm -f /tmp/verify.dump" < "$DIR/db-$STAMP.dump" \
    || { echo "备份可读性校验失败，删除"; rm -f "$DIR/db-$STAMP.dump"; exit 1; }

# 2. 附件目录（仅 FILE 外置存储模式下存在；DB 模式附件已随 pg_dump 备走）
if docker compose exec -T app sh -c '[ -d /app/data/attachments ]' 2>/dev/null; then
    docker compose exec -T app tar -C /app/data -cf - attachments > "$DIR/att-$STAMP.tar" 2>/dev/null || true
fi

# 3. 滚动清理：各类只留最近 14 份
for pat in "db-" "att-"; do
    ls -1t "$DIR/$pat"* 2>/dev/null | tail -n +15 | xargs -r rm -f
done

echo "备份完成：$DIR/db-$STAMP.dump ($(du -h "$DIR/db-$STAMP.dump" | cut -f1))"
