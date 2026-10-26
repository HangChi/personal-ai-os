# Contracts: 019-knowledge-chat-settings

按关注点拆两份：对话流（消费 / 产生 / 持久化）与设置（存储 / 行为）。

## C-01: 流式对话扩展（chat stream）

**请求**：`POST /api/v1/chat/sessions/{conversationId}/stream`

```json
{ "content": "……", "useKnowledge": true, "topK": 6 }
```

- `useKnowledge` 省略 = false；`topK` 省略 = 4；`topK` <1 或 >20 收敛到 [1,20]（服务端权威）
- `useKnowledge=false` 时请求处理路径与阶段 1 完全一致（回归保护）

**事件序列**（新事件加粗，顺序即契约）：

```
start → citations? → delta* → done
                ↘ error（仅检索失败时的降级提示，之后流继续）
```

| 事件 | 载荷 | 语义 |
|---|---|---|
| `start` | `{conversationId}` | 不变 |
| **`citations`** | `{count: N, citations: [{index, documentId, documentTitle, filename, chunkIndex, content}]}` | 仅 `useKnowledge=true` 且检索成功时发一次；N=0 也发（前端据此显示「未找到依据」并关闭加载态） |
| `delta` | `{content}` | 不变 |
| `done` | `{conversationId, content}` | 不变 |
| `error` | `{code, message}` | 检索失败时发一次降级提示（「知识库检索失败，本轮未使用知识库」），**流不中断**；模型错误时代码不变 |

**行为用例**：

| # | 场景 | 期望 |
|---|---|---|
| 1 | useKnowledge=false | 无 citations 事件、无检索、无引用落库 |
| 2 | useKnowledge=true，库有相关内容 | start 后即 citations（N>0），回答含 `[n]`，done 后落库 N 条 |
| 3 | useKnowledge=true，库无相关内容 | citations N=0，正常回答，无引用区 |
| 4 | useKnowledge=true，检索抛错 | error 事件（降级文案）+ 流继续 + 无引用落库 |
| 5 | topK=99 | 收敛为 20 |
| 6 | 刷新后 GET messages | 助手消息 citations 非空，内容与当时一致 |

**system 提示注入格式**（追加在基础人设与贡献者之后，请求级 `system()` 替换语义不变）：

```
以下内容来自用户的个人知识库检索结果（本轮实时检索）：
[1] 文档《{title}》（{filename}，第 {chunkIndex+1} 段）
{content}

[2] ……

使用约束：
- 回答必须基于上述内容，引用处标注对应编号，如 [1]
- 上述内容没有答案时，明确说明「知识库中没有找到相关内容」，不得编造
- 不要复述整段原文，提炼回答
```

**落库时序（实测依据，Spring AI 2.0.1）**：`MessageChatMemoryAdvisor.adviseStream` 用
`ChatClientMessageAggregator` 在流终止时调 `after()` → `JpaChatMemory.add()`（@Transactional 提交），
该链路位于我们订阅的**上游**；因此终帧回调（发 done 处）里「查该会话最新助手消息并写引用」是确定的。
若 Advisor 实现变化导致查不到消息：记 WARN 跳过，不抛错（集成测试断言正常路径）。

## C-02: 消息查询附带引用

`GET /api/v1/chat/sessions/{id}/messages` 返回的助手消息：

```json
{ "id": 12, "role": "ASSISTANT", "content": "……[1]……", "sequenceNo": 5, "createdAt": "…",
  "citations": [ { "index": 1, "documentId": "…", "documentTitle": "…", "filename": "…", "chunkIndex": 3, "content": "……" } ] }
```

- 用户消息 `citations` 恒为空数组（字段不省略，前端少一层判空）
- 查询一次会话消息 = 1 条消息 SQL + 1 条引用 SQL（按 message_id in），不 N+1

## C-03: 设置存储（前端）

- 键空间：`os.settings.*`（见 data-model 表）；读取走 `mergeSettings(stored)`——脏值 / 越界回落默认值
- `os.chat.knowledge.<conversationId>`：会话级增强开关
- 重置：清除全部 `os.` 前缀键（含 theme / nav / tasksView / knowledgeTab / chat 会话开关），`location.reload()`
- 主题切换仍写 `os.theme`（与 uiStore 同一键），设置页与顶栏切换效果一致

**行为用例**：

| # | 场景 | 期望 |
|---|---|---|
| 1 | 设置页改 enterSend=false | ChatView Enter 换行、Ctrl/Cmd+Enter 发送；刷新保持 |
| 2 | 改 knowledgeTopK=8 | 之后新开增强会话默认 8；本会话已开的输入框保持用户当次选择 |
| 3 | 改 reminderLead=30 | 日程页新建表单默认 30 |
| 4 | 重置偏好（二次确认） | os.* 清空、刷新、主题回 system |
| 5 | localStorage 被手改坏（"abc"） | mergeSettings 回落默认，不白屏 |

## C-04: 增强状态可见性（前端）

- 开关常驻输入区上方：「知识库增强 · 开/关」el-switch + 说明文案；开时输入框 placeholder 追加提示
- 流式中：消息区顶部显示「正在检索知识库…」；`citations` 事件到达后变为「已找到 N 条依据」（N=0 时「未找到相关依据」）
- 引用区：助手消息内容下方，`引用来源（N）` 折叠标题；展开显示编号 + 标题 + 文件名 + 片段号 + 内容；>3 条默认折叠
- 模型未按编号引用时引用区照常展示（标题注明「本轮检索到的依据」）
