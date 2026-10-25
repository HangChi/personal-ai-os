---
description: "Task list for 阶段 17 — 跨模块全局搜索"
---

# Tasks: 阶段 17 — 跨模块全局搜索

**Input**: Design documents from `/specs/017-global-search/`

---

## Phase 1: SPI 与聚合

- [x] T001 [P] `common/search/SearchHit`：`kind` / `id` / `title` / `subtitle` / `matchedField`
- [x] T002 [P] `common/search/SearchGroup`：`module` / `kind` / `hits`
- [x] T003 `common/search/SearchContributor`：`moduleName()` / `kind()` / `search(keyword, limit)`
- [x] T004 `GlobalSearchService`：收集 + 按 `moduleName` 排序 + **单模块失败隔离**（try-catch + warn）
- [x] T005 `SearchController`：`GET /api/v1/search`，校验 keyword 非空、≤100 字符，limit ≤20
- [x] T006 `SearchControllerTest`：空关键词 400 / 超长 400 / limit 收敛

## Phase 2: 五个贡献者

- [x] T007 [P] `TaskSearchContributor`：标题 / 描述，标注命中字段
- [x] T008 `ChatSearchContributor`：标题优先；消息内容命中返回**会话**并去重
- [x] T009 `ChatMessageRepository` 加「按内容查 conversationId」（分页 + 去重）
- [x] T010 [P] `CalendarSearchContributor`：标题 / 描述 / 地点
- [x] T011 [P] `MemorySearchContributor`：内容
- [x] T012 `KnowledgeSearchContributor`：标题 / 文件名 / 分块内容（命中分块返回**文档**）
- [x] T013 `VectorStoreInspector` 加「按内容查 documentId」

## Phase 3: 测试

- [x] T014 `GlobalSearchIntegrationTest`：五模块各造一条 → 五个分组、条数正确
- [x] T015 同测试：标题命中优先于内容命中；消息命中返回会话且只出现一次
- [x] T016 同测试：空关键词 400；limit 收敛；无命中时返回空 groups
- [x] T017 `./mvnw -B verify` 全绿

## Phase 4: 前端

- [x] T018 `api/search.ts`：`globalSearch(keyword, limit)`
- [x] T019 `stores/search.ts`：keyword / groups / loading / error / search
- [x] T020 `SearchView.vue`：按分组展示，空态文案，条目跳转
- [x] T021 `App.vue`：顶栏搜索框（回车跳 `/search?q=`）
- [x] T022 `router`：`/search` 路由；`kind → route` 映射表放前端
- [x] T023 `test/search.test.mjs`：kind→路由映射、空 groups 文案、失败处理
- [x] T024 `type-check` / `build` / `test:logic` 全绿

## Phase 5: 验证与文档

- [x] T025 真机：顶栏搜索 → 分组结果 → 点击跳转对应模块
- [x] T026 README 路线图加阶段 17
- [x] T027 提交：`docs(search): 阶段 17 规格` + `feat(search): 阶段 17 实现`

## 完成记录

**提交**：`9955c35` feat(search): 阶段 17 跨模块全局搜索（T001-T025）

**测试**
- 后端 `./mvnw -B verify`：**283** 通过（原 273 + 新增 10）
  - 五模块命中、分组结构、命中字段标注、标题优先于内容、消息命中返回会话且不重复、
    空/超长关键词 400、limit 收敛、无命中空 groups、**两条通配符转义路径**
- 前端 `npm run test:logic`：**248** 断言通过（+9）；`type-check`、`build` 通过

**真机验证（curl + Playwright）**
| 项目 | 结果 |
|---|---|
| 搜「数学」 | 对话（标题）+ 记忆（内容）两个分组，共 2 条 |
| 顶栏搜索框 | 回车跳 `/search?q=数学`，摘要「搜索『数学』· 共 2 条」 |
| 字段标注 | 对话条目显示「标题」，记忆条目显示「内容」 |
| 点击命中 | 跳到 `/chat` |
| 直接打开 `/search?q=…` | 同样渲染出 2 个分组（query 驱动，可分享） |
| 页面错误 | (无) |

### 真机抓出来的缺陷（本阶段最重要的收获）

集成测试只覆盖了「任务标题」的通配符转义，**漏了消息内容那条路径**。
真机搜 `%` 返回 **5 条命中**（全部来自「消息内容」）——
`findConversationIdsByContent` 的 JPQL 没有 `ESCAPE`，用户搜「100%」会命中「100 之后任意字符」。

修复 + 补测试（消息内容路径的转义），并把该查询从 JPQL 改为原生 SQL
（JPQL 的 `escape` 子句在各实现上支持不一致）。修复后真机搜 `%` → **0 命中**。

> **教训**：同一个「转义」要求分散在 5 条 SQL 路径上时，只测其中一条
> 会给人「已经覆盖」的错觉。这类缺陷只有真机能抓到 —— 集成测试的数据太干净。
