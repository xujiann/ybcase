#!/usr/bin/env bash
# Codespaces 首次创建：建库建号 + 构建后端 + 安装前端依赖（约 5-8 分钟，只跑一次）
# 注意：Codespaces 的 vscode 用户免密 sudo 仅覆盖切 root，须用 `sudo su postgres -c` 而非 `sudo -u postgres`
set -euo pipefail

echo "== PostgreSQL 初始化 =="
sudo service postgresql start || true
for i in $(seq 1 30); do
  pg_isready -q && break
  sleep 1
done
sudo su postgres -c "psql -tc \"select 1 from pg_roles where rolname='hip'\"" | grep -q 1 \
  || sudo su postgres -c "psql -c \"create role hip login password 'hip123456'\""
sudo su postgres -c "psql -tc \"select 1 from pg_database where datname='ybcase'\"" | grep -q 1 \
  || sudo su postgres -c "createdb -O hip ybcase"

echo "== 后端构建 =="
mvn -q -B package -DskipTests

echo "== 前端依赖 =="
npm ci --prefix frontend --no-audit --no-fund

echo "== E2E 依赖 =="
pip install --quiet requests

echo "setup 完成。前后端将由 postStart 自动拉起；也可手动 bash .devcontainer/start.sh"
