# API contract（/api/v1）
两端使用现有统一信封和认证，Service处理写入。错误中文、不暴露内部堆栈。
- GET /calendar/events/ics：返回text/calendar附件；稳定本机UID。
- POST /calendar/events/ics/preview {content}：严格解析，返回digest、events、duplicateCount；不写业务。
- POST /calendar/events/ics/import {content,digest}：再次校验摘要和规则，原子导入，返回created/skipped；文件最多1MiB/200事件。
- GET /calendar/events/conflicts?from=&to=：最多31天，返回items/total及truncated，半开区间、不含自身同一实例。
- GET/POST /knowledge/captures、POST /knowledge/captures/{id}/refresh、GET /knowledge/captures/{id}/versions：来源和有界版本列表；正文为纯文本。
- GET/POST /workflows、PATCH /workflows/{id}、POST /workflows/{id}/run、GET /workflows/{id}/runs、POST /workflows/runs/{id}/retry：禁用不新建自动期次；重试只针对失败、每日预算预留，不自动触发外部动作。
- GET /maintenance：真实心跳、失败索引和投递、待处理Run及校验历史（各列表有界）。
- POST /maintenance/verify：Content-Type为application/json，直接提交最多20MiB原始UTF-8业务JSON（不是包在对象内的字符串）。合法与破损文件均返回校验记录，status为VALID/INVALID；digest为原始字节的SHA-256。超限/并发繁忙使用统一错误信封。只保存摘要/计数/最多100项问题，不调用业务导入或模型。
- POST /maintenance/drill-reports：最多16KiB JSON，字段tool（1..80字符）、summary（1..2000字符）、completedAt（带时区时间）、outcome（PASSED/FAILED）、可选artifactDigest（64位十六进制SHA-256）。返回status=UNVERIFIED、source=EXTERNAL、independentVerification=false；PASSED仅是外部填报结果，不表示服务已验证恢复。
- 维护列表：心跳最近50项，其余各最近20项，均含items/total；校验历史保留最近200项且不保存原文件。心跳30秒续写，90秒失联，7天过期清理。待处理Run只显示每会话最新的普通CHAT执行，排除只读报告；主动停止、等待回答/确认与中断分别显示。
