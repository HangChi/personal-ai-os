# 实施任务与进度

按 P0 → P8 串行推进，不预先勾选后续阶段。每项任务完成后补充 progress.json 和 validation.md，运行基线检查，并按可审查的小阶段提交。

状态含义：`[x]` 只表示该具体任务完成，不表示整个 Java 文件或模块已经 verified。清单中的 file/route/tool/hook/test 项仍单独追踪。

## P0 基线与规格

- [x] T000 创建用户指定的 `go` 分支，固定 Java 对照提交 cb5d694。
- [x] T001 纳入全部 1,093 个基线文件及 SHA-256，包含 391 个主源码、154 个测试文件和 42 个迁移。
- [x] T002 索引路由/工具/调度与配置装配/测试声明/环境变量，明确框架端点与词法提取边界。
- [x] T003 建立文件、接口、工具、运行钩子和测试的迁移状态及证据格式。
- [x] T004 建立 drift check 和严格的 check-complete；验证提取器边界。
- [x] T005 完成规格、能力矩阵、兼容契约、架构决策、阶段任务和验证记录。

P0 提交：`f255fec` 固定基线，`b125f7e` 保存规格文档，`d59f85b` 保存首批 Go 基础代码。后两笔已由用户在本地提交，后续继续按可审查的小阶段提交；提交状态见 validation.md。

## P1 基础设施与数据兼容

验收状态：2026-11-03已完成。最终支持/拒绝边界见p1-foundation-contract.md，父项收口证据见p1-remaining.md及validation.md最终验收。以下子任务文字保留各批提交当时状态，不覆盖后续收口结论。P2进度见下文。

