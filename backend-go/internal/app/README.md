# P1基础运行时

`NewHandler`仍是默认二进制使用的离线诊断入口。`OpenFoundation`是可显式构造、已通过临时TLS PostgreSQL验证的基础组合；主命令尚未切换，不能当作Java业务替代。

调用方提供`config.Properties`、日志、显式Connector、原SQL目录的`fs.FS`与`Schema: "public"`。连接池默认按角色取10/4，也可显式传`PoolOptions`；不自动读取JDBC URL或Hikari属性。迁移只读校验目录/Flyway历史；不migrate、repair或改SQL。默认严格拒绝BASELINE，显式允许时只接收已验收的V1标记。协调器使用原public表，自定义schema暂时拒绝；共享协调容量大于100在启动前明确拒绝。

配置组合与依赖校验后创建认证/会话、连接池、数据库共享桶、协调租约、模型并发、真实健康探测及有界轮末执行器。Redis开关开启时必须显式注入对应专用客户端；关闭时不接受闲置注入，两个功能不能借用同一个客户端对象。创建者配置URI/TLS并持有客户端生命周期，基础装配只使用配置中的开关/namespace，不自动加载URI、不关闭借用客户端、不回退本地。启动PING只验证连通性，不证明全部ACL/脚本权限。基础组合下Redis真实集成尚待完成。

HTTP顺序：Worker业务隔离 → Request ID → 异常边界 → 共享限流 → 共享模型并发 → 会话/CSRF/认证 → 路由。已提交SSE异常中止而不追加JSON。限流/并发早于安全链，匿名/CSRF失败仍消耗模型预算，拒绝后释放并发租约。

API/combined提供原四个Auth路由和真实`/api/v1/health`。健康仍为统一信封/HTTP200，数据库DOWN通过内容报告，模型保持NOT_CONFIGURED。`/livez`与恒定503的`/readyz`是迁移诊断。Worker的这些业务端点均空404；`/actuator/*`在业务入口不提供管理数据。可显式注入未来业务handler，nil时未知业务404。

`Close(ctx)`先拒新请求，等已有HTTP退出，再排空执行器、停止续租并释放租约、关闭自有池。超时会取消在途HTTP/执行器并返回期限错误；清理继续等待实际退出，不提前关闭仍在使用的池。任务/handler必须响应context取消。调用方负责Shutdown监听器；可再次Close等待清理，借用Redis始终由创建者最后关闭。

复验仅连接脚本自己新建的临时库，app包始终先用真实Java Flyway迁移：

```bash
python3 scripts/migration/run_go_postgres_tests.py --tls --flyway --package app
```

通过两个实例共享保护、CookieJar登录/CSRF轮换/注销、五角色池上限、在途取消/执行器排空/租约先于关池、历史损坏拒绝与失败连接清理。管理端口/指标、完整网络故障/框架差分、CLI接线与业务Worker仍需验收，T102–T109父项保持开放。
