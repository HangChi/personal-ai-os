# Requirements Checklist: 阶段 1 — AI 对话与聊天历史

**Purpose**: 验证 `spec.md` 中需求的质量（完整性、清晰度、可测试性）。
**Created**: 2026-10-14
**Feature**: [spec.md](../spec.md)

**Marker Semantics**: `[x]` = 该需求质量准则已经评审并满足；不代表实现已完成。

## 需求完整性

- [x] CHK001 每个用户故事都有可独立验证的测试方式（5 个故事均含 Independent Test）
- [x] CHK002 每条功能需求（FR-001 ~ FR-019）均可追溯到验收场景或明确的设计约定
- [x] CHK003 每条成功标准（SC-001 ~ SC-008）均为可度量表述
- [x] CHK004 「明确不含」范围边界已列出（不含工具调用 / RAG / 日程 / MCP / 多模态 / 鉴权 / 编辑与重生成）
- [x] CHK005 边界情况已覆盖：并发写同会话、历史超长、客户端中途断开、空回复、非法标识、重名标题

## 需求清晰度

- [x] CHK006 无 `[NEEDS CLARIFICATION]` 遗留；未定项进入 Assumptions 并说明理由
- [x] CHK007 术语一致（会话 / 消息 / 上下文 / 增量事件 / 会话标识）
- [x] CHK008 验收场景均为 Given/When/Then，无模糊动词
- [x] CHK009 需求不预设实现细节（接口路径、类名、库选择均不出现在 spec.md）

## 一致性与可测试性

- [x] CHK010 FR-007「历史不得被裁剪」与 FR-009「上下文条数受上限约束」不矛盾 —— 两者作用于不同层面，已在 data-model 的读写语义表中钉死
- [x] CHK011 FR-011「无凭据可启动」与 FR-013「未配置时返回明确错误」一致 —— 前者是启动期，后者是请求期
- [x] CHK012 每条需求都能用命令或可观察现象验证（引用 quickstart 场景 A–F）
- [x] CHK013 阶段约束被遵守：未越界实现阶段 2–5 的内容

## 依赖与假设

- [x] CHK014 外部依赖已列明（Ollama 可选、OpenAI 兼容服务可选、Docker 用于测试）
- [x] CHK015 Assumptions 每条都说明了影响（默认提供商、上下文默认 20、POST+fetch 而非 EventSource）
- [x] CHK016 无隐藏前置：本阶段所需能力均在本阶段范围内（表、仓库、适配器全部自建）

## 关键风险已显式记录

- [x] CHK017 **数据丢失风险**已在 spec/data-model/plan 三处记录：使用框架自带 `MessageWindowChatMemory` 会因窗口裁剪覆盖历史，故改为自定义 `ChatMemory`
- [x] CHK018 **双 starter 冲突风险**已记录：必须用 `spring.ai.model.chat` 选择并显式关闭无关自动配置，否则无密钥时启动失败
