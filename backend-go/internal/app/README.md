# P1基础运行时

> 本页记录模块契约及分阶段验证范围。当前启动/模块组合见[Go 文档中心](../../docs/README.md)，两版最新能力与差异见[双端说明](../../../docs/backend-parity.md)；下文固定基线、测试数和 P1/P3 等范围不是当前全产品或生产批准。

`NewHandler`仍是默认二进制的离线诊断入口。`OpenFoundation`是显式`--foundation`主命令使用的基础组合，已通过自建TLS双存储、真实二进制和网络故障验证；不能当作Java业务替代。

调用方提供`config.Properties`、日志、显式Connector、原SQL目录的`fs.FS`与`Schema: "public"`。连接池默认按角色取10/4，也可显式传`PoolOptions`；OpenFoundation不读取宿主环境；显式主命令从canonical数据源绑定受限JDBC和Hikari上限/等待，未支持扩展连接前拒绝。迁移只读校验目录/Flyway历史；不migrate、repair或改SQL。默认严格拒绝BASELINE，显式允许时只接收已验收的V1标记。协调器使用原public表，自定义schema暂时拒绝；共享协调容量大于100在启动前明确拒绝。

配置组合与依赖校验后创建认证/会话、连接池、数据库共享桶、协调租约、模型并发、真实健康探测及有界轮末执行器。Redis开关开启时必须显式注入对应专用客户端；关闭时不接受闲置注入，两个功能不能借用同一个客户端对象。创建者配置URI/TLS并持有客户端生命周期，基础装配只使用配置中的开关/namespace，不自动加载URI、不关闭借用客户端、不回退本地。启动PING只验证连通性，不证明全部ACL/脚本权限；后续组合测试已验证专用受限ACL的实际脚本/会话/限流操作。

HTTP顺序：Servlet路径/方法边界 → Worker业务隔离 → Request ID/HTTP观测 → 异常边界 → 共享限流 → 共享模型并发 → 会话/CSRF/认证 → 路由；整个请求共享一次multipart解析并在结束时清理。已提交SSE异常中止而不追加JSON。限流/并发早于安全链，匿名/CSRF失败仍消耗模型预算，拒绝后释放并发租约。

API/combined提供原四个Auth路由、真实`/api/v1/health`，并在P2装配时提供任务9路由和确认列表/执行/取消。健康仍为统一信封/HTTP200，数据库DOWN通过内容报告，模型保持NOT_CONFIGURED。`/livez`与恒定503的`/readyz`是迁移诊断。Worker的这些业务端点均空404。非monitoring业务端口按原配置提供Actuator健康/信息；monitoring时迁到独立管理入口。可显式注入未来业务handler，nil时未知业务404。

`Close(ctx)`先拒新请求，等已有HTTP退出，再排空执行器、停止续租并释放租约、关闭自有池。超时会取消在途HTTP/执行器并返回期限错误；清理继续等待实际退出，不提前关闭仍在使用的池。任务/handler必须响应context取消。调用方负责Shutdown监听器；可再次Close等待清理，借用Redis始终由创建者最后关闭。

复验仅连接脚本自己新建的临时库，app包始终先用真实Java Flyway迁移：

```bash
python3 scripts/migration/run_go_postgres_tests.py --tls --flyway --package app
```

通过双实例共享保护、CookieJar登录/CSRF轮换/注销、五角色池上限、在途取消/执行器排空/租约先于关池、历史损坏拒绝与失败连接清理。基础CLI、TCP分区、恢复库启动、上传安全装配及HTTP/运行观测均已完成回归。P1支持/拒绝边界见[验收契约](../../../specs/033-go-rewrite/p1-foundation-contract.md)；业务Worker按P4–P6迁移。

## 独立管理监听与最小指标

有效profile包含`monitoring`时，`ManagementHandler()`提供独立管理链，否则返回nil。仅允许GET `/actuator/health`、GET `/actuator/prometheus`，其他路径/方法（包括HEAD）403，不创建业务会话。真实Servlet对照后，健康响应保留status与liveness/readiness groups，默认媒体类型为Actuator v3 JSON，也接受application/json。实际探测自有数据库和已启用的专用Redis，失败HTTP503，不套业务信封；默认when_authorized对匿名隐藏组件，显式always才展示详情。非monitoring业务入口的已登录用户可读取详情/组件路径，匿名组件路径为空404。模型未配置不影响健康，健康UP不代表迁移已就绪。

非monitoring业务端口按exposure提供`/actuator/metrics`及具体指标路径。原限流timer以seconds报告COUNT/TOTAL_TIME/MAX，支持backend标签过滤；MAX按SimpleMeterRegistry默认一分钟三槽窗口过期，累计值不清零。Redis错误counter只在实际发生错误后出现；空清单和不存在/无匹配标签指标分别为空names/空404。框架媒体类型、方法与标签错误行为通过真实Actuator差分，不添加JVM或未采集的指标名称。

