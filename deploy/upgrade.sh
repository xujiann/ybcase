#!/usr/bin/env bash
# 版本更新:先备份 → 重启 app 加载新 jar(Flyway 自动迁移) → 健康检查 → 失败给回滚指引。
# 前提:已把新的 ybcase-server.jar 和 dist/ scp 覆盖到 /opt/ybcase/。
# 用法:cd /opt/ybcase && ./upgrade.sh
set -euo pipefail
cd "${YBCASE_HOME:-/opt/ybcase}"
if [ "$#" -gt 1 ] || { [ "$#" -eq 1 ] && [ "$1" != --skip-backup ]; }; then
    echo '!! 用法: upgrade.sh [--skip-backup]'; exit 1
fi
# This is an upgrade, not a first-install command. Missing or empty rollback
# artifacts must fail before any restart. Identical JARs are valid for UI/config updates.
if [ ! -s ybcase-server.jar ] || [ ! -s ybcase-server.jar.prev ]; then
    echo '!! 缺少有效的新产物或上一版本回滚点，中止升级'; exit 1
fi

echo "[1/4] 升级前备份(失败可回滚)"
if [ "${1:-}" = "--skip-backup" ]; then
  # server-build.sh 已在覆盖产物之前完成备份（备份耗时随库增长，
  # 夹在"新产物已就位、容器未重建"之间会拉长不一致窗口）
  echo "  备份已由 server-build.sh 在覆盖产物之前完成，跳过"
elif [ -x ./backup.sh ]; then
  ./backup.sh || { echo "!! 备份失败,中止升级"; exit 1; }
else
  echo "!! 未找到可执行的 backup.sh，中止升级"; exit 1
fi

echo "[2/4] 校验回滚点"
# 注意：经 server-build.sh 调用时，新 jar 已覆盖到位，此处再 cp 会把"新 jar"存成回滚点，
# 使回滚形同虚设——回滚点由 server-build.sh 在覆盖前留存。
echo "  回滚点就绪：$(stat -c%y ybcase-server.jar.prev 2>/dev/null | cut -d. -f1)"
if cmp -s ybcase-server.jar ybcase-server.jar.prev; then
    echo '  后端产物相同；前端和配置回滚文件由发布脚本单独保留'
fi
# 注意：回滚点必须由调用方（server-build.sh / push-update.sh）在覆盖之前留存。
# 走到这里时新 jar 已就位，此处再 cp 只会把"新 jar"存成回滚点——所以上面只校验、不刷新。

echo "[3/4] 重启 app 加载新 jar(Flyway 自动增量迁移)"
if ! sudo docker compose up -d --force-recreate app; then
  echo '!! app 重建失败，中止；不能用旧容器的健康结果判定升级成功'; exit 1
fi
# Caddy 静态资源直接读挂载的 dist,无需重启;如需强制刷新可 restart caddy

echo "[4/4] 健康检查(最多等 90 秒)"
ok=0
for i in $(seq 1 30); do
  if sudo docker compose exec -T app sh -c 'wget -qO- http://127.0.0.1:8090/actuator/health 2>/dev/null' | grep -q '"status":"UP"'; then
    ok=1; break
  fi
  sleep 3
done

if [ "$ok" = 1 ]; then
  echo "==> 升级成功,后端已就绪"
  sudo docker compose ps
else
  echo "!! 后端未在 90 秒内就绪,最近日志:"
  sudo docker compose logs --tail 40 app
  echo
  echo "!! 如需回滚到上一版本（顺序不可颠倒：先停 app 再恢复库，否则在途写入会落进即将被 DROP 的表）:"
  echo "   sudo docker compose stop app caddy"
  echo "   sudo docker compose exec -T db pg_restore -U hip -d ybcase --clean --if-exists --single-transaction < backup/db-<STAMP>.dump"
  echo "   cp ybcase-server.jar.prev ybcase-server.jar && sudo docker compose up -d --force-recreate app"
  echo "   FILE 模式还需还原同一 STAMP 的附件包: sudo docker compose exec -T app tar -C /app/data -xf - < backup/att-<STAMP>.tar"
  echo "   sudo docker compose up -d"
  exit 1
fi
