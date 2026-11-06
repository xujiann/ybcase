#!/usr/bin/env bash
# 【本机(开发机)运行,不是服务器】一键更新香港试运行环境。
# 前提:已配好 SSH 免密别名 ybcase-hk（见 deploy/上线清单.md 的 SSH 密钥一节）。
# 用法:在仓库根目录  bash deploy/push-update.sh
#
# 与 server-build.sh 同一套落盘规则（此前这里仍是 scp 原地覆盖 + 事后备份）：
#  - jar 被 bind mount 进运行中的容器且被惰性读取，必须换 inode（上传到 .new 再 mv），
#    否则"覆盖完成→容器重建"之间旧 JVM 会从新 jar 的旧偏移读字节（ZipException 而健康检查仍 UP）；
#  - 回滚点 .prev 必须在覆盖之前由本脚本显式留存，upgrade.sh 只校验不再兜底创建；
#  - 备份放在覆盖之前，不夹在"新产物已就位、容器未重建"之间。
set -euo pipefail
cd "$(dirname "$0")/.."   # 切到仓库根

HOST=ybcase-hk
if [ "$#" -gt 1 ] || { [ "$#" -eq 1 ] && [ "$1" != --prebuilt ]; }; then
  echo 'Usage: push-update.sh [--prebuilt]'; exit 1
fi

echo "[1/4] 本机构建后端 + 前端"
if [ "${1:-}" = --prebuilt ]; then
  echo '  使用已经完成测试的本次构建产物'
else
  mvn -q package
  npm test --prefix frontend
  npm run build --prefix frontend
fi
JAR=server/target/ybcase-server-1.2.0.jar
[ -s "$JAR" ] && [ -s frontend/dist/index.html ] || { echo '!! 缺少构建产物'; exit 1; }

echo "[2/4] 上传到 $HOST:/opt/ybcase/（新 inode，不碰运行中的文件）"
scp "$JAR" "$HOST:/opt/ybcase/ybcase-server.jar.new"
ssh "$HOST" "rm -rf /opt/ybcase/dist.new"
scp -r frontend/dist "$HOST:/opt/ybcase/dist.new"          # 目标不存在时 scp -r 才是整目录复制
scp deploy/upgrade.sh deploy/backup.sh deploy/healthcheck.sh "$HOST:/opt/ybcase/"
scp deploy/docker-compose.yml "$HOST:/opt/ybcase/docker-compose.yml.new"
scp deploy/Caddyfile "$HOST:/opt/ybcase/Caddyfile.new"

echo "[3/4] 远程：升级前备份 → 留回滚点 → 原子替换 jar → 原地更新 dist 内容（保住 Caddy 挂载的 inode）"
ssh "$HOST" 'set -e; cd /opt/ybcase; chmod +x upgrade.sh backup.sh healthcheck.sh
  docker compose --env-file .env -f docker-compose.yml.new config --quiet
  ./backup.sh
  [ -s ybcase-server.jar ] || { echo "!! 缺少上一版本，不能执行升级"; exit 1; }
  rollback="rollback/$(date +%F-%H%M%S)"; mkdir -p "$rollback"
  cp -p ybcase-server.jar docker-compose.yml Caddyfile "$rollback/"
  tar -czf "$rollback/dist.tar.gz" dist
  cp -f ybcase-server.jar ybcase-server.jar.prev
  mv -f ybcase-server.jar.new ybcase-server.jar
  mv -f docker-compose.yml.new docker-compose.yml
  # Preserve the inode of the file bind-mounted in the running Caddy container.
  cat Caddyfile.new > Caddyfile
  mkdir -p dist
  # Keep previous hashed chunks for already-open pages; switch index.html last.
  find dist.new -mindepth 1 -maxdepth 1 ! -name index.html -exec cp -r -t dist/ {} +
  cp dist.new/index.html dist/index.html.new
  mv -f dist/index.html.new dist/index.html
  echo "回滚产物: $rollback"'

echo "[4/4] 远程执行升级(重启加载新jar→健康检查；备份已在上一步完成)"
ssh "$HOST" 'set -e; cd /opt/ybcase; ./upgrade.sh --skip-backup
  docker compose up -d
  docker compose exec -T caddy caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile'

echo "==> 完成。访问 https://43.129.201.125.sslip.io"