- [x] T100 创建可编译的 Go 模块与迁移诊断入口，默认回环 8081、不读现有 .env、不触碰现有数据、readyz 保持未就绪。实际进程监听/退出另列 T100a。
- [x] T100a 权限调整后以真实二进制分别启动五种角色，验证 livez/readyz、HEAD/405/未知 API、Worker 空 404、SIGTERM 零退出及端口释放；脚本仅使用临时目录/最小环境，未访问现有数据。完整依赖与 Worker 生命周期仍属 T108。
- [x] T101 移植 ApiResponse、完整 ErrorCode、PageDto 的 of/ofPage/offsetOverflows、RuntimeRole 和 RequestId 基础契约，增加边界测试。仅基础代码实现，不代表全量 P1/异步传播 verified。
- [x] T102 决策并锁定 PostgreSQL 驱动/连接池、数据库配置、事务接口、超时/退出与最小权限；形成 research 补充。
- [x] T102a 实现标准库显式 Connector 连接池、受限获取/探测、显式事务/独立事务、错误与 panic 回滚、取消/资源释放/错误脱敏，验证 32 路并发。真实驱动另列 T102b，T102 仍未完成。
- [x] T102b 固定 pgx v5.12.0 与许可证，提供显式配置/脱敏/UTC/受限 TLS 连接器；独立临时 PostgreSQL 验证提交/回滚/只读/隔离/类型、真实取消与并发池上限。未接入应用、未验证真实 TLS/最小权限/全部数据源配置，T102 仍未完成。
- [x] T102c 在自建 CA/独立 TLS PostgreSQL 复验五个集成包，实际验证证书/主机名/明文拒绝；以独立无管理权限账号完成事务探针并拒绝越权。完整 schema 权限与应用装配仍属 T102。
- [x] T102d 在42迁移后的完整40表中以受限P1运行角色启动五角色，仅授权历史SELECT与两协调表SELECT/INSERT/UPDATE；真实TLS验证其余37表拒读、DDL/临时表/提权/历史写拒绝、撤权启动失败与零连接泄漏。后续业务权限按模块追加。
- [x] T103 在隔离数据库重放全部 42 个 SQL；记录 schema、历史/锁、约束、向量与 Flyway 执行权。验证空库及存量备份启动。
- [x] T103a 提供显式原 SQL 目录校验，逐一比对全部 42 个基线 SHA-256；真实临时 PostgreSQL 完成空 schema/合成历史数据两次 V1–V42 重放，验证最终 39 张表/13 个触发器/pgvector/关键约束与数据搬迁。没有伪造 Flyway 历史；执行权、历史/锁、完整结构差分与备份恢复仍属 T103。
- [x] T103b 实际基线 Flyway 12.4.0 执行42原迁移/validate/二次零执行，固定42脚本和12边界的真实CRC32；Go只读repeatable-read校验历史/脚本/顺序/checksum/成功，拒绝缺失/损坏/未来/未支持标记，不写历史。完整结构差分、锁/备份恢复仍属T103。
- [x] T103c 在同一临时集群两个独立Flyway执行器真实竞争，合计恰好执行42迁移且再次零执行；Java真实结构与Go空库/合成升级重放的七类定义逐项相同（39表/287列/116约束/84索引/10序列/13触发器/9函数），实际加列反例被检测。备份恢复、部署角色及未支持历史标记仍属T103。
- [x] T103d 真实pg_dump/pg_restore合成数据到同一临时集群独立空库；42历史、七类DDL、40表全部行列摘要/10序列、向量/bytea/Unicode/时区及恢复后继续写/约束通过。仅CHECK/索引用同版本临时表重解析，并以两个条件变更反例验证未掩盖差异；非用户备份/生产ACL/真实密文验收。
- [x] T103e 在专用非空旧schema放原V2表/虚构聊天数据，真实Flyway默认baseline1→41 SQL→validate/二次零执行；Go显式允许精确V1标记、普通构造仍严格，SQL计数/当前版本/基线版本分开。只读历史/七类DDL/旧数据与损坏副本拒绝通过；自定义基线/SCHEMA/生产权限仍未覆盖。
- [x] T103f 在runner合成pg_dump恢复库实际启动/关闭五种Foundation角色，验证42历史、HTTP角色隔离、真实模型协调租约与池关闭；仅接受本次runner恢复库，不接触用户数据。
- [x] T104 对齐 .env 加载优先级、application 多文档/profile/属性默认值、prod 启动守卫；逐个审查所有配置类和 @Value。
- [x] T104a 完成 .env 解析/查找/优先级及生产安全校验子集、运行角色 profile 覆盖，并在启动前拒绝尚未完成的生产/认证/CSRF 模式；默认不读本地文件。T104 的 YAML/全量属性/动态 MCP 绑定仍待完成。
- [x] T104b 显式时长/数据大小转换器保留null、Java单位/大小写/空白/ISO符号/精度；真实Boot转换器228项逐项对照，超出Go int64纳秒的8个Java合法值明确拒绝且不截断。补充Java秒数中间加法顺序/Unicode折叠反例；尚未完成全属性绑定或装入主入口。
- [x] T104c 固定全部12配置record/67字段、70处@Value、8处getProperty与7 YAML文件/17文档/205属性来源；真实Spring/原生产守卫4场景发现并对齐Cookie canonical路径与系统环境优先级。索引默认重生成比较；非完整Binder/profile装配，T104保持开放。
- [x] T104d 显式绑定已解析canonical字符串map到Auth/RateLimit/AgentExecution三类配置；真实Binder.bindOrCreate的241项对照覆盖注解默认/空值/整数Unicode与hex/正值校验/时长，4项Java超Go范围明确拒绝。验证凭据脱敏/副本、1000次并发及真实BCrypt锁定/本地桶接线；不读取环境/展开占位符或装入主应用，T104保持开放。
- [x] T104e 显式MCP动态server/map/list/set与多来源覆盖绑定；新增157项真实Binder结果，四类配置共398项。覆盖.env原场景、下划线透传键实际变点分键、列表整体覆盖/空项/缺口、非正时长默认、全部容器副本/脱敏与1000次并发。不启动MCP命令/协议或加载ConfigData，完整T104与P7运行时继续开放。
- [x] T104f 补齐其余Plan/Calendar/AI/Context/Protection/App/Knowledge/Memory八类canonical绑定，全部12类共1475项真实Binder对照；保留primitive零值、空时长不创建nested、归一化前Java时长范围、long/Double语法、非有限值、集合与凭据/提示脱敏和副本。未完成ConfigData/profile/全来源接线，T104保持开放。
- [x] T104g 从原7 YAML/17文档生成带来源哈希的内置基线，提供显式来源组合/profile顺序/占位符解析；133场景×189属性直接对照真实Boot ConfigData。区分dotenv晚于ConfigData导致的profile文件不重载，保留缺省/空值/别名冲突/循环拒绝/转义；不读文件或宿主变量，外部导入/位置/profile分组明确拒绝。完整Binder到应用与外部配置能力仍属T104。
- [x] T104h 提供P1配置组合：从显式Properties绑定角色/Auth/RateLimit/App、安全开关、模型并发与专用Redis条件/namespace；保留ConditionalOnProperty精确true、真实基线每分钟60次与dotenv不重载profile。生产守卫继续拒绝；连接与其余业务配置仍需显式装配，T104保持开放。
- [x] T104i 为70处@Value/8处getProperty和动态键建立78项逐条台账，校验未知来源/遗漏/无效证据；接通会话/上传/日志及框架配置支持白名单，未支持扩展在连接前明确拒绝。数据源/迁移账号/外部ConfigData与后续业务配置边界纳入P1契约。
- [x] T105 实现认证/失败锁定/会话/轮换/注销/me/CSRF/Cookie/匿名白名单，移植 Auth*、SharedSession、Security 与生产验证相关断言。
- [x] T105a 移植单用户凭据、BCrypt 2a/cost10、Java strip/空值与失败锁定；固定真实 Java 编码器的 74 项 Unicode/NUL/长度边界对照，串行化并发校验。会话/CSRF/配置与应用接线仍属 T105。
- [x] T105b 实现原子本地会话/过期续期/登录轮换/注销/me/CSRF/Cookie 和可组合 HTTP 安全链；真实回环 CookieJar 验证登录前后令牌/旧 ID 失效，补充表单令牌/匿名/错误/路径边界。Redis/完整框架差分/应用接线仍属 T105。
- [x] T105c 固定 go-redis v9.23.0，提供显式专用 URI/TLS 配置、2 秒操作/无命令重试/脱敏关闭；真实 Redis 验证独立 Go 命名空间、Lua 会话轮换/续期/首次 CSRF/注销、两 HTTP 实例与独立 OS 进程。实际 TLS/ACL/断网及应用配置装配仍待验。
- [x] T105d 在临时自建 CA/独立服务端证书的真实 Redis TLS 容器复验客户端、会话与限流；验证受信证书成功、未知 CA/主机名不符/明文降级拒绝。未验证生产 ACL/网络分区/配置与应用装配，T105/T107 保持开放。
- [x] T105e 真实 Redis 两个独立最小 ACL 账号验证会话/模型桶功能及管理命令/键空间/脚本越权拒绝；ACL 错误在 HTTP/限流脱敏拒绝、不回退。透明代理在 plain/TLS 丢掉已执行写入的响应，证明不重放且新请求可重连。完整网络分区与应用装配仍待完成。
- [x] T105f 统一生产multipart惰性解析/CSRF正文token/业务文件访问及请求末清理；本地会话超时/自定义Cookie名称路径域HttpOnly/SameSite接线，登录注销使用同一Cookie属性。共享模式保留原固定超时/序列化策略，不支持的Servlet扩展显式拒绝。
- [x] T106 实现真实健康探测、框架错误/校验/内容协商/multipart/404/405、JSON 与时间/Unicode/空值规则。
- [x] T106a 移植真实 SELECT 1 健康报告、关键数据库/可选模型分量与 UTC/统一信封；GET/HEAD/405/匿名安全链、受限探测、真实临时库断连 DOWN/恢复 UP 与池耗尽验证。未挂载诊断应用，Actuator/完整框架差分仍属 T106。
- [x] T106b 移植显式业务异常与HTTP异常边界；离线真实Java处理器36响应/405日志/断连契约对照，校验/404/405/406/上传/约束/未知异常均脱敏。真实HTTP验证已提交SSE错误中止而不混入JSON，HEAD/panic/103/Flusher/Hijack/错误优先级通过；未替代Servlet解析、自动路由与实际业务装配。
- [x] T106c 运行原基础源码与固定Boot的嵌入式Tomcat/Security/Jackson/Validator/Actuator生成200个真实HTTP场景；先逐项对照其中156个认证/匿名/CSRF/路由场景，修正精确字段名、String标量转换、重复字段、尾随JSON、媒体类型/字符集、Accept、405文案/无Allow和MVC/安全链不同Content-Type。Actuator剩余44例另行补齐，multipart/通用业务DTO仍开放。
- [x] T106d 扩展真实Servlet夹具到220例并全部逐项对照：非monitoring业务端口健康/信息、monitoring端口隔离、vendor JSON/Accept、健康分组/503、匿名隐藏组件/空404、认证后详情与分量路径。接入原exposure/details/info开关和实际数据库/Redis/磁盘探测；不冒充完整Micrometer或所有扩展配置，通用multipart/DTO仍待验收。
- [x] T106e 在真实Servlet运行原HealthController/HealthService并以合成数据库/模型状态适配器扩展健康68例，累计364基础场景全部对照；修正业务健康媒体类型/HEAD/OPTIONS/405/Accept及探测先于协商，DOWN仍HTTP200。共享控制器JSON协商保留具体+json媒体类型，通用multipart/DTO仍开放。
- [x] T106f 提供有界multipart临时文件解析与canonical大小绑定，保留空/原始文件名、nil媒体类型、重复顺序和普通字段单片限制；152个真实Servlet参数/分片绑定场景通过，累计516基础场景。验证二进制/分块总量/取消/失败清理和32路隔离；无生产fixture端点，业务上传与完整CSRF表单装配仍待接线。
- [x] T106g 扩展真实Servlet到744例并全部逐项对照，新增多值Accept质量/非法项/带引号参数/字符集与MVC/Actuator差异；实际UTF-8/16/32字节编码而非仅改响应头。全14包race、integration vet/build及10个Python边界测试通过，仍不宣称所有未来DTO已验收。
- [x] T106h 扩展真实Servlet至844例并比较全部安全头，修正容器HTML/框架JSON路径与方法拒绝、X-XSS-Protection、缓存头和CSRF正文超限413；对齐默认50片/512字节头及边界，不改变业务上传范围。
- [x] T107 实现请求 ID 异步传播、模型路径、令牌桶、数据库协调/租约/API 预留容量、专用 Redis 限流和故障回退。
- [x] T107a 移植独立构造的本地令牌桶、默认值/参数校验，覆盖初始容量、平滑分数补充、回拨保护和 1,000 路并发；不用于应用共享限流，不代表 T107 完成。
- [x] T107b 移植模型路径/限流 HTTP 中间件和共享后端选择/故障策略，以固定 Java 实际执行的 216 项路径结果对照；受控替身覆盖 Redis 不回退、PostgreSQL 错误透传、指标回调及 429/日志脱敏。未接应用、真实存储或指标系统，T107 仍未完成。
- [x] T107c 移植 DatabaseCoordination.consume 的独立事务/数据库时钟/原子共享桶，使用 V37 原 SQL；真实 PostgreSQL 验证跨池 128 路/两个 OS 进程共享预算、分数/回拨/容量变化/业务回滚、锁等待取消及缺表拒绝。租约/Redis/应用装配与网络故障仍待完成，T107 保持未完成。
- [x] T107d 移植数据库租约/120 秒租期/10 秒续租/SKIP LOCKED/fencing/owner 隔离与关闭；增加显式事务完成回调并验证提交/回滚/panic 延迟释放、32 路竞争、续租故障与实际定时循环。应用/Worker 装配、真实断网与不确定提交仍待完成。
- [x] T107e 移植本地/共享模型并发门控与 Worker API 预留、失败清理和幂等释放；真 PostgreSQL 验证三种 Worker/combined/limit=1、跨池与获取/释放故障；HTTP 中间件验证 SSE 生命周期、取消/panic 清理和普通路径绕过。完整网络/前端/应用装配仍待验收。
- [x] T107f 移植 RedisRateBucket 原 Lua/TIME/命名空间哈希/毫秒周期与 150ms 操作边界；真实 Redis 验证 32/128 路竞争及两个独立 OS 进程共享预算、分数/回拨/配置拒绝/TTL/取消、故障不回退和错误指标回调。实际 TLS/不确定响应与应用装配仍待完成。
- [x] T107g 移植有界轮末执行器：1/2并发、32队列、60s空闲退出、满载丢弃、排空/停止/等待与脱敏panic隔离；提交时仅复制请求ID、不保留HTTP取消/身份，验证1000路和20轮race。真实流式调度/摘要/记忆与应用接线仍属后续任务。
- [x] T107h 在自建TLS服务前注入真实TCP分区/恢复，验证PG探测与共享预算拒绝、有效租约不被抢占及恢复释放；专用Redis会话/限流独立故障和恢复原身份、无PG回退。丢弃实际COMMIT响应后返回超时，直连确认一次持久副作用，无透明重试。
- [x] T107i 在真实TLS Foundation验证HTTP→有界后台执行器只复制请求ID，不继承HTTP取消/deadline/身份/私有值；ECS与错误/panic日志传播通过。真实摘要/记忆及持久Worker仍按业务阶段迁移。
- [x] T108 验证五种角色、Worker 业务隔离、管理端口/认证、连接池与优雅退出；接入基础指标和日志。
- [x] T108a 提供显式Foundation组合：自有池/Flyway只读校验、Auth/CSRF、共享桶/模型租约、健康与轮末执行器；真实TLS临时库验证两个实例/五角色、保护早于认证、CookieJar登录轮换/注销、退出取消/排空/释放租约后关池及启动失败无连接泄漏。主命令仍离线诊断；管理端口/指标/Redis组合/完整Worker待验，T108保持开放。
- [x] T108b monitoring有效profile装配独立管理链，只允许精确GET health/prometheus，HEAD及业务端点403；Actuator无业务信封/内部详情，真实DOWN503。接入限流count/sum/Redis错误和池/执行器最小指标；显式独立回环双监听器验证权限隔离、正常退出、异常联动关闭、SSE宽限取消与端口释放。完整观测/CLI/业务Worker仍待完成，T108保持开放。
- [x] T108c 在脚本同时新建的PostgreSQL/Redis TLS容器与独立会话/限流ACL账号中验证完整Foundation双实例：跨实例CSRF/登录/共享会话/共享预算、客户端故障拒绝/指标/管理DOWN、无PostgreSQL或本地会话回退、借用客户端不被Foundation关闭。全部app回归通过；故障注入是关闭客户端，不冒充真实网络分区，完整T108/T109仍开放。
- [x] T108d 主命令增加显式 --foundation/绝对迁移目录/V1基线开关，使用系统快照与可选dotenv接入已有基础应用；显式数据库参数、canonical数据源、基线Hikari池上限/毫秒等待、独立Redis URI/根证书、双监听与资源所有权接线。默认离线诊断和生产拒绝保留；单测/race/vet/build通过，真实基础进程另行验收，P1父项仍开放。
- [x] T108e 使用真实race二进制/五角色/双端口，在自建TLS PostgreSQL与专用Redis ACL下验证跨OS共享预算、认证/CSRF/登录轮换/注销、跨进程共享会话、profile池上限、SIGTERM零退出/端口/连接释放，以及缺项/生产/迁移错误/管理端口冲突启动拒绝。未以此替代真实网络故障或业务Worker验收，P1父项保持开放。
- [x] T108f 接入业务Actuator metrics列表/单项/标签过滤、COUNT/TOTAL_TIME/MAX与Redis错误counter；只列真实采集项，保留SimpleMeterRegistry窗口过期。真实Servlet夹具扩展292例全部对照，实际TLS PostgreSQL允许/拒绝请求产生的两次计量验证通过；完整HTTP/业务histogram与运行指标仍待完成。
- [x] T108g 接入HTTP真正结束后的timer/活跃数、SSE取消/panic中止一次计量、256序列上限、monitoring histogram与Go运行指标；真实Spring约定160标签场景及Micrometer68桶对照。CLI实际装配root日志级别/ECS/requestId并在五角色真实进程检查。
- [x] T109 P1 真数据库/安全/并发回归通过；progress 逐项填证据并提交后进入 P2。