Prometheus文本提供原限流timer的count/sum（秒）及Redis错误counter，backend标签固定postgres/redis；另以`personalos_go_*`命名自有池和轮末队列gauge。另接入真实HTTP完成timer、活跃请求与Go goroutine/堆内存/uptime；monitoring开关控制68个有限桶的HTTP histogram。未知URL不作标签，最多256序列，超限显式计数；后续业务histogram按所属阶段注册。

`Serve(ctx, businessListener, managementListener, shutdownTimeout)`接管调用方显式创建的监听器；仅允许回环TCP，管理必须不同端口并与monitoring开关一致。拒绝参数也会关闭已接管监听器；Foundation未启动时由调用方另行Close。每个实例只Serve一次。关闭时两个HTTP服务同时停止入口并排空，之后关闭Foundation；一侧监听异常会关闭另一侧。宽限耗尽强制关闭连接/取消请求并报告期限错误，另给资源清理有限窗口；不响应取消的任务仍可能延后最终清理，不能把它们标作成功完成。

真实双监听集成验证Worker业务健康空404、业务端口不提供管理指标、管理端口不允许Auth/业务API、GET健康/指标及HEAD403、正常退出/异常退出释放端口，以及真实流式请求关闭超时。主CLI默认离线诊断，显式 `--foundation` 已接入这些监听器；真实二进制在两个自建TLS存储/专用ACL下完成五角色、跨OS会话/预算和退出验证，真实网络故障/恢复已通过，业务Worker按后续业务阶段验收。

## PostgreSQL与Redis TLS组合

```bash
python3 scripts/migration/run_go_redis_tests.py --tls --package app
```

外层脚本创建独立Redis TLS容器和两个受限ACL账号，内层创建独立PostgreSQL TLS容器并真实执行Java Flyway，再在同一个Go测试进程运行完整app测试。只传专用测试环境白名单，失败时逐层finally只清理自己创建的容器/临时证书。默认Redis全包测试现在也包含app组合，需要PostgreSQL镜像与Java Flyway缓存。

两个Foundation/HTTP实例共用隔离的会话namespace与限流namespace：实例1的CSRF可供实例2登录，实例1能读取轮换后的身份，模型预算共享。分别关闭一侧专用客户端，验证限流429/错误指标、管理健康DOWN、会话500且不回退；数据库共享桶仍未创建，Foundation关闭后未故障的借用客户端仍可PING。该测试自身的故障是客户端关闭；真实TCP分区及跨OS进程另有下述组合测试和smoke_go_foundation.py证据。

新增透明TCP代理测试保留端到端TLS，分别切断PG/会话Redis/限流Redis连接并恢复；验证健康/请求拒绝、有效租约保持与恢复释放、原Cookie身份恢复和无后端回退。另实际丢弃COMMIT响应，调用超时而直连确认一次提交，禁止透明重试。完整schema40表下P1随机最小角色可启动五角色，未授权37表与DDL/提权均拒绝；后续业务表权限不能由此推断。


## P3 模型模块

显式`EnableP3`在Flyway只读校验后装配模型服务、运行时工厂、14条模型/档案/音频路由与嵌入缓存，并组合既有P2处理器、安全链/CSRF、限流/租约和multipart生命周期。CLI显式foundation模式同时启用P2/P3/P4；默认诊断仍离线。Worker不开放这些业务路由。

P3要求原模型三表SELECT/INSERT/UPDATE/DELETE及`ai_model_profile_model_id_seq`的USAGE；缺项启动拒绝，失败释放池。`EmbeddingRedis`必须为第三个专用客户端，调用者持有生命周期，禁止与会话/限流复用。Foundation拥有其自建HTTP transport，关闭后清理空闲连接；所有真实模型请求都在事务外。嵌入Redis是可回退缓存，不沿用会话/限流的失败关闭策略。

模型真实配置状态进入业务健康报告。嵌入metrics按实际计量暴露cache requests、duration COUNT/TOTAL_TIME/MAX、Redis错误；monitoring下原`personalos.embedding.duration`直方图开关已消费。完整职责与验证见[P3收口报告](../../../specs/033-go-rewrite/p3-completion.md)。prod拒绝/readyz503继续保留。

## P4 对话模块

`EnableP4`要求P2/P3依赖已启用；只读Flyway验证后检查会话/消息/引用/预算/计划/运行/事件/交互的最小表与序列权限，再装配流/Run与用量。API和单体角色启用Run恢复/队列与可配置附件cron，Worker不开放业务路由、不运行P4后台循环。流与最终业务写入均受会话租约及事务内fencing保护，关闭先取消Run、排空有界轮末任务，再释放连接池。

引用在done前尽力提交；用量按原契约在完成后限时尽力记账，统计失败不回滚回答。metrics进入既有Actuator/Prometheus与直方图。真实TLS最小权限、跨实例停止/互斥/恢复、故障与前端原API联调见[P4收口报告](../../../specs/033-go-rewrite/p4-completion.md)。记忆、日历与提醒通过 `EnableP5` 或命令行 `--p5` 装配。完整 RAG、跨模块搜索导出及保留期清理按 P6–P8 推进，prod拒绝/readyz503保留。
