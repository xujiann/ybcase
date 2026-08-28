#!/usr/bin/env bash
# 版本更新:先备份 → 重启 app 加载新 jar(Flyway 自动迁移) → 健康检查 → 失败给回滚指引。
# 前提:已把新的 ybcase-server.jar 和 dist/ scp 覆盖到 /opt/ybcase/。
# 用法:cd /opt/ybcase && ./upgrade.sh
set -uo pipefail
cd /opt/ybcase

echo "[1/4] 升级前备份(失败可回滚)"
if [ -x ./backup.sh ]; then ./backup.sh || { echo "!! 备份失败,中止升级"; exit 1; }; else echo "  (未找到 backup.sh,跳过)"; fi

echo "[2/4] 校验回滚点"
# 注意：经 server-build.sh 调用时，新 jar 已覆盖到位，此处再 cp 会把"新 jar"存成回滚点，
# 使回滚形同虚设——回滚点由 server-build.sh 在覆盖前留存。
if [ -f ybcase-server.jar.prev ]; then
    if cmp -s ybcase-server.jar ybcase-server.jar.prev; then
        echo "  !! 回滚点与当前 jar 相同，回滚将无效（若非首次部署请检查 server-build.sh）"
    else
        echo "  回滚点就绪：$(stat -c%y ybcase-server.jar.prev 2>/dev/null | cut -d. -f1)"
    fi
else
    # 手工直接调用 upgrade.sh（未经 server-build）时兜底留存
    cp -f ybcase-server.jar ybcase-server.jar.prev 2>/dev/null || true
    echo "  首次部署，已留存当前 jar 作回滚点"
fi

echo "[3/4] 重启 app 加载新 jar(Flyway 自动增量迁移)"
sudo docker compose up -d --force-recreate app
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
  echo "!! 如需回滚到上一版本:"
  echo "   cp ybcase-server.jar.prev ybcase-server.jar && sudo docker compose up -d --force-recreate app"
  echo "   如迁移已改库,还需恢复备份:"
  echo "   sudo docker compose exec -T db pg_restore -U hip -d ybcase --clean --if-exists < backup/db-<最近时间>.dump"
  exit 1
fi