## P2 幂等、确认与任务

- [x] T200 移植 JSON 规范化/SHA256、幂等占位/竞争/摘要冲突/结果回放/失败/HTTP 陈旧占位与 TOOL 结果不明保护。
- [x] T201 移植确认注册表、令牌、期限、状态、归属、处理器执行/取消/失败与并发消费；保留后续日程/MCP 动作扩展点。
- [x] T202 移植任务数据/DTO/校验、CRUD/筛选/统计/分页、完成/重开与 previous_status。
- [x] T203 移植标签 Unicode 长度/归一化/去重/顺序/上限；PATCH 省略/清空和任务提醒同步。
- [x] T204 移植任务查询/导出/搜索贡献接口职责；先测试实现，不提前宣告跨模块端点完成。
- [x] T205 任务 9 条路由、幂等/确认和相关 Java 单测/集成测试全部对照；验证跨连接竞争与数据库副作用后提交。

2026-11-03审查补验：已修复T200/T205的PATCH可空Boolean摘要及确认/取消JSON绑定差异；新增原Java摘要回放、38个确认绑定场景与真实库副作用回归。证据见validation.md的“P2审查修复”。不据此解除全量发布门禁。

## P3 模型配置与协议

验收状态：2026-11-03完成T300–T305，收口职责、原测试映射及实际命令见[p3-completion.md](p3-completion.md)。T300a/T301a保留首批历史说明，后续已由父项覆盖。会话持久化/Advisor/预算器消费属于P4，未提前勾选；全量发布守卫保留。

