# 医保基金监管案件查办系统（ybcase）

面向市级医疗保障局的行政执法案件全流程管理系统。

- 法规依据：《医疗保障行政处罚程序暂行规定》（国家医疗保障局令第4号）；案由参照苏医保督〔2024〕1号（4类主体/9种案由/35项违法情形）
- 流程：线索核查 → 立案 → 调查取证（八类证据/先行登记保存/封存）→ 调查终结 → 法制审核 → 告知听证 → 处理决定 → 送达 → 执行 → 结案归档（一案一卷）
- 法定规则=硬校验（错误码 2xxx），全部经 E2E 回归断言；开发排期见 [系统开发计划.md](系统开发计划.md)

## 结构

| 目录 | 说明 |
|---|---|
| `core/` | 底座：用户/组织/RBAC/JWT 认证/系统配置/审计查询 |
| `server/` | 案件查办服务（Spring Boot 3.5，端口 8090，PostgreSQL `ybcase` 库，Flyway V1/V2） |
| `frontend/` | Vue 3 + Element Plus（dev 端口 5174，代理 /api → 8090） |
| `tools/e2e-case.py` | 26 步端到端回归（需后端运行） |

## 运行

```bash
mvn -q package -DskipTests
java -jar server/target/ybcase-server-*.jar   # 首启自动建表+种子+默认账号
```

```bash
npm install --prefix frontend && npm run dev --prefix frontend
```

默认账号（口令 admin123，生产须改）：`admin` 管理员 / `banban` 办案 / `fazhi` 法制 / `juzhang` 局长。
数据库：PostgreSQL 16，`ybcase`（用户 hip，开发库在 WSL，JDBC 用 127.0.0.1）。

## 来源

自 hip-platform 仓库 `bureau/` 目录迁出（来源提交 f9f0732，2026-08-06），Maven 坐标与包名已改为 `cn.ybcase`，配置前缀 `ybcase.security`，与医院产品完全解耦；`core/` 为 hip-platform-core 的独立分叉，此后各自演进。
