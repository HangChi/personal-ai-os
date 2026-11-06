# 实施方案：按问题作答与来源分歧

**Branch**: `go` | **Date**: 2026-11-06 | **Spec**: [spec.md](spec.md)

## Summary

保留冲突状态与无工具保护路径。全部相关证据进入有界逐项生成，结构校验后独立核对，核对成功才输出可确认内容和标为建议的测试步骤；失败时输出双方原文及实际澄清。Java/Go 使用同一组固定契约数据和相同预算。

## Technical Context

Java 21 / Spring AI、Go（沿用 go.mod）、Vue 3 / TypeScript / Element Plus。复用现有网关、检索管线和来源抽屉，无新增依赖、配置或数据库变更。

## Constitution Check

设计前后均通过：模块化单体；无新增副作用、任意工具或权限；不接真实配置与资料；SQL/冻结清单/发布门禁保持起点快照；阶段分别提交。实现前基线 Java verify 与前端 type-check/build 实际通过。

## Project Structure

- Java knowledge/rag：RagResult、RagRetrievalPipeline、EvidenceReview、EvidenceAnswer。
- Go knowledge：rag.go、evidence_answer.go、evidence_scope.go。
- 双端知识工具按本轮注册编号重映射保护回答。
- Vue RagDisagreements.vue、ChatSources.vue、RagTrace.vue。
- 双端单元/集成测试、contracts/answer-cases.json、隔离浏览器脚本。

## Implementation

1. 冲突时保留所有相关命中，topK 不丢弃可答项或冲突对方。现有公开状态不变。
2. 治理复用恢复与人工处理；对本轮精审未报告但治理恢复的历史冲突，重新核对与当前问题的关系。仅完整有效且明确为无关时从本轮移除，不改原记录；失败为 UNVERIFIED。
3. EvidenceAnswer 是纯读取处理器，复用现有网关与 JSON。生成 SUPPORTED/SUGGESTION/UNRESOLVED 回答项，严格校验类型、长度、原文及编号；编号由服务端附加。
4. 独立核对必须覆盖全部回答项，确认问题覆盖完整、冲突没有被选边。非法结构、伪造原文、遗漏、否定核对、异常或超时均不放行生成内容。最多8秒：生成5秒、核对3秒，并受检索剩余预算约束。
5. 成功结果在 RagResult 内部缓存且不序列化；问答/对话/工具复用。保留 guarded 与 answered=false 的保守语义，不进入普通工具执行或自动记忆提取路径。
6. 确定性渲染双方原文、理由、实际澄清，移除无关制度模板。工具全局编号重映射，未知状态不冒充真实冲突。
7. 抽屉顶部直接展示分歧，入口显示数量；过程单独折叠，不重复。复用主题令牌、焦点和现有控件。

## Validation

先补双端固定契约与失败用例，再实现。前端 lint/format/type/build/逻辑/组件；Java verify 原 JaCoCo 门禁；Go 单测/race/相关隔离 PostgreSQL；桌面/窄屏、深浅主题浏览器截图和交互。提交后推进现有候选所有权与责任锚点，不升级迁移状态。

## Complexity Tracking

新增知识模块处理器避免三个入口分别生成/核对造成差异。内部缓存保持公开接口和持久化结构。模型核对仍可能出错，保留原文与人工核对，不承诺绝对事实正确。
