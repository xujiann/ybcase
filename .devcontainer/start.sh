#!/usr/bin/env bash
# 每次 Codespace 启动：拉起 PostgreSQL + 后端(8090) + 前端(5174)
set -uo pipefail

sudo service postgresql start || true
for i in $(seq 1 30); do sudo -u postgres pg_isready -q && break; sleep 1; done

if ! curl -sf http://127.0.0.1:8090/actuator/health > /dev/null 2>&1; then
  JAR=$(ls server/target/ybcase-server-*.jar 2>/dev/null | head -1)
  if [ -z "${JAR}" ]; then
    echo "未找到 jar，先构建..."
    mvn -q -B package -DskipTests
    JAR=$(ls server/target/ybcase-server-*.jar | head -1)
  fi
  echo "启动后端 ${JAR}"
  nohup java -jar "${JAR}" > /tmp/ybcase-server.log 2>&1 &
  for i in $(seq 1 60); do
    curl -sf http://127.0.0.1:8090/actuator/health > /dev/null 2>&1 && break
    sleep 2
  done
  curl -sf http://127.0.0.1:8090/actuator/health || { echo "后端启动失败，见 /tmp/ybcase-server.log"; tail -30 /tmp/ybcase-server.log; }
fi

if ! curl -sf http://127.0.0.1:5174 > /dev/null 2>&1; then
  echo "启动前端 5174"
  nohup npm run dev --prefix frontend > /tmp/ybcase-frontend.log 2>&1 &
fi

echo "就绪：打开转发端口 5174 即为登录页（admin/admin123，演示环境）"
