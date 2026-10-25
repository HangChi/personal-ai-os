---
description: "Task list for 阶段 14 — 任务标签"
---

# Tasks: 阶段 14 — 任务标签

**Input**: Design documents from `/specs/014-task-tags/`

---

## Phase 1: 存储与归一化

- [x] T001 `V10__create_task_tag.sql`：`task_tag(task_id, tag_index, tag)`，
      主键 `(task_id, tag_index)`、`ON DELETE CASCADE`、`tag` 上普通索引
- [x] T002 [P] `task/TaskTags`：`normalize(List<String>)` —— 去空白、去空、去重（保序）、
      校验单条 ≤20 与前缀数量 ≤5，超限抛 `BusinessException(VALIDATION_FAILED)`
- [x] T003 `TaskTagsTest`：5 条路径（正常、去重、去空白、超长、超数量）
- [x] T004 `TaskEntity` 加 `@ElementCollection + @CollectionTable + @OrderColumn`；构造器接收 tags
- [x] T005 `TaskDto` 加 `tags`；`TaskService.toDto` 映射

## Phase 2: 写路径与查询

- [x] T006 `CreateTaskRequest` 加 `tags`；`TaskService.create` 归一化后存入
- [x] T007 `UpdateTaskRequest` 加 `tags`（`null` 不变 / `[]` 清空）；`update` 接线
- [x] T008 `TaskQuery` 加 `tag`；`buildSpecification` 加 `join("tags") + equal`
- [x] T009 `TaskRepository.findDistinctTags()`（native query，去重有序）
- [x] T010 `TaskService.listTags()`；`TaskController` 加 `GET /tasks/tags` 与 `tag` 查询参数

## Phase 3: 集成测试

- [x] T011 `TaskTagsIntegrationTest`：
  - 创建带标签 → DTO 顺序与输入一致
  - 更新为另一组 → **整体替换**（不是追加）
  - 更新传 `[]` → 清空；不传 → 保持不变
  - 6 个标签 → 400/40000；21 字符标签 → 400/40000
  - 按标签筛选 → 只返回含该标签的任务，total 正确
  - 标签清单去重有序；删除任务后独有标签消失
- [x] T012 `./mvnw -B verify` 全绿

## Phase 4: Agent 工具

- [x] T013 `TaskTools.createTask` 加 `tags` 参数（可选）
- [x] T014 `TaskTools.findTasks` 加 `tag` 筛选参数
- [x] T015 现有工具测试仍全绿（参数变化不破坏既有调用）

## Phase 5: 前端

- [x] T016 `types/api.ts`：`TaskItem.tags`、`CreateTaskPayload.tags`、`UpdateTaskPayload.tags`
- [x] T017 `api/tasks.ts`：`listTags()`；`TaskListFilter` 加 `tag`
- [x] T018 `stores/task.ts`：加载标签清单；筛选状态变化时页码归零
- [x] T019 `TasksView`：表单多选可创建标签、列表展示标签、顶部标签筛选下拉
- [x] T020 `test/task-tags.test.mjs`：清空标签时提交空数组（而不是 undefined）
- [x] T021 `type-check` / `build` / `test:logic` 全绿

## Phase 6: 验证与文档

- [x] T022 真机：新建带标签 → 列表显示 → 筛选 → 清空筛选 → 编辑清空标签 → 标签从筛选下拉消失
- [x] T023 README 路线图加阶段 14
- [x] T024 提交：`docs(task): 阶段 14 规格` + `feat(task): 阶段 14 实现`

## 完成记录

（实现后回填）
