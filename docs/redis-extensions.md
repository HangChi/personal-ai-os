# Redis 扩展

## 共享会话

部署多个 API 实例时，将各实例的 `REDIS_SESSION_ENABLED` 设为 `true`，并配置同一 `REDIS_SESSION_URI` 和 `REDIS_SESSION_NAMESPACE`。命名空间按环境隔离。使用独立、启用认证、配置 `noeviction` 的 Redis，按并发会话数量分配容量。生产连接使用 TLS，HTTPS 部署开启安全 Cookie。

Spring Session 保存登录上下文和会话属性。共享模式使用 `SESSION` Cookie，启用时已有浏览器需重新登录。退出登录会删除共享会话。Redis 连接故障会影响依赖会话的请求。需要回退时统一关闭所有实例的共享会话开关，浏览器重新登录。
