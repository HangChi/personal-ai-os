# Tasks: 安全自动化与资料互通
## Setup
- [X] T001 完成 specs/035-safe-automation/spec.md 和规格质量检查并提交
- [X] T002 完成 specs/035-safe-automation/{plan,research,data-model,quickstart}.md 与 contracts/api.md 并提交
- [X] T003 在 specs/035-safe-automation/tasks.md 明确独立切片及验证边界并提交
## US3 日历互通（独立先交付）
- [X] T004 [US3] 在两端 calendar 模块添加严格ICS codec和测试；文件「最多1MiB/200事件」、UID「最多512字符」，拒绝不能无损映射的规则
- [X] T005 [US3] 在 V44__calendar_exchange.sql 与两端calendar Service实现预览摘要、原子导入/UID去重、导出、半开区间冲突查询
- [X] T006 [US3] 在 frontend/src/components/calendar/CalendarExchange.vue 实现文件预览/确认/导出/冲突，完成双端和UI检查后提交
## US2 URL采集
- [X] T007 [US2] 在两端knowledge模块添加安全抓取/HTML提取器及测试，正文「最多1MiB」、URL「最多2048字符」，拒绝私网/重绑定/重定向
- [X] T008 [US2] 在 V45__knowledge_captures.sql 与两端采集/索引任务接入来源去重、成功才推进的正文版本和历史
- [X] T009 [US2] 在 frontend/src/components/knowledge/KnowledgeCaptures.vue 添加采集/刷新/版本及状态，完成检查后提交
## US1 定时只读报告
- [ ] T010 [US1] 在 V46__readonly_workflows.sql 与两端workflow模块实现定义、时区调度、唯一期次、租约、每日预算及「最多3次」重试测试
- [ ] T011 [US1] 在两端chat恢复入口持久隔离只读工作流Run，生成固定只读报告并记录「零模型调用」，max_items「1..100」、max_output_chars「1000..20000」、max_runs_per_day「1..24」
- [ ] T012 [US1] 在 frontend/src/views/WorkflowsView.vue 增加计划/启停/手动执行/重试/历史，完成检查后提交
## US4 维护恢复
- [ ] T013 [US4] 在 V47__maintenance_center.sql 与两端maintenance模块添加真实心跳（「90秒」失联）、失败队列/待处理Run聚合和有界查询
- [ ] T014 [US4] 在两端maintenance模块实现「最多20MiB」业务备份纯校验与历史、外部演练报告（明确非独立验证），补充业务零写入测试
- [ ] T015 [US4] 在 frontend/src/views/MaintenanceView.vue 集中显示备份、校验、心跳、失败任务和既有独立库恢复演练指引，完成检查后提交
## Final validation
- [ ] T016 执行Java verify、Go race/vet/build、前端全检查、隔离数据库/浏览器并记录 specs/035-safe-automation/validation.md，提交最终验证

依赖：T001→T002→T003；T004→T005→T006；T007→T008→T009；T010→T011→T012；T013→T014→T015；各切片→T016。
并行机会：仅互不依赖文件的只读研究/检查；实现依功能串行，避免迁移号/共享路由冲突。先完成日历再采集、工作流和维护，每片独立提交；后端两种实现与共享前端一同验证，不部署。
