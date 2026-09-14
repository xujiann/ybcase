#!/usr/bin/env bash
# ybcase 最简健康告警：由 crontab 每 5 分钟执行（见 deploy/上线清单.md「日常运维」）。
# 检查四件事：①公网入口（Caddy→app 全链路）②容器内 /actuator/health ③磁盘 ④昨夜备份是否成功。
# 状态变化时告警（正常→异常、异常→恢复各发一次；持续异常每 6 小时重复一次），避免每 5 分钟刷屏。
# 告警渠道可选：.env 里配 ALERT_WEBHOOK（企业微信/钉钉/飞书群机器人 URL）+ ALERT_WEBHOOK_TYPE（wecom|dingtalk|feishu），
# 未配置时只写 /opt/ybcase/healthcheck.log——至少 `tail` 一眼能看出什么时候挂过。
set -uo pipefail
cd /opt/ybcase || exit 1

LOG=/opt/ybcase/healthcheck.log
STATE=/opt/ybcase/.health-state        # 上次结果：OK 或 FAIL:<epoch of last alert>
# 不能 source .env：口令里一个 $ 就让 set -u 把整个脚本静默杀死（cron 下无人知晓），
# 含 ( 或引号则其后所有行丢失。只按键名逐个提取，值原样取用（compose 对同一文件也是按字面处理）
envval() { sed -n "s/^[[:space:]]*$1=//p" .env 2>/dev/null | tail -1 | sed -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'$/\1/"; }
DOMAIN="$(envval DEMO_DOMAIN)"
ALERT_WEBHOOK="$(envval ALERT_WEBHOOK)"
ALERT_WEBHOOK_TYPE="$(envval ALERT_WEBHOOK_TYPE)"
DISK_LIMIT="$(envval ALERT_DISK_PERCENT)"; DISK_LIMIT="${DISK_LIMIT:-85}"
BACKUP_MAX_AGE_H="$(envval ALERT_BACKUP_MAX_AGE_HOURS)"; BACKUP_MAX_AGE_H="${BACKUP_MAX_AGE_H:-30}"

problems=()

# ① 公网入口：走真实域名与 TLS，和用户看到的一致。用 config/public（免登录、经反代到 app）
if [ -n "$DOMAIN" ]; then
    # curl 传输失败时 -w 仍会打出 000，不能再 || echo 000（否则变成 000000）
    code=$(curl -sS -m 15 -o /dev/null -w '%{http_code}' "https://$DOMAIN/api/config/public" 2>/dev/null) || code=000
    [ "$code" = "200" ] || problems+=("公网入口 https://$DOMAIN/api/config/public 返回 $code")
fi

# ② 容器内健康端点（DB 连接、磁盘、进程存活）
health=$(sudo docker compose exec -T app curl -sS -m 10 http://127.0.0.1:8090/actuator/health 2>/dev/null || echo '')
case "$health" in
    *'"status":"UP"'*) ;;
    *) problems+=("app 健康端点异常：${health:-无响应（容器未运行或未就绪）}") ;;
esac

# ③ 容器状态（db/app/caddy 任一非 running）
for svc in db app caddy; do
    st=$(sudo docker compose ps --format '{{.Service}} {{.State}}' 2>/dev/null | awk -v s="$svc" '$1==s{print $2}')
    [ "$st" = "running" ] || problems+=("容器 $svc 状态 ${st:-缺失}")
done

# ④ 磁盘
used=$(df -P / | awk 'NR==2{gsub("%","",$5); print $5}')
[ "${used:-0}" -lt "$DISK_LIMIT" ] || problems+=("根分区已用 ${used}%（阈值 ${DISK_LIMIT}%）")

# ⑤ 备份新鲜度：最近一份 db-*.dump 须在 N 小时内且非空（备份脚本失败会删半截文件，所以"没有新文件"即失败）
latest=$(ls -1t backup/db-*.dump 2>/dev/null | head -1)
if [ -z "$latest" ]; then
    problems+=("backup/ 下没有任何数据库备份")
else
    age_h=$(( ( $(date +%s) - $(stat -c %Y "$latest") ) / 3600 ))
    [ "$age_h" -le "$BACKUP_MAX_AGE_H" ] || problems+=("最近备份 $latest 已 ${age_h} 小时未更新（昨夜备份可能失败，查 backup/backup.log）")
fi

now=$(date '+%F %T')
prev=$(cat "$STATE" 2>/dev/null || echo "OK")

send_alert() {   # $1 = 文本
    echo "[$now] ALERT: $1" >> "$LOG"
    [ -n "${ALERT_WEBHOOK:-}" ] || return 0
    local text="[ybcase ${DOMAIN:-$(hostname)}] $1"
    local payload
    case "${ALERT_WEBHOOK_TYPE:-wecom}" in
        feishu)   payload=$(printf '{"msg_type":"text","content":{"text":%s}}' "$(printf '%s' "$text" | python3 -c 'import json,sys;print(json.dumps(sys.stdin.read()))')") ;;
        *)        payload=$(printf '{"msgtype":"text","text":{"content":%s}}' "$(printf '%s' "$text" | python3 -c 'import json,sys;print(json.dumps(sys.stdin.read()))')") ;;
    esac
    curl -sS -m 10 -H 'Content-Type: application/json' -d "$payload" "$ALERT_WEBHOOK" >/dev/null 2>&1 \
        || echo "[$now] webhook 发送失败" >> "$LOG"
}

if [ "${#problems[@]}" -eq 0 ]; then
    if [ "$prev" != "OK" ]; then
        send_alert "已恢复正常（$now）"
    fi
    echo "OK" > "$STATE"
    echo "[$now] OK" >> "$LOG"
else
    msg=$(printf '%s；' "${problems[@]}")
    last_alert=0
    case "$prev" in FAIL:*) last_alert=${prev#FAIL:} ;; esac
    if [ "$prev" = "OK" ] || [ $(( $(date +%s) - last_alert )) -ge 21600 ]; then
        send_alert "异常：$msg"
        echo "FAIL:$(date +%s)" > "$STATE"
    else
        echo "[$now] FAIL(已告警): $msg" >> "$LOG"
    fi
    exit 1
fi
