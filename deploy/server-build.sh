#!/usr/bin/env bash
# 【服务器上运行】拉取最新源码 → 在 Docker 里构建后端+前端 → 部署 → 重启。
# 全程走服务器到 GitHub 的网络,本机无需上传大文件。
# 前提:部署私钥已放在 /root/.ssh/ybcase_deploy(见 deploy/上线清单.md 服务器端构建一节)。
# 用法:bash /opt/ybcase-src/deploy/server-build.sh
set -euo pipefail

SRC=/opt/ybcase-src
DEPLOY=/opt/ybcase
export GIT_SSH_COMMAND="ssh -i /root/.ssh/ybcase_deploy -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new"

echo "[1/5] 拉取最新源码 → $SRC"
if [ "${1:-}" = "--no-pull" ]; then
    echo "  （已由上一轮拉取到 $(git -C "$SRC" rev-parse --short HEAD)，跳过）"
elif [ -d "$SRC/.git" ]; then
    BEFORE=$(git -C "$SRC" rev-parse HEAD)
    git -C "$SRC" fetch --depth 1 origin main
    git -C "$SRC" reset --hard origin/main
    # bash 是按字节偏移逐行读脚本的：本脚本自己也在仓库里，reset 之后磁盘上的文件已换，
    # 继续执行等于按旧偏移读新文件——轻则跳过几行，重则执行半截命令。
    # 只要源码有更新，就用新版本的脚本重新执行一次（--no-pull 防止递归）。
    if [ "${1:-}" != "--no-pull" ] && [ "$BEFORE" != "$(git -C "$SRC" rev-parse HEAD)" ]; then
        echo "  源码已更新（$BEFORE → $(git -C "$SRC" rev-parse --short HEAD)），以新版脚本重新执行"
        exec bash "$SRC/deploy/server-build.sh" --no-pull
    fi
else
    git clone --depth 1 git@github.com:xujiann/ybcase.git "$SRC"
fi
echo "  当前提交: $(git -C "$SRC" rev-parse --short HEAD)"

echo "[2/5] Docker 构建后端(Maven,缓存 .m2 加速后续)"
docker run --rm -v "$SRC":/app -v ybcase_m2:/root/.m2 -w /app \
    maven:3.9-eclipse-temurin-21 mvn -q clean package -DskipTests

echo "[3/5] Docker 构建前端(Node,缓存 node_modules)"
docker run --rm -v "$SRC":/app -v ybcase_npm:/app/frontend/node_modules -w /app/frontend \
    node:22-alpine sh -c "npm ci --no-audit --no-fund && npm run build"

# 备份提前到落盘之前：upgrade.sh 里的 pg_dump + pg_restore --list 校验随库增长可达数分钟，
# 夹在"新产物已就位、容器还没重建"之间会把上面那段不一致窗口整体拉长。
echo "[4/5] 升级前备份（回滚依据）"
if [ -x "$DEPLOY/backup.sh" ]; then (cd "$DEPLOY" && ./backup.sh); fi

echo "[4/5] 部署构建产物到 $DEPLOY"
# 回滚点必须在覆盖之前留存：upgrade.sh 里再存就已经是新 jar 了（回滚等于没回滚）
if [ -f "$DEPLOY/ybcase-server.jar" ]; then cp -f "$DEPLOY/ybcase-server.jar" "$DEPLOY/ybcase-server.jar.prev"; fi
# 必须换 inode 而非原地改写：该 jar 被 bind mount 进**正在运行**的 app 容器，
# 而 Spring Boot fat jar 的类与资源是惰性读的（LaunchedClassLoader 全程持有该 fd）。
# 原地 cp（O_TRUNC）会让旧 JVM 在"覆盖完成→容器重建"这段时间里从新 jar 的旧偏移量读字节，
# 表现为随机 ZipException/NoClassDefFoundError，而健康检查仍报 UP。
# mv 是同分区内的原子 rename：旧进程继续读旧 inode，直到容器重建才切换。
cp "$SRC"/server/target/ybcase-server-*.jar "$DEPLOY/ybcase-server.jar.new"
mv -f "$DEPLOY/ybcase-server.jar.new" "$DEPLOY/ybcase-server.jar"
# 前端原地更新目录内容,不整体替换目录——否则换掉 inode 会让 Caddy 的 bind mount 失效(404)
mkdir -p "$DEPLOY/dist"
rm -rf "$DEPLOY/dist"/* "$DEPLOY/dist"/.[!.]* 2>/dev/null || true
cp -r "$SRC/frontend/dist/." "$DEPLOY/dist/"
# 同步最新的运维脚本与编排配置。
# 此前只同步了两个脚本，compose/Caddyfile 的改动（时区、working_dir、日志轮转、安全头）
# 永远到不了线上——源码改了、线上还是旧配置，且从部署日志完全看不出来。
cp "$SRC"/deploy/upgrade.sh "$SRC"/deploy/backup.sh "$DEPLOY/" && chmod +x "$DEPLOY"/upgrade.sh "$DEPLOY"/backup.sh
CADDY_CHANGED=0
if ! cmp -s "$SRC/deploy/Caddyfile" "$DEPLOY/Caddyfile"; then CADDY_CHANGED=1; fi
cp "$SRC"/deploy/docker-compose.yml "$SRC"/deploy/Caddyfile "$DEPLOY/"

echo "[5/5] 重启 + 健康检查"
cd "$DEPLOY" && ./upgrade.sh --skip-backup
# 编排配置若有变更（时区/挂载/日志），需让 db 与 caddy 也应用新配置
sudo docker compose up -d
# Caddyfile 是 Caddy 启动时载入的，bind mount 文件内容变化 compose 察觉不到——
# 此前反代/安全头/资源缓存的改动同步到了部署目录却从未生效。有变才 reload（不断连）。
# 不再用 cmp 结果做门控：一次部署中重载未触发而 HSTS 未生效，隔离测试却证明 cmp 判定正确，
# 原因未能定位。caddy reload 对未变的配置是幂等空操作且不断连，故每次部署无条件执行并回显结果，
# 让"配置是否生效"不再依赖任何判断分支。
[ "$CADDY_CHANGED" = 1 ] && echo "Caddyfile 有变更" || echo "Caddyfile 无变更（仍执行幂等重载）"
if sudo docker compose exec -T caddy caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile >/tmp/caddy-reload.log 2>&1; then
  echo "Caddy 已重载"
else
  echo "!! caddy reload 失败，改为重启 caddy："; tail -5 /tmp/caddy-reload.log
  sudo docker compose restart caddy
fi
