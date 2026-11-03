# Redis 扩展

## 共享会话

部署多个 API 实例时，将各实例的 `REDIS_SESSION_ENABLED` 设为 `true`，并配置同一 `REDIS_SESSION_URI` 和 `REDIS_SESSION_NAMESPACE`。命名空间按环境隔离。使用独立、启用认证、配置 `noeviction` 的 Redis，按并发会话数量分配容量。生产连接使用 TLS，HTTPS 部署开启安全 Cookie。

Spring Session 保存登录上下文和会话属性。共享模式使用 `SESSION` Cookie，启用时已有浏览器需重新登录。退出登录会删除共享会话。Redis 连接故障会影响依赖会话的请求。需要回退时统一关闭所有实例的共享会话开关，浏览器重新登录。

## 模型请求频率

默认使用 PostgreSQL 令牌桶。`personalos.rate.limit.acquire` 按后端记录每次扣减耗时。评估迁移时，在相同 API 与 worker 并发下对照吞吐、准入耗时和 PostgreSQL 的 `pg_stat_activity.wait_event_type = 'Lock'`。这个计时包含连接和 SQL 执行耗时。

确认需要迁移后，统一暂停模型请求，再给所有 API 和 worker 配置同一个启用 `noeviction` 的 Redis、同一 `REDIS_RATE_LIMIT_NAMESPACE` 及相同的令牌桶额度和周期。等待原限流周期结束后，用 `REDIS_RATE_LIMIT_ENABLED=true` 启动所有进程。回退时同样统一切换。Redis 故障时拒绝模型请求，避免切换到独立计数器后超额。跨进程并发额度与写入租约继续由数据库管理。

## RAG 候选缓存

设置 `RAG_CANDIDATE_CACHE_ENABLED=true` 和 `REDIS_ENABLED=true` 启用。缓存保存当前单用户部署的未过滤候选池，TTL 为 20 秒，单条最多 256 KiB。缓存键覆盖环境命名空间、检索参数、嵌入配置、索引就绪状态和数据库资料版本。文档、向量或来源变更随业务事务更新版本。每次请求继续运行资料范围过滤、重排和冲突判断。

候选值包含知识片段原文，适用于受信任的缓存 Redis。查询文本只以摘要进入键。Redis 读写故障时继续正常检索。指标 `personalos.knowledge.candidate.cache` 按命中、未命中及错误计数。

## 限流对照测试

2026-11-03 在本机隔离容器中比较 PostgreSQL 共享令牌桶与 Redis 令牌桶。每种后端先预热 80 次，再以 8 路并发对单桶执行 1,600 次扣减。PostgreSQL 使用 10 个连接的 Hikari 连接池。测试额度充足，所有请求均被放行。

| 后端 | P50 | P95 |
| --- | --- | --- |
| PostgreSQL | 2.203 ms | 6.043 ms |
| Redis | 0.392 ms | 0.677 ms |

每隔约 10 ms 采样 PostgreSQL 的锁等待，观测到峰值 7 个等待者。这组数据衡量单桶扣减，不包含模型调用或业务请求耗时。切换前应以实际负载与故障处理要求为准。
