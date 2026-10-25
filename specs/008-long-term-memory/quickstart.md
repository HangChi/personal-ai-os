# Quickstart / 验收清单: 阶段 8 — 长期记忆

**Date**: 2026-10-25 | **Spec**: [spec.md](./spec.md) | **Contract**: [contracts/memory-api.md](./contracts/memory-api.md)

> ✅ 已实际执行并验证 ｜ ⏳ 未执行（须写原因） ｜ ❌ 失败
> 宪法原则 V：MUST NOT 用「看起来对」代替「实际跑通过」。

## 前置

```bash
cd /Volumes/WD770/Project/personal-ai-os
docker compose up -d                        # postgres:55432，Flyway 执行 V9
cd backend && ./mvnw -B verify               # 质量门禁
cd ../frontend && npm run type-check && npm run build && npm run test:logic
```

**基线**：后端 `./mvnw -B verify` → **203 tests / 0 failures**（阶段 7 基线 181，新增 9 单测 + 13 集成）；
前端 `npm run test:logic` → **81 通过 / 0 失败**（原 60 + 新增 21）；`type-check` 退出码 0；`build` 成功。

## 自动化验收

| 项 | 动作 | 关联 | 结果 |
|---|---|---|---|
| A-空库列表 | `MemoryIntegrationTest.listIsEmptyArrayWhenNoMemory`：`data: []` | M-1 | ✅ |
| A-新增去空白 | `createTrimsContent`：`"  周报…  "` → 落库无空白 | M-3 | ✅ |
| A-去重幂等 | `duplicateContentIsIdempotent`：同内容 3 次 → 表 1 行、id 相同 | M-4 / SC-003 | ✅ |
| A-空内容四态 | `blankContentIsRejected`：`""` / `"   "` / `{}` / `null` → 40000，表零写入 | M-10 | ✅ |
| A-超长 | `contentLengthLimit`：2001 → 400；2000 → 200 | M-11 | ✅ |
| A-编辑 | `updateKeepsCreatedAt`：内容更新、`createdAt` 不变、`updatedAt` 刷新 | M-6 | ✅ |
| A-删除 | `deleteThenMissing`：200 后表空；再删 → 404 | M-7 / M-12 | ✅ |
| A-非法 id | `invalidAndMissingId`：400；另有单测断言 `findById` 从未被调用 | M-13 | ✅ |
| A-注入存在 | `memoryIsInjectedIntoSystemPrompt`：SystemMessage 含**基础人设**与记忆块原文 | M-5 / SC-001 | ✅ |
| A-注入为空 | `noMemoryKeepsSystemPromptUnchanged`：SystemMessage **逐字符等于** `AiProperties.systemPrompt()` | M-2 / SC-004 | ✅ |
| A-注入预算-条数 | `injectionCapsAtThirty`：35 条 → 含 1..30，不含第 31 条 | M-8 / SC-002 | ✅ |
| A-预算-字符不截断 | 单测 `renderShouldStopInsteadOfTruncating`：4×2000 字 → 只注入 3 条，第 4 条**整条不出现** | M-9 / R-05 | ✅ |
| A-工具保存 | `toolSavesMemory`：桩模型调用 `saveMemory` → 表 +1 行，且存在 TOOL 类型消息回灌 | M-14 | ✅ |
| A-删后不注入 | `deletedMemoryIsNotInjected`：删除后 SystemMessage 不含该条、也不含记忆块标题 | SC-006 | ✅ |
| A-知识库不注入 | — | M-15 / FR-007 | ⏳ 见下 |
| F-store 三路径 | `memory-store.test.mjs`：新增**重拉**、编辑**就地替换**、删除**本地移除**、失败不动列表 | 前端契约 | ✅ |
| F-空状态 | 浏览器实拍：`el-empty` 可见，条目数 0 | SC-005 | ✅ |

## 真机验收

| 项 | 动作 | 结果 |
|---|---|---|
| M-REST 冒烟 | `curl` 九步：空列表 → 新增（去空白）→ 重复新增（id 相同）→ 列表 1 条 → 编辑 → 空内容 400 → 非法 id 400 → 删除 200 → 再删 404 | ✅ |
| M-迁移 | `flyway_schema_history` 最新一行 `9 / create memory / success=t` | ✅ |
| M-浏览器页面 | `/memory` 五条路径：空状态 → 新增 1 条 → 编辑（**对话框预填原文**）→ 删除（二次确认）→ 回到空状态；控制台 0 错误 | ✅ |
| M-导航与标识 | 顶栏出现「记忆」，阶段标识为「阶段 8 · 长期记忆」 | ✅ |
| M-真实对话保存记忆 | 接入 DashScope 后：说「记住：我教初一年级27班的数学，用苏教版教材」→ 模型调用 `saveMemory`，`GET /memories` 出现该条；另在**另一个会话**里创建任务时，模型主动引用了更早存的「周报每周五下午交」 | ✅ 2026-10-25 补验 |

## 未验证项与补验动作

- **M-15（知识库不注入）**：未做运行时断言。当前证据是静态事实 ——
  `MemoryPromptContributor` 的唯一消费者是 `ChatCompletionService`，知识库走
  `AiChatClientProvider.requirePlainChatClient()`（构造期只设 `defaultSystem`，不收集贡献者）。
  **补验动作**：在 `KnowledgeRagIntegrationTest` 里上传一段文档 → 问答 → 断言送给模型的
  system 消息不含「## 长期记忆」。需要复用该类的 `DeterministicEmbeddingModel` 桩。
- ~~**M-真实对话**~~：**已于 2026-10-25 补验**（接入 DashScope）。结论见上表最后一行 ——
  跨会话记忆在真实模型下确实生效（模型在新建会话里主动提到了上一会话存下的偏好）。
  注意这条验证的是"模型是否**遵守**记忆"（模型能力）；系统的正确性仍以
  SystemMessage 级别的自动化断言为准。

## 实现偏差（同轮已回改上游文档）

| 偏差 | 处理 |
|---|---|
| 单测构造 4×2000 字时，前缀让每条变成 2004 字，预算只装得下 2 条，测试失败 | 修正为**按总长度 2000** 构造；并在注释里写明踩坑点（否则下次改断言的人会重踩） |
| 工具回灌断言最初读 `ToolResponseMessage.getText()`，取不到工具结果 | 改为断言存在 `MessageType.TOOL` 消息 —— 消息类型是稳定契约，字段组织是框架内部实现 |
| tasks 原计划四个 Phase 各一次提交 | 实现与验证连续完成，按「后端 / 前端 / 文档回填」三次提交，避免伪提交 |
