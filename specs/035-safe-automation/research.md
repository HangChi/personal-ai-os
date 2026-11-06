# Research
- Decision: 严格 ICS 子集（DATE、UTC、可识别 IANA TZID；不重复/每天/工作日/每周/每月和 UNTIL），拒绝 COUNT、例外和复杂 BY*。Rationale: 保持现有日历语义。Alternatives: 完整 RFC 引擎会引入无法映射的数据和额外运行依赖。
- Decision: UID 映射与导入事务锁；预览 digest 后确认，重复 UID 不覆盖现有日程。Rationale: 安全互通避免隐式改期。Alternatives: 静默 upsert 可能覆盖用户编辑。
- Decision: HttpClient5 自定义 DnsResolver / Go Transport 自定义 DialContext，对全部 DNS 结果分类并拨固定地址；不带代理/Cookie/凭据、不跟重定向。Rationale: 防 SSRF 与重绑定。Alternatives: 仅校验字符串或先解析再交默认客户端不安全。
- Decision: JDK ParserDelegator / Go x/net/html 提取正文并过滤脚本样式；来源唯一、内容摘要、正文版本，经已有索引队列推进成功版本。Rationale: 复用已有租约/fence和失败保留旧索引语义。Alternatives: 用治理 revision 当正文版本不成立。
- Decision: 只读确定性报告，零模型费用；周期、Run 和事件持久化，禁止通用恢复/继续将其转换为可写 Agent。Rationale: 先安全交付晨报/周报/资料状态巡检。Alternatives: 普通ChatRun.start默认可写，prompt限制不足以授权。
- Decision: Worker独立心跳表；任务租约不当作进程存活。完整恢复只由既有 CLI在独立随机库执行，中心展示指引与外部报告，不把JSON校验当恢复成功。Rationale: 不给服务额外系统权限。
- Existing anchors: ChatRunService/ChatRunRepository、chat/runs.go；KnowledgeIndexJobs/knowledge/jobs.go；scripts/dev/backup.py restore_drill；runtime_lease为租约而非心跳。
- No unresolved clarification; no new external service, provider or real-user data accessed during development.

- 实施确认：HttpClient5 5.6.4；Go x/net/html v0.62.0（官方模块代理实际发布于2026-10-27，Go1.26最低版本，兼容仓库Go1.27）。仅直接声明解析所需模块，不升级其它依赖。网页仅接受UTF-8、标准80/443端口，禁用内容压缩。并发4、总体15秒、DNS结果绑定连接；版本推进使用共享数据库触发器，沿用原索引事务。
- 实施确认：两端共用 V47 的 PostgreSQL 纯校验函数，避免 Java/Go 在类型、枚举、引用及循环规则上分歧；34组共同样例同时运行。纯规则函数可在只读事务执行，Service仅另外保存校验摘要；不复用有写入及嵌入模型副作用的业务导入器。
- 实施确认：维护接口按原始UTF-8字节计算SHA-256；业务JSON上限20MiB，外部报告16KiB，并发校验最多2个，处理15秒/SQL10秒超时。不保存上传原文；最近200条摘要、界面最近20条。心跳每30秒续写、90秒失联、7天过期清理；在线只表示进程续写正常，不代替任务成功指标。
- 实施确认：确定性报告输出写入独立只读会话并生成已完成的READONLY_WORKFLOW Run。会话持久标记及SQL防护阻止后续转为普通可写Agent；业务JSON导入导出保留标记，普通会话不增加false字段，033冻结oracle不修改。

## 复审修复决策（2026-11-06）
- V48使用OLD/NEW调度字段的IS DISTINCT FROM与重新启用条件，避免Service完整UPDATE把非调度编辑误当作改期；V49只补充jsonb解析的numeric_value_out_of_range捕获。原V1..V47不修改，避免Flyway校验和漂移；两端共用追加函数修复。
- ICS编码器接收按事件ID分组的来源UID映射，导出时批量读取；不将来源身份加入普通日程API DTO。本地事件仍以本地UUID生成稳定UID，导入另一个实例后将其作为来源UID继续保留。
- 替换Java URI.normalize与Go旧路径堆栈为同一字面点段算法。空段必须保留，不能把/a//b等同于/a/b；百分号编码与查询不解码重写。已有来源不做推测性合并，历史采集和索引不自动重建。
- ParserDelegator将template/iframe等未知容器的起止都发给handleSimpleTag，结束由ENDTAG属性标识；补齐回调，不换解析库或增加运行依赖。共享HTML样例验证隐藏文本排除及可见后文保留。
- 报告在采集状态/资料或日程/任务两个分区轮流取数：先异常或日程，单项预算也体现该优先级；某一区为空时不浪费名额。候选查询、总项数和字符上限保持有界，不增加模型/工具调用。
- Java使用NamedParameterJdbcTemplate的IN参数，Go使用ANY(uuid[])批量读取最多50期的尝试，再按execution_id分组。固定查询次数由真实JDBC/Go驱动计数验证，不用SQL文本匹配代替性能证据。
