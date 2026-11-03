# P1基础运行时验收契约

Java基线固定`cb5d694d62fa93742b5789b97273e76d931ce335`。P1交付可组合、可真实启动的基础设施；任务/模型/对话/知识等业务按P2–P7串行迁移。以下边界是明确支持或启动拒绝，不把未使用配置视作已实现。

## 数据源、迁移与权限

- 驱动固定pgx v5.12.0，标准库显式连接池，API/combined上限10，三个Worker profile上限4；`maximum-pool-size`与毫秒`connection-timeout`实际接线，获取超时≥250ms且有界。连接最大生命周期30分钟，默认同上限的空闲连接数。SQL/事务取消使用调用方context，不透明重试COMMIT。
- JDBC仅单主机TCP、显式端口、单库；查询参数/多主机/内嵌凭据拒绝。Go TLS用独立显式参数，默认verify-full；不将Java JDBC任意选项当作已支持。
- `pool-name=personalos-pool`作为原配置标识保留；`driver-class-name`仅允许`org.postgresql.Driver`。其余Hikari/JNDI/数据源扩展（例如minimum-idle、max-lifetime、connection-init-sql、自动提交、read-only默认等）统一在连接前拒绝。显式事务选项不依赖这些未支持默认值。
- Go不执行Flyway migrate/baseline/repair，只校验原42 SQL SHA-256与真实历史CRC32/顺序/成功状态。迁移唯一执行权属于原Java Flyway或独立部署步骤。默认只接受完整SQL历史，显式开关额外接受已验收的默认V1基线。自定义历史表/版本/描述、SCHEMA/repeatable/undo等标记、非public应用schema均拒绝。
- Flyway原enabled/locations/baseline-on-migrate配置仅保留基线值，不把它们转成Go迁移执行开关；JPA原validate/open-in-view=false/UTC由显式仓储、事务和类型验证替代，不引入自动DDL。其他这些配置面的扩展值拒绝。
- 部署账号分为bootstrap管理员、独立迁移属主、运行角色。bootstrap负责建库/角色和需要特权的vector等扩展；迁移属主需要目标schema CREATE/USAGE、原对象/序列/触发器/函数的所有权与历史表读写，并且只有一个部署步骤运行。不要给运行角色继承迁移属主或管理角色。
- 已实际验收的P1运行权限：数据库CONNECT、public USAGE、`flyway_schema_history` SELECT、`runtime_rate_bucket/runtime_lease` SELECT/INSERT/UPDATE；无其他37表读取、无DDL/TEMP/历史写/提权权限。部署时先审查PUBLIC默认授权、属主及角色继承；不能只增加GRANT而忽略已继承的权限。
- 上述运行权限只覆盖P1。P2以后的仓储需逐表追加业务CRUD/序列/触发器依赖并测试，不能用`GRANT ALL`替代审查。恢复属主/ACL、真实用户备份及跨版本恢复属于P8；合成备份已完成数据/DDL/序列与五角色实际启动/关闭验证。

## 请求、会话与上传

- 限流/模型租约早于会话CSRF/认证；Worker业务请求在依赖访问前空404。Tomcat/StrictHttpFirewall已验证的路径/方法拒绝在角色过滤前执行，区分容器HTML400、框架JSON400/405及业务信封；安全响应头逐一与真实Servlet比较。
- Cookie和Redis序列化为Go命名空间，切换后重新登录。canonical路径是`server.servlet.session.*`；原YAML的`spring.servlet.session.cookie.*`历史错误路径保持原样，不能当成已设置Secure。
- 本地会话支持正timeout（无单位秒）、cookie.name/path/domain/http-only/same-site及安全链secure；持久cookie.max-age、tracking-modes、URL会话重写、上下文根路径等扩展明确拒绝。生产守卫仍强制认证/CSRF/Secure/非示例密码。
- 共享会话沿用原@EnableRedisHttpSession的30分钟、SESSION、Lax、HttpOnly，Secure来自原canonical配置。原独立CookieSerializer没有消费的Servlet会话扩展在Go显式拒绝；不会误声称这些配置改变共享会话。TLS、独立ACL、跨OS共享/轮换/注销及网络恢复均有真实服务证据。
- Multipart大小由原canonical配置装配（8MB/10MB）；location支持显式存在的绝对目录。每请求只解析一次：正文CSRF和业务共享暂存对象，返回/拒绝/超限/取消/panic都清理，业务后台必须先复制文件。原始文件名只是元数据；随机0700目录/0600文件。
- file-size-threshold仅0（统一落盘）；enabled仅true、resolve-lazily/strict-servlet-compliance仅false；无界大小、其他Tomcat连接器/参数扩展拒绝。对齐固定Tomcat默认50片与512字节分片头，真实Servlet验证恰好边界及超出一项；控制器上传解析超限为业务40003，CSRF正文参数解析超限保留容器JSON413。multipart业务附件/音频/知识库处理分别在P4/P6。
- JSON信封、基础分页、String标量绑定、Unicode/null、UTC时间、内容协商和实际UTF字节编码已验收。未来业务DTO的字段、时间/校验/省略规则随对应阶段逐项对照，不用基础样本替代全量业务测试。
- Go传输层在Handler之前拒绝的畸形HTTP起始行/非法头由net/http处理；未复刻完整Tomcat协议栈。无自定义Tomcat配置支持承诺。P8仍需在真实反代下做端到端验证。

