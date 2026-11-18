后端集成测试会执行 Flyway、初始化种子和真实数据库并发写入。测试只能连接由本次运行新建、随机命名的独占数据库；现有业务库 `ybcase` 和固定库 `ybcase_test` 不能用于回归。

测试 classpath 中的 `IsolatedTestDatabaseInitializer` 为所有 Spring Boot 测试安装 DataSource 前置检查。检查发生在最终 Hikari 配置绑定后、Flyway 和应用初始化器取得 DataSource 前。默认直接执行 `mvn test`，若没有显式授权证据，会拒绝启动数据库上下文。只运行纯单元测试不需要数据库授权。

首选使用受控的测试 runner：自动生成随机库名，新建数据库，写入本轮随机授权标记，读取实际 OID 和 owner，然后为 Maven 进程设置以下变量。现有本地验收 runner 为项目内 `data/recovery/regression-freeze-20261118/backend/run-backend.py`；它同时冻结源码、记录身份，并在结束后仅清理本轮身份完全匹配且无活动连接的数据库。

| 变量 | 来源 |
| --- | --- |
| `SPRING_DATASOURCE_URL` | 新库的完整 JDBC URL，主机为本地或专用私网地址，显式端口，无附加参数 |
| `YBCASE_TEST_DATABASE_URL` | 与实际最终 DataSource 的 JDBC URL 完全相同 |
| `YBCASE_TEST_DATABASE_OID` | 新建后从 `pg_catalog.pg_database` 读取的真实 OID |
| `YBCASE_TEST_DATABASE_OWNER` | 新库实际 owner，同时为测试连接账号 |
| `YBCASE_TEST_DATABASE_MARKER` | 创建新库时写入数据库 comment 的 `ybcase-test:` 加 32 位随机十六进制标记 |

当前历史并发测试要求库名为 `ybcase_authowner_test_20261028_` 加 8 位随机十六进制字符。每次运行都要重新生成后缀；不能复用历史库。连接凭据通过本地安全配置提供，不在测试报告或命令行中输出。

需要迁移到另一运行器时，必须先成功创建全新的随机库，再记录其 URL、OID、owner 和 marker。不要给既有数据库补写标记以通过检查，也不要猜测或复制上一次运行的 OID。检查会核对最终绑定的 JDBC URL、账号及实际数据库身份，另一个 Flyway URL、自定义 DataSource 和可改变连接目标的 Hikari 属性也会被拒绝。清理时再次比较数据库名、OID、owner 和 comment，有活动连接或任何身份差异时停止，禁止强制断开或删除其他数据库。

该检查和注册文件只位于 `src/test`，不会进入生产 JAR；HTTP 和浏览器验收仍需各自的新隔离库及身份核验。
