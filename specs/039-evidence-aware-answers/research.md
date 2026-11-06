# 决策记录

- Decision：在原保护路径逐项生成并核对，保留 guarded。Rationale：防止普通生成/工具绕开冲突保护。Alternatives：取消 guarded 改变授权和记忆；只改模板无法回答无争议项。
- Decision：只移除明确核对为与本轮无关的历史冲突。Rationale：切片相关不等于其中每个事实相关。Alternatives：删除治理记录破坏追溯；默认忽略遗漏失去已有保护。
- Decision：RagResult 内部缓存核对答案，不序列化。Rationale：三个入口一致且无 SQL/API 变更。Alternatives：分别请求模型产生不一致和额外预算。
- Decision：Java/Go 同时读取固定 JSON 契约。Rationale：验证编号、原文、Unicode 边界和降级文本实际一致。
- Decision：分歧组件复用来源抽屉与主题，无新增运行时依赖或新设计系统。
