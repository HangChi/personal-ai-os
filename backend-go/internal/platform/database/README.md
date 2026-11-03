# 数据库事务基础（P1 子集）

使用 Go 标准库 `database/sql` 管理连接池，真实连接器锁定 `github.com/jackc/pgx/v5 v5.12.0`。`Open` 必须接收调用方显式提供的 `driver.Connector`；没有默认 DSN、全局数据库或自动迁移，也尚未接入应用入口。依赖许可证见 [第三方声明](../../../THIRD_PARTY_NOTICES.md)。

## 显式 PostgreSQL 连接器

`NewPostgresConnector` 只构造连接器，`Open` 才会发起连接。必须显式传入主机、端口、数据库、用户和密码；应用名默认为 personalos-go，连接期限为 5 秒。拒绝任意 DSN、socket、多主机和隐式 PG* 环境；不读取 .pgpass、service 或客户端证书文件，不修改进程全局环境。

默认 `verify-full` 校验证书链和主机名，最低 TLS 1.2，可显式传入根证书。`disable` 只允许回环测试主机；不支持 require/prefer 或静默明文回退。这是有意受限的显式配置子集，完整 Java 数据源配置仍待迁移。连接器与选项的普通/Go/JSON 格式化均脱敏。

会话时区固定 UTC，timestamptz 二进制扫描也显式使用 UTC。临时自建 CA 的真实 PostgreSQL 已验证 TLS 1.2/1.3、受信根与主机名成功、未知 CA/错误主机名和明文连接拒绝。

## 生命周期

- `DefaultPoolOptions(false)` 对应 combined/api：最多 10 个打开/空闲连接；`true` 对应 Worker：最多 4 个；连接获取/启动探测期限 5 秒。
- `MaxLifetime` 默认 30 分钟，`MaxIdleTime=0` 表示不按空闲时间回收；这不是对 Hikari 预热、最小空闲或保活行为的完整仿真。PostgreSQL 集成阶段必须按实际负载验收。
- `Open` 校验选项、设置边界并实际 `Ping`；失败关闭新池，不返回半初始化资源。Connector 必须遵守 context，才能保证网络握手也受期限约束。
- `WithConn` 只在获取连接时使用短 context。回调中的 SQL 使用调用方 context，不会被已取消的获取 context 立即终止。
- 正常、错误、panic 路径都归还连接；Rows/连接不得逃逸出回调，回调不能留下未结束事务。
- 停止服务时先取消/等待所有 Worker 与请求，再调用幂等 `Close`。尚未完成应用生命周期装配。

## 事务

- `WithManagedTx` 提供显式 Transaction 和 AfterCompletion；完成回调在事务终止且连接归还后执行，提交/错误/取消/panic 都覆盖。回调错误保留原因，回调 panic 不跳过后续清理，也不覆盖业务 panic；不引入隐式环境事务。

- `WithTx` 使用同一连接执行开始/回调/提交；回调错误或 panic 时回滚，panic 原样传播。
- 仓储操作需要共享事务时显式传入同一 `*sql.Tx`。再次调用 Pool.WithTx 会开始独立事务并占用另一连接，不隐式加入当前事务。
- 只读和隔离级别透传 `sql.TxOptions`；真实 PostgreSQL 测试已验证只读写入拒绝和 serializable 设置。
- 父 context 取消后不提交。提交失败可能已经生效，绝不自动重试，业务侧需根据幂等记录/租约等协议判定。
- 回滚/归还错误不会吞掉原业务错误，基础设施错误文案不包含原始 SQL/DSN/凭据。可用 `errors.Is/As` 检查原因；HTTP 层仍需映射统一业务错误。

## 验证范围

`pool_test.go` 使用受控 driver.Connector，验证生命周期、选项透传、回滚、取消、错误/资源管理、32 路并发与池上限。该驱动不实现 PostgreSQL 数据语义，不能据此宣称 SQL 原子性、锁、TLS、pgvector 或 Flyway 历史已验证。

`postgres_integration_test.go` 通过 integration 标签与一次性数据库显式标记保护，普通 go test 不连接数据库。独立容器已验证提交、业务错误/panic/父取消回滚、只读拒绝、隔离级别、UTC/UUID/JSON、真实 pg_sleep 取消/池恢复及 32 路事务竞争下的 Worker 4 连接上限。

从仓库根目录运行 `python3 scripts/migration/run_go_postgres_tests.py`，仅创建和清理自己的临时 PostgreSQL/pgvector 容器：固定镜像摘要、tmpfs 数据目录、无持久卷、随机回环端口、随机测试密码，不读取 .env 或使用现有 Compose。

`python3 scripts/migration/run_go_postgres_tests.py --tls` 使用临时 CA/服务端证书，拒绝明文，全部五个数据库集成包在 TLS 下复验。`postgres_security_integration_test.go` 在本次集群创建无管理权限的账号，仅授独立探针 schema 的 USAGE、单表 DML 和 identity 序列权限；验证中文/emoji 事务读写以及建库/建角色/DDL/读未授权表/自提超级用户全部拒绝。该探针不代表完整 42 个迁移后的部署权限已验收。

全部 42 个原 SQL 的空库/合成数据重放与 pgvector/schema/触发器测试另见 [migration](../migration/README.md)。待完成：完整数据库配置、全部业务 schema 最小权限角色、应用装配、真实 Flyway 历史/锁与存量备份恢复、跨连接故障回归、生产优雅关闭。