- [x] T300 移植模型配置与档案存储/DTO、CRUD/激活/排序/会话模型继承、启动应用与运行时解析。
- [x] T300a 完成配置/档案读取、逐字段回落与来源、安全DTO、active/V20解析及有序批量模型清单；通过原Java配置夹具和原42迁移TLS数据库验证。CRUD/激活/排序写入、会话继承、启动/运行时模型应用仍属T300。
- [x] T301 验证并移植 ModelKeyConverter 的新旧密文、掩码/保留原密钥/更新语义，禁止真实密钥进入日志/fixtures。
- [x] T301a 移植原AES-GCM格式、旧明文/禁用行为、随机IV/认证/错误脱敏与UTF-16掩码；原Java↔Go双向密文及真实库Java密文读取通过。保留/清空/更新密钥的完整业务写路径仍属T301。
- [x] T302 调研/固定模型协议实现，覆盖 OpenAI 兼容/Ollama、图片、工具 schema、额外请求体和重试边界。
- [x] T303 移植连接测试、模型列表与窗口探测/注册/证据、音频状态与转写、usage/超时/取消。
- [x] T304 移植 embedding 包装/本地缓存/Redis 缓存、模型隔离、TTL/容量/观测/故障回退。
- [x] T305 以本地模拟协议验证模型/档案全部路由及 Java 相关断言，保存差分后提交。

