#!/usr/bin/env bash
# ybcase 试运行每日备份：pg_dump 自定义格式 + 附件目录（若启用 FILE 存储）+ 滚动保留 14 份。
# 用法：挂 crontab 每日执行，见 deploy/上线清单.md。
set -euo pipefail

DIR="/opt/ybcase/backup"
STAMP="$(date +%F-%H%M)"
mkdir -p "$DIR"

cd /opt/ybcase

# 1. 数据库（自定义格式）。注意：set -e 下 pg_dump 失败会直接退出，
#    "失败即删"必须显式捕获，否则坏文件留在目录里、还会把好备份挤出滚动窗口。
DUMP="$DIR/db-$STAMP.dump"
if ! docker compose exec -T db pg_dump -U hip -Fc ybcase > "$DUMP"; then
    echo "!! pg_dump 失败，删除半截文件"; rm -f "$DUMP"; exit 1
fi
if [ "$(stat -c%s "$DUMP")" -le 10240 ]; then
    echo "!! 备份异常过小，删除"; rm -f "$DUMP"; exit 1
fi
# 可读性校验：能列出目录才算完整的自定义格式转储
if ! docker compose exec -T db sh -c "cat > /tmp/verify.dump && pg_restore --list /tmp/verify.dump >/dev/null && rm -f /tmp/verify.dump" < "$DUMP"; then
    echo "!! 备份可读性校验失败，删除"; rm -f "$DUMP"; exit 1
fi

# 2. 附件目录（FILE 外置存储模式）。此前用 || true 吞掉全部失败，
#    连"打出空 tar"都算成功——证据没备份到却天天显示正常。
STORAGE=$(docker compose exec -T db psql -U hip -d ybcase -tAc "select cfg_value from sys_config where cfg_key='attachment_storage'" 2>/dev/null | tr -d '' || echo DB)
if [ "$STORAGE" = "FILE" ]; then
    ATT="$DIR/att-$STAMP.tar"
    if ! docker compose exec -T app tar -C /app/data -cf - attachments > "$ATT"; then
        echo "!! 附件打包失败（FILE 模式下附件即执法音像证据）"; rm -f "$ATT"; exit 1
    fi
    # 空 tar 约 10KB 以下：说明卷没挂对或目录为空，此时"备份成功"是假象
    if [ "$(stat -c%s "$ATT")" -le 10240 ]; then
        echo "!! 附件包异常过小（$(stat -c%s "$ATT") 字节）——检查 attachment_dir 是否落在挂载卷上"
        rm -f "$ATT"; exit 1
    fi
fi

# 3. 滚动清理：各类只留最近 14 份
for pat in "db-" "att-"; do
    ls -1t "$DIR/$pat"* 2>/dev/null | tail -n +15 | xargs -r rm -f
done

echo "备份完成：$DIR/db-$STAMP.dump ($(du -h "$DIR/db-$STAMP.dump" | cut -f1))"
