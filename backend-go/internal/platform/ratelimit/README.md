# 模型请求限流（P1 子集）

来源为固定 Java 基线的 `RateLimitProperties`、`TokenBucketRateLimiter`、`DatabaseCoordination.consume`、`ModelRequestPaths` 和 `RateLimitFilter`。当前代码尚未接入应用入口；`/readyz` 仍为 503。

## 本地与共享桶

- `DefaultOptions()`：启用、容量 60、周期 1 分钟。配置关闭时仍校验正容量/正周期，保留 Java int 的范围。
- `NewLocal`：只用于独立构造和测试。启动注满，单调时钟按分数平滑补充，互斥锁保护并发，不超过容量；不是跨实例限流实现。
- `NewShared`：必须显式提供 PostgreSQL 协调后端，可以另传专用 Redis 后端；选中 Redis 后不再访问 PostgreSQL。
- PostgreSQL 错误向上返回；Redis 返回错误时拒绝准入，记录 Redis 错误指标，不重试、不切换后端。不将共享存储错误降级成本地桶。
- `Bucket.Consume` 定义原子消费边界，传递原始 context、`model-rate`、容量和周期。`NewPostgresBucket` 已实现真实 PostgreSQL 消费；Redis Lua、TLS/namespace 仍待迁移。
- `Observer.RecordAcquisition` 在成功、拒绝和数据库错误时记录耗时；`failed=true` 只对应 Redis 返回错误，与 Java 的错误计数位置一致。当前只是可执行的指标回调边界，尚未接入 Prometheus。

## PostgreSQL 桶

必须显式提供已打开的 Pool，不自行连接、建表或执行迁移。每次消费在独立短事务中 INSERT ON CONFLICT 初始化，再执行原 Java 的条件 UPDATE：用 clock_timestamp 平滑补充分数令牌、上限裁剪、回拨不负补充；拒绝时不修改 tokens/refilled_at。业务事务回滚不返还已消费预算，容量改变也不重新注满。SQL/提交失败返回脱敏错误并保留 errors.Is/As 原因，不重试或使用本地回退。

integration 测试直接加载未修改的 V37 原 SQL。一次性数据库验证两个独立连接池 128 路初始化竞争只放行容量 17，以及两个真实 OS 子进程合计 64 次竞争只放行容量 13；同时覆盖数据库时钟补充、分数/未来时间、配置变化、独立事务、行锁取消/连接释放、缺表拒绝与脱敏。该测试只移植了 DatabaseCoordinationIntegrationTest 中的限流场景，租约/fencing/续租/API 预留仍未实现，也不是完整 Java/Go 服务差分。

## HTTP 边界

`httpmiddleware.ModelRequestPath` 对照全部 10 个 POST 模式和 1 个 PUT 模式，保留单次解码、方法大小写、Ant 的空段和末尾斜杠行为。该函数只判断保护范围，不保证请求能通过 Go/Java 的服务器、认证或实际路由。Go 中间件用 `URL.EscapedPath()`，避免二次解码 `URL.Path`，并去掉 query。

`RateLimitMiddleware` 应置于请求 ID 中间件内层、挂载前缀处理之后。放行只消费一次，不改写下游 SSE/状态/响应；拒绝返回 HTTP 429、业务码 42900、中文文案及统一 JSON 信封。限流日志仅记录 method、原始路径和 requestId，不读取正文或记录 query/凭据。PostgreSQL 错误输出脱敏的 50000；这部分的 Servlet 容器级错误差分仍待 T106 验证。

## 验证与待完成项

本地测试以可控时钟覆盖分数、回拨和 1,000 路竞争。共享策略使用替身验证后端选择、错误/拒绝、context/参数和指标回调；PostgreSQL 适配器另以真实存储验证。可从仓库根目录运行 `python3 scripts/migration/run_go_postgres_tests.py`，脚本只操作自己的临时容器。

路径测试读取从固定 Java 源码实际执行得到的 216 项 [快照](../http/testdata/java-model-request-paths.json)，不从 Go 算法生成期望值。在已缓存基线 Java 依赖的环境从仓库根目录执行：

```bash
# 默认仅重新运行 Java 并核对快照，不下载依赖、不读 .env、不监听端口
python3 scripts/migration/java_model_paths_fixture.py
```

Java BOM 中指定的 Spring/Tomcat 依赖缺失时该命令失败；可用 `--m2` 指定已有缓存。只有审查基线/行为变更后才用 `--write` 重生成。此工具不是 Maven verify，也不替代全量 Java/Go 差分。

仍待完成：环境/YAML 属性绑定、Redis 消费/故障测试、数据库断网/提交不确定故障、指标系统和安全/角色链装配、数据库并发租约/API 预留、实际 SSE/后台生命周期。T107 和 P1 未完成。
