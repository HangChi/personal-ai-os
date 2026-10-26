# Implementation Plan: 阶段 19 — 对话知识库增强 + 设置页

**Branch**: `019-knowledge-chat-settings` | **Date**: 2026-10-26 | **Spec**: [spec.md](./spec.md)

## Summary

两件事：

1. **对话知识库增强**：流式请求扩展 `useKnowledge` / `topK`；开启时服务端在调模型前用既有混合检索
   （`KnowledgeRetrievalService`）取 topK 条片段，以 `[1] [2]…` 编号注入**请求级 system 提示**，
   检索成功后立即推 `citations` SSE 事件；回答落库时把引用快照写入新表 `chat_message_citation`，
   `GET messages` 按消息带回，刷新不丢。
2. **设置页** `/settings`：主题三态、侧栏折叠、知识库默认开关、默认检索条数、Enter 发送开关、
   默认提前提醒分钟数、导出数据、重置偏好、关于。全部 localStorage（`os.settings.*`），零后端配置接口。

**零新增依赖**（前后端都是）。新增 1 张迁移表（V11）。

## Technical Context

- 后端 Java 21 / Spring Boot 4.1.1 / Spring AI 2.0.1（`MessageChatMemoryAdvisor` 落库路径已实测确认）
- 前端 Vue 3.5 + TS + Pinia + Element Plus（`stores/settings.ts` + `views/SettingsView.vue`）
- 测试：后端 Testcontainers 集成测试（既有 chat 流式测试 harness 可复用）；前端 `.mjs` 纯函数单测 + 真机 Playwright

## Constitution Check

| 原则 | 判定 | 依据 |
|---|---|---|
| I. 简单优先 | ✅ | 复用既有检索服务，零新增依赖；引用挂载只加一个终帧回调 + 一张表 |
| II. 边界即安全 | ✅ | 检索内容只进 system 提示，不扩大工具边界；引用快照不含密钥；无密钥入仓 |
| III. 分层与写路径 | ✅ | chat → knowledge 走**窄接口 SPI**（`KnowledgeSearchPort`，见 R-02），不直拉 Service；写路径仍在 Service（citation 落库在 chat 服务层） |
| IV. 数据变更可追溯 | ✅ | V11 Flyway 迁移，外键级联；已应用迁移不改写 |
| V. 可验证的交付 | ✅ | 集成测试断言 citations 事件 / 落库 / 级联删除 / 降级；前端单测 + 真机截图 |
| 工作流：阶段串行 | ✅ | 019 接 018；每 Phase 一次提交 |

## Complexity Tracking

| 项 | 为什么必须 | 更简单的方案为何被否决 |
|---|---|---|
| 「最新助手消息」挂载引用 |  Advisor 的 `after()`（落库）经实测确认**先于**我们的终帧回调运行（aggregateChatClientResponse → after → 下游终帧），故终帧里查最新助手消息是确定的 | 在流启动前按预测 sequence 预写：advisor 与实际写入之间有任何并发（双标签同会话）就会错位；按内容哈希匹配：助手内容可能相同（重复提问），误挂 |
| 同会话并发流假设 | 前端已禁（streaming 中禁发送、切会话被拦）；后端不额外加锁 | 加分布式锁 / 队列：本地单用户应用属过度设计 |
| `KnowledgeSearchPort` 一层接口 | 与阶段 12/17 的 SPI 模式一致（SearchContributor / ExportContributor），避免 chat 直拉 knowledge Service | 直接注入 `KnowledgeRetrievalService`：单点可用但开破坏模块边界先例，后患大 |

## Project Structure

**后端**

```
resources/db/migration/V11__create_chat_message_citation.sql    新
chat/entity/ChatMessageCitationEntity.java                      新
chat/repository/ChatMessageCitationRepository.java              新
chat/dto/ChatCitationDto.java                                   新
chat/dto/StreamChatRequest.java                                 改：+ useKnowledge / topK
chat/dto/ChatMessageDto.java                                    改：+ citations
chat/dto/ChatStreamEvents.java                                  改：+ Citations 事件
chat/service/ChatCompletionService.java                         改：检索 + 注入 + citations 事件 + 终帧落库
chat/service/ChatMessageService.java                            改：list 附带 citations
chat/controller/ChatSessionController.java                      改：传参
knowledge/spi/KnowledgeSearchPort.java                          新（窄接口）
knowledge/service/KnowledgeRetrievalService.java                改：implements KnowledgeSearchPort
```

**前端**

```
src/lib/settings.ts                    新：clampTopK / mergeSettings / 会话增强键（纯函数）
src/stores/settings.ts                 新：os.settings.* 偏好 + watch 持久化
src/stores/chat.ts                     改：send(options) + liveCitations + loadToolTrail 不变
src/api/chat.ts                        改：StreamHandlers + onCitations；dispatchEvent + citations 分支
src/types/api.ts                       改：ChatMessage.citations / ChatCitation
src/views/ChatView.vue                 改：增强开关 / topK / 检索状态 / 引用区 / Enter 行为
src/views/CalendarView.vue             改：leadMinutes 默认取设置
src/views/SettingsView.vue             新
src/router/index.ts                    改：/settings 路由
src/App.vue                            改：导航第 8 项「设置」
frontend/test/settings.test.mjs        新
```

## Phase 拆分与提交边界

| Phase | 内容 | 性质 | 提交 |
|---|---|---|---|
| A | 后端：迁移 + 实体 + SPI + 请求扩展 + 检索注入 + citations 事件 + 终帧落库 + 查询附带 + 测试 | 后端为主 | `feat(chat):` |
| B | 前端：settings store/lib + ChatView 增强开关 + 引用区 + 检索状态 + Enter 行为 + Calendar 默认值 | 前端为主 | `feat(ui):` |
| C | 前端：SettingsView + 路由 + 导航项 | 纯增量 | `feat(ui):` |
| D | 真机验收 + 文档回填 | docs + 验收 | `docs(chat):` |

## 风险与降级

| 风险 | 信号 | 降级 |
|---|---|---|
| Advisor 落库顺序与实测不符（版本升级改变） | 集成测试断言 citation 落库失败 | 终帧查不到助手消息时记 WARN 并跳过（不抛）；测试先挂会暴露 |
| 检索慢拖慢首字节 | 真机计时 | citations 事件在模型调用前发，用户先看到「已找到 N 条」再等生成；topK 默认 4 已控制 |
| 引用内容极大撑爆消息流 | 真机极端数据 | 引用区默认折叠；内容区 max-height + 滚动 |
| 模型不按编号引用 | 真机抽查 | 提示词强约束 + 引用区独立展示（不依赖模型是否引用） |
