# 数据库事务基础（P1 子集）

当前使用 Go 标准库 `database/sql`，尚未注册或锁定 PostgreSQL 驱动，也没有接入应用入口。`Open` 必须接收调用方显式提供的 `driver.Connector`；没有默认 DSN、隐式环境加载、全局数据库或自动迁移。

## 生命周期

- `DefaultPoolOptions(false)` 对应 combined/api：最多 10 个打开/空闲连接；`true` 对应 Worker：最多 4 个；连接获取/启动探测期限 5 秒。
- `MaxLifetime` 默认 30 分钟，`MaxIdleTime=0` 表示不按空闲时间回收；这不是对 Hikari 预热、最小空闲或保活行为的完整仿真。PostgreSQL 集成阶段必须按实际负载验收。
- `Open` 校验选项、设置边界并实际 `Ping`；失败关闭新池，不返回半初始化资源。Connector 必须遵守 context，才能保证网络握手也受期限约束。
- `WithConn` 只在获取连接时使用短 context。回调中的 SQL 使用调用方 context，不会被已取消的获取 context 立即终止。
- 正常、错误、panic 路径都归还连接；Rows/连接不得逃逸出回调，回调不能留下未结束事务。
- 停止服务时先取消/等待所有 Worker 与请求，再调用幂等 `Close`。尚未完成具体驱动与应用生命周期装配。

## 事务

- `WithTx` 使用同一连接执行开始/回调/提交；回调错误或 panic 时回滚，panic 原样传播。
- 仓储操作需要共享事务时显式传入同一 `*sql.Tx`。再次调用 Pool.WithTx 会开始独立事务并占用另一连接，不隐式加入当前事务。
- 只读和隔离级别透传 `sql.TxOptions`；数据库是否支持/遵守由真实 PostgreSQL 测试验证。
- 父 context 取消后不提交。提交失败可能已经生效，绝不自动重试，业务侧需根据幂等记录/租约等协议判定。
- 回滚/归还错误不会吞掉原业务错误，基础设施错误文案不包含原始 SQL/DSN/凭据。可用 `errors.Is/As` 检查原因；HTTP 层仍需映射统一业务错误。

## 验证范围

`pool_test.go` 使用受控 driver.Connector，验证生命周期、选项透传、回滚、取消、错误/资源管理、32 路并发与池上限。该驱动不实现 PostgreSQL 数据语义，不能据此宣称 SQL 原子性、锁、TLS、pgvector 或 Flyway 历史已验证。

待完成：固定真实驱动和许可证、PostgreSQL 配置与 TLS/时区、最小权限角色、应用装配、42 个原 SQL 的隔离重放、存量库恢复、跨连接事务/锁/取消回归、生产优雅关闭。
