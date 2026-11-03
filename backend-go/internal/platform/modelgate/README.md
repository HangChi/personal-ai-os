# 模型并发门控

对应固定 Java ModelConcurrencyGate，默认上限 4。本地 TryAcquire 非阻塞，独立测试构造 NewLocal；多实例必须显式 NewShared(limit, role, Coordinator)。三种 Worker 先获取 model-background(max(1,limit-1))，再获取 model-concurrency(limit)；api/combined 只取后者。limit=1 按原 Java 行为没有额外 API 槽，不擅自更改策略。

拒绝或异常归还已取得的后台/本地许可；成功持有到 Release。关闭幂等，共享释放失败仍尝试后台清理并归还本地容量，不重试、不回退本地模式。共享容量仍受协调器 1–100 限制；错误通过显式返回值传递。没有接入应用或真实 Worker。

真实 PostgreSQL 测试覆盖 index/review/delivery 的 API 预留、跨池后台配额、combined 前台策略、limit=1、共享拒绝清理，以及触发器注入的共享获取/释放失败。定向复验：python3 scripts/migration/run_go_postgres_tests.py --package modelgate。单测另验证 1,000 路本地竞争、取消和幂等释放。

HTTP 的 ConcurrencyMiddleware 使用同一 ModelRequestPath，位于请求 ID/速率限制之后。Go SSE 在 ServeHTTP 内持续写入，许可保持到处理器退出；禁止返回后后台写 ResponseWriter。正常/取消/panic 都独立清理许可，拒绝返回原 42900 JSON，数据库错误返回脱敏 50000；不包装 ResponseWriter，以保留 Flusher 等能力。channel 控制的流生命周期/正文透传测试及真实回环 HTTP SSE 断开后释放/下一请求准入已通过；真实前端重连、上游网络故障和服务端装配仍待验收。
