# Quickstart / 验收清单: 阶段 8 — 长期记忆

**Date**: 2026-10-25 | **Spec**: [spec.md](./spec.md) | **Contract**: [contracts/memory-api.md](./contracts/memory-api.md)

> ✅ 已实际执行并验证 ｜ ⏳ 未执行（须写原因） ｜ ❌ 失败
> 宪法原则 V：MUST NOT 用「看起来对」代替「实际跑通过」。

## 前置

```bash
cd /Volumes/WD770/Project/personal-ai-os
docker compose up -d                        # postgres:55432，Flyway 会执行 V9
cd backend && ./mvnw -B verify               # 质量门禁
cd ../frontend && npm run type-check && npm run build && npm run test:logic
```

## 自动化验收

| 项 | 动作 | 关联 | 结果 |
|---|---|---|---|
| A-空库列表 | `GET /memories` → `data: []` | M-1 | ⏳ |
| A-新增去空白 | POST `"  周报周五交  "` → 落库「周报周五交」 | M-3 | ⏳ |
| A-去重幂等 | POST 同内容 3 次 → 表 1 行，均 200 | M-4 / SC-003 | ⏳ |
| A-空内容 | `"   "` / `""` / `null` → 40000，表零写入 | M-10 | ⏳ |
| A-超长 | 2001 字符 → 40000；2000 恰好 → 200 | M-11 | ⏳ |
| A-编辑 | PATCH → 内容更新、`createdAt` 不变、`updatedAt` 刷新 | M-6 | ⏳ |
| A-删除 | DELETE → 200；再删 → 404 | M-7 / M-12 | ⏳ |
| A-非法 id | `bad_id_x` → 40000，且零查库 | M-13 | ⏳ |
| A-注入存在 | 有记忆时对话 → SystemMessage 含「## 长期记忆」与该条原文 | M-5 / SC-001 | ⏳ |
| A-注入为空 | 无记忆时 SystemMessage 与阶段 1 **逐字符相同** | M-2 / SC-004 | ⏳ |
| A-注入预算-条数 | 35 条 → 恰好 30 条，且为最近更新的 30 条 | M-8 / SC-002 | ⏳ |
| A-注入预算-字符 | 超 6000 字符 → 累加至上限即停，无半条 | M-9 | ⏳ |
| A-工具保存 | 桩模型触发 `saveMemory` → 表 +1 行，返回「已记住」 | M-14 | ⏳ |
| A-知识库不注入 | 知识库问答 prompt 不含「## 长期记忆」 | M-15 / FR-007 | ⏳ |
| A-删后不注入 | 删除后再对话 → SystemMessage 不含该条 | SC-006 | ⏳ |
| F-store | 新增后重拉、编辑就地替换、删除本地移除、失败设 error | 前端契约 | ⏳ |
| F-空状态 | 空列表显示 `el-empty` | SC-005 | ⏳ |

## 真机验收

| 项 | 动作 | 结果 |
|---|---|---|
| M-REST 冒烟 | `curl` 四个端点（list/create/patch/delete）+ 三条错误路径 | ⏳ |
| M-对话保存记忆 | 真实对话说「记住：…」→ 助手调用工具 → 记忆页出现该条 | ⏳ |
| M-跨会话生效 | 新建会话问相关内容 → 助手回答体现出知道该记忆 | ⏳ |
| M-浏览器页面 | 打开 `/memory`：列表、新增、编辑、删除、空状态五条路径 | ⏳ |

> M-对话两条需要可用模型（本地 Ollama 或真实 API Key）。若不具备，保持 ⏳ 并写明
> —— 注入链路已由桩模型在自动化测试里断言到 SystemMessage 级别，不依赖真实模型。
