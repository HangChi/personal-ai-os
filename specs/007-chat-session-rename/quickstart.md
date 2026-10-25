# Quickstart / 验收清单: 阶段 7 — 会话重命名

**Date**: 2026-10-25 | **Spec**: [spec.md](./spec.md) | **Contract**: [contracts/session-rename-api.md](./contracts/session-rename-api.md)

> 状态标记：✅ 已实际执行并验证 ｜ ⏳ 未执行（须写明原因与验证动作） ｜ ❌ 失败
> 宪法原则 V：MUST NOT 用「看起来对」代替「实际跑通过」。

## 前置

```bash
cd /Volumes/WD770/Project/personal-ai-os
docker compose up -d                     # postgres:55432
cd backend && ./mvnw -B verify            # 质量门禁：191 测试全绿
cd ../frontend && npm run type-check && npm run build && npm run test:logic
```

## 自动化验收

| 项 | 动作 | 关联 | 结果 |
|---|---|---|---|
| A-改名成功 | `ChatApiIntegrationTest.renameSessionUpdatesTitle`：PATCH → 200，`title` 为新值，响应含 `messageCount`/`updatedAt` | C-1 / SC-001 | ✅ |
| A-幂等 | `renameSessionIsIdempotent`：连续两次相同标题均 200 且结果一致 | C-2 / FR-005 | ✅ |
| A-去空白 | `renameSessionTrimsTitle`：`"   报销流程   "` → 落库 `"报销流程"` | C-3 / FR-002 | ✅ |
| A-空标题四态 | `renameSessionRejectsBlankTitle`：`""` / `"   "` / `{}` / `null` → 400/40000，库中标题仍为「新会话」 | C-4 / SC-003 | ✅ |
| A-超长 | `renameSessionEnforcesLengthLimit`：201 字 → 400 | C-5 | ✅ |
| A-边界 200 | 同上：恰好 200 字 → 200，返回标题长度 200 | C-6 | ✅ |
| A-归一后长度 | `renameSessionMeasuresLengthAfterTrim`：原始 230 字（190 + 首尾各 20 空格）→ **200**，落库长度 190 | C-7 / R-02 | ✅ |
| A-不存在 | `renameUnknownSessionReturns404`：404 / 40004 | C-8 | ✅ |
| A-非法 id | `renameInvalidIdReturns400`：400 / 40000；另有单测 `renameShouldRejectInvalidIdBeforeQuery` 断言 `findById` 从未被调用 | C-9 / FR-007 | ✅ |
| A-顺序不变 | `renameSessionKeepsListOrder`：三个固定 `updatedAt` 的会话，改最旧者后列表仍为 新→中→旧，且 `updated_at` 与改名前**逐值相等** | C-11 / FR-004 / SC-002 | ✅ |
| A-消息不受影响 | `renameSessionDoesNotTouchMessages`：有 2 条消息的会话改名后条数不变、内容仍可查 | C-10 | ✅ |
| A-创建同口径 | 单测 `createShouldMeasureLengthAfterTrim`：`create` 传原始 230 / 归一 190 → 接受（收敛前会被 `@Size` 拒绝） | R-08 | ✅ |
| A-判定顺序 | 单测 `renameShouldRejectBlankTitleBeforeQuery` / `renameShouldRejectTooLongTitle`：空与超长时 `findById` **从未被调用** | C-4 / C-9 | ✅ |
| F-store 本地替换 | `chat-store-rename.test.mjs`：成功后目标项被替换，列表请求次数**不增加** | R-05 | ✅ |
| F-store 失败不动 | 同上：API reject → `error` 被设置，`sessions` 内容与顺序不变 | 前端契约 | ✅ |
| F-store 顺序不变 | 同上：`c-1,c-2,c-3` 顺序断言 | SC-002 | ✅ |
| F-store 幂等 | 同上：同标题提交两次，结果一致且列表不重复插入 | FR-005 | ✅ |

**自动化基线**：后端 `./mvnw -B verify` → **191 tests / 0 failures / 0 errors**（阶段 6 基线 174，新增 7 单测 + 10 集成）；
前端 `npm run test:logic` → **60 通过 / 0 失败**（原 47 + 新增 13 断言）；`type-check` 退出码 0；`build` 成功。

## 真机验收

| 项 | 动作 | 结果 |
|---|---|---|
| M-REST 冒烟 | 创建会话 → `PATCH {"title":"  报销流程问答  "}` → 200，`title` 为「报销流程问答」，`updatedAt` 与 `createdAt` **完全相同**（证明未被刷新）；GET 列表显示新标题 | ✅ |
| M-REST 错误路径 | 空标题 → `400/40000 标题不能为空`；非法 id `bad_id_x` → `400/40000 会话标识格式非法`；不存在 → `404/40004 会话不存在` | ✅ |
| M-浏览器点击路径 | 打开 `/chat` → 悬停会话项 → 点「重命名」→ 对话框**预填**当前标题 → 改值 → 保存 → 列表即时显示新标题、控制台 0 错误 | ✅ |
| M-浏览器刷新持久 | 改名后 `reload` 页面 → 仍是新标题 | ✅ |
| M-浏览器取消路径 | 打开对话框改值后点「取消」→ 标题不变 | ✅ |
| M-浏览器空值拦截 | 清空输入 → 保存 → 对话框内提示「标题不能为空」，不发请求 | ✅ |

> 真机环境：后端 `java -jar`（`--server.port=8080`）+ 前端 `vite --host 127.0.0.1:5173` +
> `docker compose` 的 postgres:55432；浏览器用 Playwright + headless Chromium。
> 同一轮验证（`/tmp/verify-rename.cjs`）覆盖点击/刷新/取消/空值四条路径，并截取空闲与悬停两张图。

## 实现偏差（同轮已回改上游文档）

| 偏差 | 处理 |
|---|---|
| 最初把「重命名 / 删除」做成常驻按钮，真机截图显示标题被压成 6 个字 | 改为**悬停浮现 + 绝对定位**，把宽度还给标题；触摸设备（`@media (hover: none)`）回退为常显，理由与代价写进 CSS 注释 |
| tasks 原计划 Phase 1/2/3 三次提交 | 本轮实现与验证连续完成，合并为一次实现提交 + 一次文档回填提交，避免制造「伪提交」（skill 第 7 条） |

## 已知行为（不是缺陷）

- 空会话改名成字面量「新会话」，随后发首条消息 → 标题被自动标题覆盖。
  依据：spec Assumptions + research R-04（不为极窄路径加状态列）。验收时按"预期行为"对待。