## P4 对话、Agent 与持久化交互

验收状态：2026-11-04完成T400–T409并接入显式Foundation/CLI；T400a保留首批历史。职责、兼容差异、真实验证见[p4-completion.md](p4-completion.md)，45类/379声明映射见[p4-test-mapping.md](p4-test-mapping.md)。原前端API22组真实联调及既有逻辑/组件通过；无浏览器视觉/OS强杀/真实用户备份验收。P5–P8及五个发布门禁继续开放。

- [x] T400 移植会话/消息/引用存储、分页/排序/搜索/改名/模型、删除、rewind 和 Markdown 导出。
- [x] T400a 完成独立会话存储/九条HTTP路由：显式事务/并发序号、分页搜索/改名模型、引用快照/批量读取、rewind摘要失效/提交后清理端口及Markdown；206个固定原Java差分、真实TLS库六组及安全组合测试通过。未装配生产Foundation/CLI，真实附件生命周期/完整Memory与控制器职责仍开放，详见[p4-storage.md](p4-storage.md)。
- [x] T401 移植提示贡献顺序、历史窗口/摘要、token/字符预算、模型窗口、输出/图片/tool schema 预留与上下文过大保护。
- [x] T402 移植工具注册、参数校验/规范化、进度/审计/幂等、模型/工具预算与同参数失败去重/终止。
- [x] T403 移植计划工具/状态/依赖/步骤记录、提示控制、未完成计划防提前结束、Calendar 写防护。
- [x] T404 移植旧 POST SSE 事件/心跳、前置校验、空输出/usage、超时/取消、消息/引用/用量落库。
- [x] T405 移植持久化 runs 状态机、请求幂等/会话互斥/租约、事件游标/批次/Hub/交付、显式停止/继续/确认完成。
- [x] T406 移植启动恢复、未知结果暂停、成功工具结果复用与续跑；验证前端断开不取消后台工作。
- [x] T407 移植 askUser/问题回答/WAITING_USER、QUEUE/STEER、编辑/取消/转引导、安全边界、刷新与停止后队列保留。
- [x] T408 移植附件上传/类型大小/路径安全/读取/上下文、本地与 S3、引用保留与孤儿清理，包含语音入口的调用链。
- [x] T409 移植用量统计与流指标，验证新旧流以及前端对话页全部交互；注入失败/重启/并发并保存差分后提交。

