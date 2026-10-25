# Implementation Plan: 阶段 16 — 列表分页与站内搜索

**Branch**: `016-list-and-search` | **Date**: 2026-10-25 | **Spec**: [spec.md](./spec.md)

## Summary

把任务模块（阶段 2）已确立的「`page` + `size` + `PageDto` + 关键词筛选」模式补到
知识库与会话两个模块。**保留全量 `list()` 供导出使用**，分页是新增的 `search()`。

零新依赖、零迁移。

## Technical Context

- Java 21 / Spring Boot 4.1.1 / Spring Data JPA / PostgreSQL 17
- 前端 Vue 3 + TS + Pinia + Element Plus
- 测试：Testcontainers；前端 `.mjs`

## Constitution Check

| 原则 | 判定 | 依据 |
|---|---|---|
| I. 简单优先 | ✅ | 零依赖零迁移；复用 `TaskQuery` 已确立的参数收敛口径，不另造一套；**不**做全局搜索（R-04） |
| II. 边界即安全 | ✅ | 只读增强；写路径不变 |
| III. 分层与写路径收敛 | ✅ | Controller 只解析参数；Specification 在 Service；对外 DTO |
| IV. 数据变更可追溯 | ✅ | 无结构变更 |
| V. 可验证的交付 | ✅ | 四条路径有断言（分页收敛 / 筛选 / 导出全量 / 消息数正确）；真机走翻页与搜索 |
| 工作流：阶段串行 | ✅ | 016 接在 015 之后 |

## Project Structure

**后端**
```
chat/repository/ChatSessionRepository.java         改：+ JpaSpecificationExecutor
chat/dto/ChatSessionQuery.java                     新：keyword / page / size
chat/service/ChatSessionService.java               改：+ search()
chat/controller/ChatSessionController.java         改：GET /sessions 加参数
knowledge/repository/KnowledgeDocumentRepository.java 改：+ JpaSpecificationExecutor
knowledge/dto/KnowledgeDocumentQuery.java          新
knowledge/service/KnowledgeDocumentService.java    改：+ search()
knowledge/controller/KnowledgeController.java      改：GET /documents 加参数
```

**前端**
```
src/types/api.ts           改：PageDto 类型复用（无需新类型）
src/api/knowledge.ts       改：listDocuments 带参数，返回 PageDto
src/api/chat.ts            改：listSessions 带参数，返回 PageDto
src/stores/knowledge.ts    改：page/size/total/keyword + 搜索归零
src/stores/chat.ts         改：同上
src/views/KnowledgeView.vue 改：搜索框 + 分页
src/views/ChatView.vue     改：搜索框 + 分页
frontend/test/list-paging.test.mjs 新
```

## Phase 0 / Phase 1 结论

### R-01 保留 `list()`，分页叫 `search()`

导出链路（阶段 12 的 `ChatExportContributor` / `KnowledgeExportContributor`）依赖全量数据。
若把 `list()` 直接改成分页，导出会变成「只能导出第一页」—— 那比现在更糟。

两个方法职责分明：`list()` = 全量（给导出与其他内部用途），
`search()` = 分页 + 筛选（给界面）。删掉 `list()` 留给将来「明确不再有全量消费者」时再做。

### R-02 SQL 层分页，不用内存切片

与阶段 2 的结论一致：会话与文档都只增不减，加载全量再切片的成本会随数据增长。
`repository.findAll(spec, PageRequest)` 的 `Page` 自带 `totalElements`，
既高效又能给出「过滤后总数」。

`PageDto` 的形状保持不变，只是 `total` 由数据库给。

### R-03 消息条数只对当前页做一次分组查询

原有实现已经避免了 N+1（一次 `countGroupedByConversationId`）。
分页后同样只对**当前页的 conversationIds** 做一次分组查询 —— 既保持 O(1) 次查询，
又不会因为给全量会话统计消息数而退化。

### R-04 不做全局搜索

跨模块搜索要决定：搜哪些模块、每模块返回几条、怎么排序、关键词高亮在哪做。
每一个选择都会显著改变体验，塞进本阶段只会得到一个「能搜但不好用」的结果。

### R-05 参数收敛复用任务模块口径

`page < 0 → 0`、`size <= 0 → 20`、`size > 100 → 100`、keyword trim、空串等同不筛。
同一个「翻页」概念在四个模块里应该是同一套行为，否则用户要在不同页面记不同规则。

## Complexity Tracking

无违例。两个新 Query record + 两个 Service 方法 + 两个 Controller 参数。
