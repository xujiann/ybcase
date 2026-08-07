# 公网演示部署（一周临时演示）

目标：`https://你的域名` 公开可访问，前端静态 + `/api` 反代后端 + 库内 PG，全程 HTTPS，数据库与 actuator 不暴露公网。

## 0. 前置选择（重要）

- **服务器**：腾讯云/阿里云"轻量应用服务器"，**香港/新加坡节点**（免 ICP 备案可绑域名），2核2G、Ubuntu 22/24，一周约 ¥30-60。
- **域名**：任意已有域名加一条 A 记录（如 `ybdemo.xxx.com` → 服务器 IP）。没有域名也可裸 IP 演示（无 HTTPS，Caddyfile 首行改 `:80`）。
- 国内节点 + 域名 = 需备案，一周演示来不及，勿选。

## 1. 本机准备产物（Windows，仓库根目录）

```bash
mvn -q clean package -DskipTests && npm run build --prefix frontend
```

把以下文件传到服务器 `/opt/ybcase/`（scp 或宝塔/FinalShell 拖拽）：

```bash
scp server/target/ybcase-server-1.0.0.jar root@服务器IP:/opt/ybcase/ybcase-server.jar
scp -r frontend/dist deploy/docker-compose.yml deploy/Caddyfile deploy/env.example root@服务器IP:/opt/ybcase/
```

## 2. 服务器上（仅 4 条命令）

```bash
curl -fsSL https://get.docker.com | sh                  # 装 docker（含 compose 插件）
cd /opt/ybcase && cp env.example .env && vi .env        # 填 DB_PASSWORD / JWT_SECRET / DEMO_DOMAIN
docker compose up -d                                    # 拉起 db+app+caddy，首启自动建表+种子
docker compose logs -f app | grep -m1 Started           # 看到 Started 即就绪
```

打开 `https://你的域名` → 登录页。防火墙/安全组只放行 **80、443**（22 限自己 IP）。

## 3. 上线前 5 分钟加固（演示也要做）

1. **改默认口令**：用 admin 登录 → 右上角"修改密码"；四个演示账号同样处理，把新口令只发给受邀演示对象。
2. 参数设置里确认 `org_name` 等展示信息；如需辽宁口径演示：本机跑 `python tools/init-bureau.py --org 某某市医疗保障局 --province liaoning`（BASE 改成演示域名）或直接在参数设置页切。
3. 造一两个完整演示案件（从线索走到结案），比空库更有说服力。
4. （可选）每日备份：`docker compose exec db pg_dump -U hip -Fc ybcase > /opt/ybcase/backup-$(date +%F).dump` 挂 crontab。

## 4. 一周后撤站

```bash
cd /opt/ybcase && docker compose down -v   # 含数据卷一并销毁
```
然后释放服务器、删掉 DNS 记录。若期间收集了真实反馈数据，先跑一次第 3.4 的 pg_dump 带走。

## 风险边界（明确告知使用方）

- 演示环境放的是**虚构数据**；不得录入任何真实参保人信息——公网演示环境不满足真实数据的等保要求。
- 系统自带防爆破锁定、角色分权、审计留痕，但演示口令请勿复用在其他系统。

## 备选：不买服务器的两条路

- **Codespaces 临时公开**（只在演示时段开）：`gh codespace ports visibility 5174:public -c vigilant-space-disco-7r7j59gjwj2xgx4`，演示完切回 private。免费额度 4 核约 30 小时/月，够十几场演示，但不能 7×24 挂着。
- **内网穿透**（零服务器，本机+WSL 需一周不关机）：`cloudflared tunnel --url http://localhost:5174` 得到临时 https 域名；国内访问稳定性一般，正式邀约演示不建议。
