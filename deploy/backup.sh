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
# 与 AttachmentController 的 equalsIgnoreCase 同口径（参数是自由文本，填 "file" 也算 FILE 模式）
STORAGE=$(docker compose exec -T db psql -U hip -d ybcase -tAc "select upper(btrim(cfg_value)) from sys_config where cfg_key='attachment_storage'" 2>/dev/null | tr -d '' || echo DB)
if [ "$STORAGE" = "FILE" ]; then
    # 挂载错位要显式检查，不能靠 tar 大小推断
    if ! docker compose exec -T app test -d /app/data/attachments; then
        echo "!! /app/data/attachments 不存在——attachment_dir 未落在挂载卷上，FILE 模式附件不会被备份"; exit 1
    fi
    ATT="$DIR/att-$STAMP.tar"
    if ! docker compose exec -T app tar -C /app/data -cf - attachments > "$ATT"; then
        echo "!! 附件打包失败（FILE 模式下附件即执法音像证据）"; rm -f "$ATT"; exit 1
    fi
    # 按"库里有多少外置附件、包里有多少文件"校验，而不是猜字节数：
    # GNU tar 按 10240 字节 record 补齐，空目录或几个小文件恰好就是 10240，按大小判会每夜误报
    N_DB=$(docker compose exec -T db psql -U hip -d ybcase -tAc "select count(*) from case_attachment where file_path is not null" 2>/dev/null | tr -d '' || echo 0)
    N_TAR=$(tar -tf "$ATT" 2>/dev/null | grep -vc '/$' || echo 0)
    if [ "${N_DB:-0}" -gt 0 ] && [ "${N_TAR:-0}" -lt "$N_DB" ]; then
        echo "!! 附件包不完整：库内外置附件 $N_DB 条，包内文件 $N_TAR 个——检查 attachment_dir 是否落在挂载卷上"
        rm -f "$ATT"; exit 1
    fi
    [ "${N_DB:-0}" -eq 0 ] && echo "  （FILE 模式下尚无外置附件，附件包为空目录属正常）"
fi

# 3. 滚动清理：各类只留最近 14 份
for pat in "db-" "att-"; do
    ls -1t "$DIR/$pat"* 2>/dev/null | tail -n +15 | xargs -r rm -f
done

echo "备份完成：$DIR/db-$STAMP.dump ($(du -h "$DIR/db-$STAMP.dump" | cut -f1))"