## P5 记忆、日历与提醒投递

- [ ] T500 移植记忆 CRUD/置顶/来源、hash 去重、相似/新近度排序、替代关系、命中统计/常驻预算。
- [ ] T501 移植自动记忆事实解析/沉淀开关/限制、向量写入/超时降级/存量回填。
- [ ] T502 移植日程数据/重复展开/范围/时区/提前量、CRUD 与 Agent 确认处理器。
- [ ] T503 移植任务/日程提醒扫描、物化去重、窗口补偿、已读和手工扫描。
- [ ] T504 移植提醒 SSE 快照/心跳/轮询和 RabbitMQ fanout 广播；双 API 实例验证已读与到点同步。
- [ ] T505 调研/移植 Web Push 的订阅/VAPID/加密/过期/领取/重试，使用真实浏览器订阅验证。
- [ ] T506 移植 Webhook URL/签名/稳定 ID/启用时间/窗口/租约/退避/重试分类与上限、禁重定向和数据保留。
- [ ] T507 对照记忆/日程/提醒/推送/Webhook 全部 Java 断言并做停机恢复、DST/月底和多副本测试后提交。

## P6 知识、RAG 与后台运行

- [ ] T600 移植文件名/扩展名/大小/短文拒收、文本/PDF/DOCX/图像读入与视觉回退，固定解析依赖与许可证。
- [ ] T601 移植 Markdown 结构切分/重叠/最小块，pgvector 维度/索引、批写、文档锁/原子替换/删除与引用元数据。
- [ ] T602 移植异步索引任务/进度/负载/摘要、领取/续租/失效重试/清理和最终提交 fencing。
- [ ] T603 移植向量+关键词召回/IDF/RRF、LIKE 转义、排序/阈值、KnowledgeSearchPort 与可选 OpenSearch。
- [ ] T604 移植搜索投影 Outbox/重建/版本/租约、RabbitMQ durable signal/publisher confirm/多执行器唤醒及轮询兜底。
- [ ] T605 移植多阶段 RAG/预算/证据/reranker/澄清/冲突/拒答/引用、问答与聊天共用装配。
- [ ] T606 移植空间/来源/元数据/版本/异步审查/片段引用核验/人工冲突解决与版本失效、review 角色租约。
- [ ] T607 移植 Redis candidate cache 的隔离/TTL/generation/失效和关闭/故障退路。
- [ ] T608 真 PostgreSQL/pgvector、RabbitMQ、Redis、OpenSearch 下验证 API+index/review/delivery、双 API、重复/丢失/乱序/崩溃；可选设施关闭模式也通过。
- [ ] T609 适配现有 RAG 评估语料/脚本，保存召回、引用、延迟/队列等对照结果与全部 Java 断言映射后提交。

