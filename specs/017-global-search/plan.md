# Implementation Plan: 阶段 17 — 跨模块全局搜索

**Branch**: `017-global-search` | **Date**: 2026-10-25 | **Spec**: [spec.md](./spec.md)

## Summary

新增 `GET /api/v1/search`，跨五个模块搜索并按模块分组返回。
各模块通过 `SearchContributor` SPI 提供自己的搜索实现，聚合服务只负责收集与容错。

**零新依赖、零迁移。**

## Technical Context

- Java 21 / Spring Boot 4.1.1 / Spring Data JPA / PostgreSQL 17
- 前端 Vue 3 + TS + Pinia + vue-router
- 测试：Testcontainers；前端 `.mjs`

## Constitution Check

| 原则 | 判定 | 依据 |
|---|---|---|
| I. 简单优先 | ✅ | 零依赖零迁移。**不**做全文索引 / 拼音 / 高亮 / 历史（R-07） |
| II. 边界即安全 | ✅ | 只读；写路径不变；单模块故障被隔离（R-06） |
| III. 分层与写路径收敛 | ✅ | **SPI 而非聚合服务 import 五个 Service**（R-01）；后端不返回前端路由（R-05） |
| IV. 数据变更可追溯 | ✅ | 无结构变更 |
| V. 可验证的交付 | ✅ | 五模块命中、分组、标题优先、故障隔离各有断言；真机走顶栏搜索到跳转 |
| 工作流：阶段串行 | ✅ | 017 接在 016 之后 |

## Project Structure

**后端**
```
common/search/SearchContributor.java   新：SPI（moduleName / kind / search）
common/search/SearchHit.java           新：kind + id + title + subtitle + matchedField
common/search/SearchGroup.java         新：module + kind + hits
common/search/GlobalSearchService.java 新：收集 + 排序 + 容错
common/search/SearchController.java    新：GET /search
task/TaskSearchContributor.java        新
chat/ChatSearchContributor.java        新（标题 + 消息内容，标题优先）
chat/repository/ChatMessageRepository.java 改：+ 按内容查 conversationId
calendar/CalendarSearchContributor.java 新
memory/MemorySearchContributor.java    新
knowledge/KnowledgeSearchContributor.java 新（标题/文件名/分块）
knowledge/service/VectorStoreInspector.java 改：+ 按内容查 documentId
```

**前端**
```
src/api/search.ts          新：globalSearch()
src/stores/search.ts       新：keyword / groups / loading / error / search()
src/views/SearchView.vue   新：分组结果页
src/App.vue                改：顶栏搜索框
src/router/index.ts        改：/search 路由
frontend/test/search.test.mjs 新
```

## Phase 0 / Phase 1 结论

### R-01 SPI 而不是聚合服务 import 五个 Service

如果 `GlobalSearchService` 直接注入 `TaskService` / `ChatSessionService` / …，
它会 import 全部五个模块 —— 将来加第六个模块就要改它，而「改一个原本正确的类」
正是回归的来源。这与既有的 `ToolContributor` / `ExportContributor` 是同一套思路：
**各模块自己说「我怎么搜」**。

### R-02 正文命中返回父对象

消息与分块的量级比元数据大 1~2 个数量级。若把单条消息、单个分块作为结果，
一次搜索可能返回几百条，分组就失去意义。命中正文时返回**会话 / 文档**，
并在 `matchedField` 标注「消息内容」/「正文」—— 用户因此知道「为什么是这条」。

### R-03 标题命中优先于正文命中

同一对象两类都命中时，标注标题。理由：标题命中通常意味着「这条就是关于这个的」，
正文命中可能只是提了一句。优先展示更可能的那个，同时**仍然只出现一次**
（按 id 去重）。

### R-04 分组 + 组内时间倒序，不做混排

混排会让「今天的待办」和「三年前的会话」交替出现，难以扫读。
分组让用户直接看出「这一类有几条」，符合「先定位模块、再看细节」的心智。

### R-05 后端不返回前端路由

结果条目给 `kind` + `id`，由前端维护 `kind → route` 映射表。
后端返回路由意味着它要知道前端路由表，两边会各自漂移；而 `kind` 是业务概念，
比路由稳定得多。

### R-06 单模块故障隔离

每个 contributor 的调用包 try-catch：失败记 warn、跳过该分组、请求仍返回 200。
聚合查询最坏的失败模式是「一个模块挂 → 整个搜索白屏」，
而用户此时最需要的是其他模块的结果。

### R-07 不做的事

全文索引（个人量级 `LIKE` 够用，上万条时该上独立搜索引擎而不是给 Postgres 加更多索引）、
关键词高亮（要转义用户输入，做错就是 XSS）、搜索历史（要决定存哪存多久，
而浏览器历史已覆盖大部分需求）、拼音匹配（需要额外词典）。

## Complexity Tracking

| 项 | 为什么必须引入 |
|---|---|
| 一个 SPI + 五个实现 | 替代「聚合服务 import 五个模块」；每个实现只有 20~40 行，且新增模块不需要改聚合服务 |
