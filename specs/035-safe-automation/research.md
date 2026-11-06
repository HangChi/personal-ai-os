# Research
- Decision: 严格 ICS 子集（DATE、UTC、可识别 IANA TZID；不重复/每天/工作日/每周/每月和 UNTIL），拒绝 COUNT、例外和复杂 BY*。Rationale: 保持现有日历语义。Alternatives: 完整 RFC 引擎会引入无法映射的数据和额外运行依赖。
- Decision: UID 映射与导入事务锁；预览 digest 后确认，重复 UID 不覆盖现有日程。Rationale: 安全互通避免隐式改期。Alternatives: 静默 upsert 可能覆盖用户编辑。
- Decision: HttpClient5 自定义 DnsResolver / Go Transport 自定义 DialContext，对全部 DNS 结果分类并拨固定地址；不带代理/Cookie/凭据、不跟重定向。Rationale: 防 SSRF 与重绑定。Alternatives: 仅校验字符串或先解析再交默认客户端不安全。
- Decision: JDK ParserDelegator / Go x/net/html 提取正文并过滤脚本样式；来源唯一、内容摘要、正文版本，经已有索引队列推进成功版本。Rationale: 复用已有租约/fence和失败保留旧索引语义。Alternatives: 用治理 revision 当正文版本不成立。
- Decision: 只读确定性报告，零模型费用；周期、Run 和事件持久化，禁止通用恢复/继续将其转换为可写 Agent。Rationale: 先安全交付晨报/周报/资料状态巡检。Alternatives: 普通ChatRun.start默认可写，prompt限制不足以授权。
- Decision: Worker独立心跳表；任务租约不当作进程存活。完整恢复只由既有 CLI在独立随机库执行，中心展示指引与外部报告，不把JSON校验当恢复成功。Rationale: 不给服务额外系统权限。
- Existing anchors: ChatRunService/ChatRunRepository、chat/runs.go；KnowledgeIndexJobs/knowledge/jobs.go；scripts/dev/backup.py restore_drill；runtime_lease为租约而非心跳。
- No unresolved clarification; no new external service, provider or real-user data accessed during development.
