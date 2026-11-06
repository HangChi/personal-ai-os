# Tasks: 今日工作台与项目任务
## Phase 1: Setup
- [X] T001 完成规格、设计与契约 specs/034-task-workspace/{spec,plan,research,data-model,quickstart}.md
## Phase 2: Foundation
- [X] T002 在 backend/src/main/resources/db/migration/V43__task_workspace.sql 增加项目、任务关联、周期字段与原子不变量
- [X] T003 在 backend-go/internal/business/task/workspace_integration_test.go 增加数据库边界/并发验证
## Phase 3: 今日工作台 US1
- [X] T004 [US1] 在 backend-go/internal/business/task/{model,postgres,http,workspace}.go 和 backend/src/main/java/com/personalos/task 增加时间筛选和最近执行接口
- [X] T005 [US1] 在 frontend/src/components/home/TodayWorkspace.vue 与 frontend/src/lib/today.ts 实现分区、重试、完成和定位
- [X] T006 [US1] 在 frontend/test 和 frontend/src/components/home 添加今日工作台测试
## Phase 4: 项目与子任务 US2
- [X] T007 [US2] 在 backend-go/internal/business/task 和 backend/src/main/java/com/personalos/task 接入项目 Service/API 与任务 DTO，名称「必填且最多 100 字」、描述「最多 2000 字」、父子「仅一级且同项目」
- [X] T008 [US2] 在 frontend/src/components/task、frontend/src/views/TasksView.vue 和 frontend/src/stores/task.ts 增加项目管理、筛选、子任务创建编辑
## Phase 5: 周期任务 US3
- [X] T009 [US3] 在前后端任务模型/表单接入 NONE/DAILY/WEEKDAYS/WEEKLY/MONTHLY，周期任务「必须有 dueAt」且时区有效、月锚点「1..31」
- [X] T010 [US3] 在 backend/src/main/java/com/personalos/export 与 backend-go/internal/business/{task,dataimport} 完成项目/任务字段备份往返
## Phase 6: Validation
- [X] T011 执行前后端检查和数据库/UI 验证并记录 specs/034-task-workspace/validation.md

依赖：T001→T002→T003/T004/T007；T004→T005→T006；T007→T008→T009→T010→T011。保留原分支及已有脏改动；按用户后续要求，将功能与联合验证记录分拆提交。
