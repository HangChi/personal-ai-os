# Implementation Plan: 阶段 14 — 任务标签

**Branch**: `014-task-tags` | **Date**: 2026-10-25 | **Spec**: [spec.md](./spec.md)

## Summary

任务支持 0–5 个自由文本标签：新建 `task_tag` 表（V10），实体用 `@ElementCollection` 映射，
列表支持按标签精确筛选，另提供去重标签清单接口。Agent 工具同步支持。

**零新依赖**；1 个迁移；不改任何既有字段语义。

## Technical Context

- Java 21 / Spring Boot 4.1.1 / Spring Data JPA / Flyway / PostgreSQL 17
- 前端 Vue 3 + TS + Element Plus
- 测试：JUnit 5 + Testcontainers；前端 `.mjs`

## Constitution Check

| 原则 | 判定 | 依据 |
|---|---|---|
| I. 简单优先 | ✅ | 零依赖。**不**建标签库表（R-01）、**不**做颜色/统计、**不**做标签管理页（FR-010） |
| II. 边界即安全 | ✅ | 写路径仍在 Service；Agent 侧 `createTask` 加标签不构成数据破坏风险，不需要确认（与删除/改期区别对待） |
| III. 分层与写路径收敛 | ✅ | 归一化集中在 `TaskTags`（纯函数）；Controller 只解析参数；不暴露 Entity |
| IV. 数据变更可追溯 | ✅ | 唯一结构变更走 `V10__create_task_tag.sql`；`ddl-auto=validate` 会校验映射与表一致 |
| V. 可验证的交付 | ✅ | 归一化、替换/清空语义、筛选、清单去重、级联删除各有断言；真机走完整交互 |
| 工作流：阶段串行 | ✅ | 014 接在 013 之后 |

## Project Structure

**后端**
```
db/migration/V10__create_task_tag.sql   新：task_tag（task_id, tag_index, tag）
task/entity/TaskEntity.java            改：@ElementCollection + @OrderColumn
task/TaskTags.java                     新：归一化纯函数（去空白/去空/去重/校验）
task/dto/TaskDto.java                  改：加 tags
task/dto/CreateTaskRequest.java        改：加 tags
task/dto/UpdateTaskRequest.java        改：加 tags（null=不变，[]=清空）
task/dto/TaskQuery.java                改：加 tag
task/repository/TaskRepository.java    改：findDistinctTags()
task/service/TaskService.java          改：归一化接线、specification 加标签条件、listTags()
task/controller/TaskController.java    改：tag 参数 + GET /tasks/tags
agent/tool/TaskTools.java              改：createTask 加 tags；findTasks 加 tag
```

**前端**
```
src/types/api.ts           改：TaskItem.tags、payload.tags
src/api/tasks.ts           改：listTags()、payload 字段
src/stores/task.ts         改：tags 清单、筛选状态
src/views/TasksView.vue    改：表单多选输入、列表展示、筛选下拉
frontend/test/task-tags.test.mjs 新
```

## Phase 0 / Phase 1 结论

### R-01 独立表而不是逗号分隔列

「用一列存 `工作,学习`」看起来更省事，代价是：无法索引筛选（只能 `LIKE '%工作%'`，
会误命中 `工作台`）、无法保证唯一、分隔符出现在标签里就崩。

一张 `(task_id, tag_index, tag)` 的小表把这些都解决掉，且与既有 `@ElementCollection` 的
映射一一对应，不需要新代码模式。

### R-02 用 `List<String>` + `@OrderColumn` 而不是 `Set`

标签顺序在列表、导出、编辑回填三处都会被看到。用 `Set`（哪怕 `LinkedHashSet`）
在 JPA 持久化后顺序不保证，用户会看到「同样的数据，刷新后标签换了个位置」。
`tag_index` 列的成本只是一个 int。

### R-03 归一化集中在 `TaskTags`

「去空白 → 去空 → 去重 → 校验数量/长度」任何一步漏掉都会出问题：
漏去重会撞主键、漏去空会存进空标签、漏校验会在数据库层报长度错误（错误码难看）。
做成纯函数才能一条条断言。

### R-04 `null` 与 `[]` 必须可区分

更新语义：`tags == null` → 不改；`tags == []` → 清空。
这与 `dueAt` / `clearDueAt` 是同一类问题（阶段 2 已经踩过一次），
所以复用同样的约定，不另造一个 `clearTags` 标志 —— 数组本身就能表达「清空」。

### R-05 Agent 侧同步支持

如果只在 REST 加标签，「用自然语言给任务打标签」就做不到，
而这是本项目的主要交互方式。工具参数必须同步，否则功能对主要入口不可用。

## Complexity Tracking

无违例。新增一个迁移与一个纯函数工具类，均无替代方案能同时满足「可筛选 + 顺序稳定 + 无解析歧义」。