## 配置与后台链

- 12类record/67字段、70处@Value、8处getProperty的来源索引固定且可复验；78处调用方逐项处置见`config-disposition.json/md`。动态配置指纹键和模型来源键已展开，未知来源/缺证据使台账检查失败。
- Properties保留原7文件/17文档、profile顺序、dotenv晚于ConfigData、占位符与来源优先级。任意外部ConfigData位置/import/profile group拒绝。
- 已接管的数据源/Flyway/JPA/Servlet/server/management/logging扩展只允许显式支持清单；未知项在打开外部资源前拒绝。任意Tomcat连接器、SSL/转发头策略、非回环监听、管理path重定位均未开放。
- 模型/embedding/MCP/Rabbit/S3/OpenSearch等后续业务配置可以被来源解析器保留，但不会提前创建客户端；台账明确对应P3–P8任务。
- 请求ID从HTTP经Foundation进入真实有界轮末执行器；后台只复制ID，不继承HTTP取消/deadline/身份/私有值。真实模型流、摘要/记忆、持久Worker调度分别属于P4/P5/P6，P1不启动空Worker。
- 原生产安全校验已完成；Go额外禁用prod、仅回环监听及readyz=503是P8发布门禁，P1通过不解除这些限制。

## 观测与日志

- 真实限流timer/counter、HTTP完成timer、当前活跃请求、数据库池和执行器状态已采集。SSE在处理器真正结束时累计，首个Flush不计完成；正常/取消/panic/中止只计一次。
- `http.server.requests`保留原exception/method/outcome/status/uri低基数标签，已对照原Spring观测约定160场景。URI只用服务器路由声明；未知路径NOT_FOUND/UNKNOWN，不含用户ID/查询串/token。每实例最多256条序列，超出显式计入dropped指标。
- 原monitoring `http.server.requests` histogram开关实际生效，默认Micrometer Timer的1ms–30s共68个有限边界加+Inf；逐项与原Micrometer生成器比较。没有histogram时只输出count/sum/max。其余3个原业务histogram开关跟随P3/P6指标注册，不提前生成空业务指标。
- `/actuator/metrics`与单项/tag过滤显示实际采集内容；Go原生goroutine、已分配堆内存、uptime可读。JVM/GC/Hikari/线程等Java平台指标不伪造；Go数据库/执行器/活跃请求/丢弃序列使用`personalos_go_*`映射。Java专属dashboard调整属于P8。
- 主命令实际绑定`logging.level.root`和`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`。默认Go文本保留时间/级别/requestId，缺ID占位12个横线；ECS输出@timestamp/log.level/message/ecs.version/service/process.pid/requestId。不伪造Tomcat/boundedElastic线程名或Logback调用者。
- 原console pattern只接受基线模板并映射到上述Go文本；任意Logback模板/文件Appender/组件日志级别/其他结构格式明确拒绝。当前组件使用同一root级别，业务日志仍按各阶段检查脱敏。

## 最终门禁

P1父项只能在最终race/vet/build、原框架夹具/配置台账、真实TLS PostgreSQL/Redis/ACL/网络故障/恢复与五角色进程回归通过后关闭。P1不是全项目替换完成；2438项和五发布门禁继续独立追踪，严格check-complete在P8前预期失败。
