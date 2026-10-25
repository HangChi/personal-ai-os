# Quickstart / 验收清单: 阶段 7 — 会话重命名

**Date**: 2026-10-25 | **Spec**: [spec.md](./spec.md) | **Contract**: [contracts/session-rename-api.md](./contracts/session-rename-api.md)

> 状态标记：✅ 已实际执行并验证 ｜ ⏳ 未执行（须写明原因与验证动作） ｜ ❌ 失败
> 宪法原则 V：MUST NOT 用「看起来对」代替「实际跑通过」。

## 前置

```bash
cd /Volumes/WD770/Project/personal-ai-os
docker compose up -d                     # postgres:55432
cd backend && ./mvnw -B verify            # 质量门禁（含集成测试）
cd ../frontend && npm run type-check && npm run build && npm run test:logic
```

## 自动化验收

| 项 | 动作 | 关联 | 结果 |
|---|---|---|---|
| A-改名成功 | `ChatSessionRenameIT`：PATCH 新标题 → 200，库中 `title` 为新值 | C-1 / SC-001 | ⏳ |
| A-幂等 | 连续两次相同标题 → 两次 200 且结果相同 | C-2 / FR-005 | ⏳ |
| A-去空白 | `"  报销流程  "` → 落库为 `"报销流程"` | C-3 / FR-002 | ⏳ |
| A-空标题四态 | `""` / `"   "` / `{}` / `null` → 40000，库中 `title` 不变 | C-4 / SC-003 | ⏳ |
| A-超长 | 201 字符 → 40000，库中不变 | C-5 | ⏳ |
| A-边界 200 | 恰好 200 字符 → 200 | C-6 | ⏳ |
| A-归一后长度 | 190 字符 + 首尾各 20 空格（原始 230）→ **200** | C-7 / R-02 | ⏳ |
| A-不存在 | 合法格式但无此会话 → 404 / 40004 | C-8 | ⏳ |
| A-非法 id | `../../etc` → 40000，且**零查库**（以 `conversationRepository` 未被调用或库状态不变为证） | C-9 / FR-007 | ⏳ |
| A-顺序不变 | 三会话 A/B/C，改 C → 列表仍 A/B/C；C 的 `updatedAt` 不变 | C-11 / FR-004 / SC-002 | ⏳ |
| A-消息不受影响 | 有 4 条消息的会话改名 → `messageCount == 4`，消息表行数不变 | C-10 | ⏳ |
| A-创建同口径 | `create` 传 190 字符 + 首尾各 20 空格（原始 230）→ 现在**接受**（收敛前会被 `@Size` 拒） | R-08 | ⏳ |
| F-store 本地替换 | `renameSession` 成功后 `sessions` 该项被替换，`loadSessions` **未被调用**（spy） | R-05 | ⏳ |
| F-store 失败不回滚 | API reject → `error` 被设置，`sessions` 引用与内容不变 | 前端契约 | ⏳ |
| F-store 顺序不变 | 替换后数组顺序不变 | SC-002 | ⏳ |

## 真机验收

| 项 | 动作 | 结果 |
|---|---|---|
| M-REST 冒烟 | `curl -X PATCH .../sessions/{id} -d '{"title":"冒烟改名"}'` → 200；再 GET 列表核对 | ⏳ |
| M-浏览器点击路径 | 打开 `/chat` → 会话项点「重命名」→ 对话框预填当前标题 → 改值 → 确定 → 列表即时显示新标题、**刷新后仍是新标题**、会话位置未跳到最前 | ⏳ |
| M-浏览器取消路径 | 点「重命名」→ 取消 → 标题不变、无网络请求 | ⏳ |
| M-浏览器空值拦截 | 清空输入 → 确定 → 提示「标题不能为空」，不发请求 | ⏳ |

> M-浏览器三项需要启动后端（`--server.port=8080` 显式指定，避免 `SERVER_PORT` 环境变量覆盖）
> + 前端 dev server（`--host 127.0.0.1`，见项目记忆）。若本轮不具备条件，保持 ⏳ 并写明。

## 已知行为（不是缺陷）

- 空会话改名成字面量「新会话」，随后发首条消息 → 标题被自动标题覆盖。
  依据：spec Assumptions + research R-04（不为极窄路径加状态列）。验收时按"预期行为"对待。
