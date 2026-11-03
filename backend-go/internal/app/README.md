# P1基础运行时

`NewHandler`仍是默认二进制使用的离线诊断入口。`OpenFoundation`是可显式构造、已通过临时TLS PostgreSQL验证的基础组合；主命令尚未切换，不能当作Java业务替代。

调用方提供`config.Properties`、日志、显式Connector、原SQL目录的`fs.FS`与`Schema: "public"`。连接池默认按角色取10/4，也可显式传`PoolOptions`；不自动读取JDBC URL或Hikari属性。迁移只读校验目录/Flyway历史；不migrate、repair或改SQL。默认严格拒绝BASELINE，显式允许时只接收已验收的V1标记。协调器使用原public表，自定义schema暂时拒绝；共享协调容量大于100在启动前明确拒绝。

配置组合与依赖校验后创建认证/会话、连接池、数据库共享桶、协调租约、模型并发、真实健康探测及有界轮末执行器。Redis开关开启时必须显式注入对应专用客户端；关闭时不接受闲置注入，两个功能不能借用同一个客户端对象。创建者配置URI/TLS并持有客户端生命周期，基础装配只使用配置中的开关/namespace，不自动加载URI、不关闭借用客户端、不回退本地。启动PING只验证连通性，不证明全部ACL/脚本权限；后续组合测试已验证专用受限ACL的实际脚本/会话/限流操作。

HTTP顺序：Worker业务隔离 → Request ID → 异常边界 → 共享限流 → 共享模型并发 → 会话/CSRF/认证 → 路由。已提交SSE异常中止而不追加JSON。限流/并发早于安全链，匿名/CSRF失败仍消耗模型预算，拒绝后释放并发租约。

API/combined提供原四个Auth路由和真实`/api/v1/health`。健康仍为统一信封/HTTP200，数据库DOWN通过内容报告，模型保持NOT_CONFIGURED。`/livez`与恒定503的`/readyz`是迁移诊断。Worker的这些业务端点均空404；`/actuator/*`在业务入口不提供管理数据。可显式注入未来业务handler，nil时未知业务404。

`Close(ctx)`先拒新请求，等已有HTTP退出，再排空执行器、停止续租并释放租约、关闭自有池。超时会取消在途HTTP/执行器并返回期限错误；清理继续等待实际退出，不提前关闭仍在使用的池。任务/handler必须响应context取消。调用方负责Shutdown监听器；可再次Close等待清理，借用Redis始终由创建者最后关闭。

复验仅连接脚本自己新建的临时库，app包始终先用真实Java Flyway迁移：

```bash
python3 scripts/migration/run_go_postgres_tests.py --tls --flyway --package app
```

通过两个实例共享保护、CookieJar登录/CSRF轮换/注销、五角色池上限、在途取消/执行器排空/租约先于关池、历史损坏拒绝与失败连接清理。完整网络故障/框架差分、CLI接线与业务Worker仍需验收，T102–T109父项保持开放。

## 独立管理监听与最小指标

有效profile包含`monitoring`时，`ManagementHandler()`提供独立管理链，否则返回nil。仅允许GET `/actuator/health`、GET `/actuator/prometheus`，其他路径/方法（包括HEAD）403，不创建业务会话。健康仅输出`{"status":"UP"}`或`{"status":"DOWN"}`，实际探测自有数据库和已启用的专用Redis，失败HTTP503，不套业务信封或公开依赖详情。模型未配置不影响健康，健康UP不代表迁移已就绪。

Prometheus文本提供原限流timer的count/sum（秒）及Redis错误counter，backend标签固定postgres/redis；另以`personalos_go_*`命名自有池和轮末队列gauge。这是最小指标集，没有伪造完整Micrometer、业务HTTP histogram、JVM或未迁移业务指标。

`Serve(ctx, businessListener, managementListener, shutdownTimeout)`接管调用方显式创建的监听器；仅允许回环TCP，管理必须不同端口并与monitoring开关一致。拒绝参数也会关闭已接管监听器；Foundation未启动时由调用方另行Close。每个实例只Serve一次。关闭时两个HTTP服务同时停止入口并排空，之后关闭Foundation；一侧监听异常会关闭另一侧。宽限耗尽强制关闭连接/取消请求并报告期限错误，另给资源清理有限窗口；不响应取消的任务仍可能延后最终清理，不能把它们标作成功完成。

真实双监听集成验证Worker业务健康空404、业务端口不提供管理指标、管理端口不允许Auth/业务API、GET健康/指标及HEAD403、正常退出/异常退出释放端口，以及真实流式请求关闭超时。主CLI默认离线诊断，显式 `--foundation` 已接入这些监听器；真实二进制在两个自建TLS存储/专用ACL下完成五角色、跨OS会话/预算和退出验证，完整网络故障与业务Worker仍待验收。

## PostgreSQL与Redis TLS组合

```bash
python3 scripts/migration/run_go_redis_tests.py --tls --package app
```

外层脚本创建独立Redis TLS容器和两个受限ACL账号，内层创建独立PostgreSQL TLS容器并真实执行Java Flyway，再在同一个Go测试进程运行完整app测试。只传专用测试环境白名单，失败时逐层finally只清理自己创建的容器/临时证书。默认Redis全包测试现在也包含app组合，需要PostgreSQL镜像与Java Flyway缓存。

两个Foundation/HTTP实例共用隔离的会话namespace与限流namespace：实例1的CSRF可供实例2登录，实例1能读取轮换后的身份，模型预算共享。分别关闭一侧专用客户端，验证限流429/错误指标、管理健康DOWN、会话500且不回退；数据库共享桶仍未创建，Foundation关闭后未故障的借用客户端仍可PING。此处故障是客户端关闭，未声称覆盖真实网络分区或整套应用跨OS进程故障恢复。
