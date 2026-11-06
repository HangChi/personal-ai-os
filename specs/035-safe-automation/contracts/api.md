# API contract（/api/v1）
两端使用现有统一信封和认证，Service处理写入。错误中文、不暴露内部堆栈。
- GET /calendar/events/ics：返回text/calendar附件；稳定本机UID。
- POST /calendar/events/ics/preview {content}：严格解析，返回digest、events、duplicateCount；不写业务。
- POST /calendar/events/ics/import {content,digest}：再次校验摘要和规则，原子导入，返回created/skipped；文件最多1MiB/200事件。
- GET /calendar/events/conflicts?from=&to=：最多31天，返回items/total及truncated，半开区间、不含自身同一实例。
- GET/POST /knowledge/captures、POST /knowledge/captures/{id}/refresh、GET /knowledge/captures/{id}/versions：来源和有界版本列表；正文为纯文本。
- GET/POST /workflows、PATCH /workflows/{id}、POST /workflows/{id}/run、GET /workflows/{id}/runs、POST /workflows/runs/{id}/retry：禁用不新建自动期次；重试只针对失败、每日预算预留，不自动触发外部动作。
- GET /maintenance：真实心跳、失败索引和投递、待处理Run及校验历史（各列表有界）。
- POST /maintenance/verify：最多20MiB业务JSON，纯校验并记录摘要/计数/问题，不调用导入或模型。
- POST /maintenance/drill-reports：有界外部CLI报告，明确external/unverified，不执行或冒称真实恢复。