## P7 MCP、搜索与数据交换

- [ ] T700 移植外部 MCP 配置/验证、stdio/HTTP 生命周期、命名/冲突/名单、失败降级、状态与刷新。
- [ ] T701 移植 AUTO/CONFIRM 默认策略、摘要/确认执行、幂等/预算/审计；不能把外部写工具直接设为自动。
- [ ] T702 实现对外 MCP 协议入口、独立开关/认证/CSRF 例外和四个只读工具，测试禁止递归导出其他工具。
- [ ] T703 移植五模块搜索贡献器/转义/分组/排序/限额与全局端点。
- [ ] T704 移植流式 JSON 导出与模块贡献器；移植空库导入/格式版本/20MB/校验/事务/向量与引用恢复边界。
- [ ] T705 MCP 真实协议模拟器、导出→空库导入→查询对照、全局搜索与对应 Java 用例通过后提交。

## P8 全量验证、运维与切换

- [ ] T800 适配 Go 镜像/Compose/prod/Caddy、独立管理端口/角色、凭据与非 root 运行，不替换仍在用的 Java 配置。
- [ ] T801 适配本地启动/停止/Worker discovery、备份恢复/保留清理、Push key、监控/告警/仪表盘与评估脚本。
- [ ] T802 跑全量前端 lint/format/type-check/logic/component/build，现有 E2E 接 Go 运行；页面/组件/通知/上传下载逐项验收。
- [ ] T803 全部 Java 测试文件与测试声明映射到 Go/差分证据；检查全部清单的职责是否确实覆盖，不按数量勾选。
- [ ] T804 跑完整真实基础设施回归、故障恢复、存量备份升级与恢复、性能/资源/召回质量对照。
- [ ] T805 完成切换演练：停止入口写入/全部 Worker、备份、部署 Go、重新登录、校验数据/副作用、核查回退路径。
- [ ] T806 五个 release_gates verified，运行 check-complete，人工审查所有证据。
- [ ] T807 更新宪法/README/贡献/开发/运维/架构/接口与安全文档的 Go 说明，保持迁移历史可追溯。
- [ ] T808 用户验收后再决定 Java 目录的归档；当前不删除原实现，不自动部署或合并 main。

## 单个清单项的记账示例

```json
{
  "file:backend/src/main/java/com/personalos/task/service/TaskService.java": {
    "status": "implemented",
    "evidence": ["backend-go/internal/task/service.go", "backend-go/internal/task/service_test.go"],
    "note": "代码与本地单测已完成；真实数据库和 Java 差分未通过前不得改成 verified"
  }
}
```

示例路径尚不存在，不能复制到当前 progress.json 冒充证据。复用资产也要经过验证后再置 verified；测试条目必须指向替代断言与实际运行报告，而不是只指向 Java 原文件。
