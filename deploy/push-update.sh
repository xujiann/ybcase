#!/usr/bin/env bash
# 【本机(开发机)运行,不是服务器】一键更新香港试运行环境。
# 前提:已配好 SSH 免密别名 ybcase-hk（见 deploy/上线清单.md 的 SSH 密钥一节）。
# 用法:在仓库根目录  bash deploy/push-update.sh
set -euo pipefail
cd "$(dirname "$0")/.."   # 切到仓库根

HOST=ybcase-hk
JAR=server/target/ybcase-server-1.2.0.jar

echo "[1/3] 本机构建后端 + 前端"
mvn -q clean package -DskipTests
npm run build --prefix frontend

echo "[2/3] 上传到 $HOST:/opt/ybcase/"
scp "$JAR" "$HOST:/opt/ybcase/ybcase-server.jar"
scp -r frontend/dist "$HOST:/opt/ybcase/"
scp deploy/upgrade.sh deploy/backup.sh "$HOST:/opt/ybcase/"   # 脚本本身有更新时一并同步

echo "[3/3] 远程执行升级(备份→重启加载新jar→健康检查)"
ssh "$HOST" "cd /opt/ybcase && chmod +x upgrade.sh backup.sh && ./upgrade.sh"

echo "==> 完成。访问 https://43.129.201.125.sslip.io"
