# API 契约
前缀 /api/v1，统一 ApiResponse 错误封装及已有认证。

- GET /projects：返回全部项目（含归档，界面区分）；按 createdAt,id 稳定排序。
- POST /projects：{name,description?}，201；复用 Idempotency-Key。
- PATCH /projects/{id}：{name?,description?,archived?}，200；归档不删除现有任务；可恢复。
- GET /workspace/runs：{items:[{id,conversationId,title,status,updatedAt}],total}；每会话仅最新 run，排除 COMPLETED；最多 20 条，按更新时间倒序。
- GET /tasks：新增 projectId、parentTaskId、incomplete、dueFrom(含)、dueBefore(不含) 筛选；保留分页和原筛选。
- POST/PATCH /tasks：新增 projectId、parentTaskId、recurrenceType、recurrenceTimezone；PATCH 以 clearProject/clearParent 表示解除；不接收生成标记或月锚点。
- TaskDto：新增上述字段及 recurrenceAnchorDay、recurrenceGenerated（备份可恢复）。
- 导出增加 projects；导入接受 projects 和新增任务字段，旧格式不变。

层级、时区、重复规则非法均返回 VALIDATION_FAILED；有关联子任务的删除返回可读错误。归档项目禁止新关联，保留已有任务处理。
