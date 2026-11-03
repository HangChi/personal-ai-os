# Redis 扩展

## 共享会话

部署多个 API 实例时，将各实例的 `REDIS_SESSION_ENABLED` 设为 `true`，并配置同一 `REDIS_SESSION_URI` 和 `REDIS_SESSION_NAMESPACE`。命名空间按环境隔离。使用独立、启用认证、配置 `noeviction` 的 Redis，按并发会话数量分配容量。生产连接使用 TLS，HTTPS 部署开启安全 Cookie。

Spring Session 保存登录上下文和会话属性。共享模式使用 `SESSION` Cookie，启用时已有浏览器需重新登录。退出登录会删除共享会话。Redis 连接故障会影响依赖会话的请求。需要回退时统一关闭所有实例的共享会话开关，浏览器重新登录。

## 模型请求频率

默认使用 PostgreSQL 令牌桶。`personalos.rate.limit.acquire` 按后端记录每次扣减耗时。评估迁移时，在相同 API 与 worker 并发下对照吞吐、准入耗时和 PostgreSQL 的 `pg_stat_activity.wait_event_type = 'Lock'`。这个计时包含连接和 SQL 执行耗时。

确认需要迁移后，统一暂停模型请求，再给所有 API 和 worker 配置同一个启用 `noeviction` 的 Redis、同一 `REDIS_RATE_LIMIT_NAMESPACE` 及相同的令牌桶额度和周期。等待原限流周期结束后，用 `REDIS_RATE_LIMIT_ENABLED=true` 启动所有进程。回退时同样统一切换。Redis 故障时拒绝模型请求，避免切换到独立计数器后超额。跨进程并发额度与写入租约继续由数据库管理。
