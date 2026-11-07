# 依赖健康报告（P1 子集）

> 本页记录模块契约及分阶段验证范围。当前启动/模块组合见[Go 文档中心](../../../docs/README.md)，两版最新能力与差异见[双端说明](../../../../docs/backend-parity.md)；下文固定基线、测试数和 P1/P3 等范围不是当前全产品或生产批准。

移植原 HealthService/HealthStatusDto/HealthController 的状态规则：数据库实际 `SELECT 1` 成功为 UP、失败为 DOWN；模型只询问显式注入的本地配置状态，CONFIGURED/NOT_CONFIGURED 不改变整体状态，不发起模型请求。每次报告产生 UTC 时间、独立 components 对象与应用名/版本。

`New` 必须显式提供元信息/正探测期限、Probe、模型状态回调和日志；不会自行加载配置或连接服务。`NewPostgresProbe` 复用调用方 Pool、受限获取连接并执行 SELECT 1，不访问业务表、不执行 DDL。Probe 必须支持 context，父请求较早的取消/期限优先；不创建超时后遗留的探测 goroutine。数据库错误不抛到响应，不输出驱动消息/凭据，日志保留安全请求 ID。

`Handler` 已接入显式Foundation的 `/api/v1/health`，默认离线诊断入口保持独立。可接受JSON的GET返回HTTP200统一信封，数据库DOWN仍可读；HEAD同样执行一次探测但无正文。OPTIONS空200且Allow，其他方法交原全局异常契约返回具体405文案且无Allow。默认媒体类型application/json，也支持协商后的application/problem+json；不兼容Accept为406，且按原MVC顺序仍执行一次探测。五角色/Worker隔离与ready=false保持既有边界。

## 已验证

- 对照原三种服务状态、控制器信封/透传值；增加取消/较早父期限、100路并发报告、可变对象隔离和日志脱敏。
- 实际 HTTP/认证安全链证明匿名健康报告不要求登录、不创建会话。
- 真实Boot Servlet运行原HealthController/HealthService/HealthStatusDto/AppProperties，合成JdbcTemplate与模型状态接口适配器；对照68个健康场景的状态/信封/媒体/方法/CSRF/探测次数，包含DOWN与可选模型。夹具不访问真实服务，也不冒充执行原模型运行时。
- `python3 scripts/migration/run_go_postgres_tests.py --tls --package health` 使用自己创建的临时集群/维护连接，实际禁止新连接并终止探针连接：HTTP 200/DOWN；恢复后UP。连接池单槽耗尽时按50ms期限报告DOWN，释放后UP且无占用泄漏。

原 Java 的无界查询由显式 Go ProbeTimeout 收紧；Java 原日志会输出驱动消息，Go有意脱敏。以上不是完整运行应用/前端差分，也未解除安全启动守卫。
