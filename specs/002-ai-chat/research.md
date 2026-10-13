# Research: 阶段 1 — AI 对话与聊天历史

> 所有 Spring AI 2.0.1 的 API 结论均通过**解包 Maven Central 上的 jar 并用 `javap` 核实**得出，不是凭记忆书写。

## R-01 AI Starter 选择与「双提供商可切换」

**Decision**：引入 `spring-ai-starter-model-openai` 与 `spring-ai-starter-model-ollama` 两套 Starter（版本由 `spring-ai-bom:2.0.1` 统一管理），用 `spring.ai.model.chat` 属性选择生效的一套。

**Rationale**：
- 实测两个 autoconfigure jar 中都包含字面量 `spring.ai.model.chat`，即该属性正是「哪个聊天模型自动配置生效」的开关。
- OpenAI Starter 用 `spring.ai.openai.base-url` 即可指向任何 OpenAI 兼容服务（DeepSeek / 通义 / Kimi / 智谱 / vLLM），因此「一套 Starter 覆盖多家云端」成立。
- Ollama Starter 对应本地离线场景，无需任何密钥。

**必须同时关闭无关自动配置**：OpenAI Starter（OpenAiChatAutoConfiguration 等 6 个自动配置类）还包含 embedding / image / audio.speech / audio.transcription / moderation，它们各有独立的 `spring.ai.model.*` 开关。若不放行关闭，这些自动配置会尝试创建需要 API Key 的 Bean，破坏「无密钥可启动」。因此显式设置：

```yaml
spring.ai.model:
  chat: ${AI_CHAT_PROVIDER:ollama}
  embedding: none
  image: none
  moderation: none
  audio:
    speech: none
    transcription: none
```

**Alternatives considered**：
- 只引一个 Starter → 无法满足「配置切换提供商」。
- 只引 `spring-ai-client-chat` 手工 new 模型 → 丧失自动配置与属性绑定，且等于自己重写一遍 Starter。

## R-02 聊天历史：自行实现 `ChatMemory`（而非 `MessageWindowChatMemory`）

**Decision**：实现 `org.springframework.ai.chat.memory.ChatMemory` 接口（适配 JPA），**不使用**框架自带的 `MessageWindowChatMemory`。

**核实的接口**（`javap` 实测）：

```java
public interface ChatMemory {
  String CONVERSATION_ID;                       // 常量名，用于 advisor 传参
  default void add(String conversationId, Message message);
  void add(String conversationId, List<Message> messages);
  List<Message> get(String conversationId);
  void clear(String conversationId);
}

public interface ChatMemoryRepository {
  List<String> findConversationIds();
  List<Message> findByConversationId(String conversationId);
  void saveAll(String conversationId, List<Message> messages);   // 注意：没有 save()
  void deleteByConversationId(String conversationId);
}
```

**为什么不实现 `ChatMemoryRepository` + 套 `MessageWindowChatMemory`**：这是本阶段最容易踩的隐性数据丢失。`MessageWindowChatMemory` 的语义是「维护一个窗口」，它在 `add` 之后会把**裁剪后的窗口列表**整体 `saveAll` 回仓库。若仓库是持久化历史，则每轮对话都会用窗口覆盖历史——**窗口滑出的旧消息被静默删除**。这与 FR-007（历史不得被裁剪）直接冲突，且症状是「用久了早期对话莫名消失」，极难排查。

**Decision 的收益**：把两件事解耦——
- 持久化历史：只追加，永不裁剪；
- 送给模型的上下文：取最近 N 条，N 可配置。

## R-03 上下文装载：`MessageChatMemoryAdvisor`

**Decision**：用 `MessageChatMemoryAdvisor.builder(chatMemory).build()` 作为 `ChatClient` 的默认 Advisor，会话标识通过每次请求的 advisor 参数传入。

**核实的 API**（`javap` 实测）：

```java
MessageChatMemoryAdvisor.Builder.builder(ChatMemory) → Builder
Builder.order(int) / Builder.scheduler(Scheduler) / Builder.build()
ChatClient.ChatClientRequestSpec.advisors(Consumer<AdvisorSpec>)
ChatClientRequestSpec.advisors(Advisor...) / advisors(List<Advisor>)
ChatMemory.CONVERSATION_ID              // 传参用的 key
```

用法：`.advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, conversationId))`。

**Rationale**：由框架负责「取出历史 → 注入 prompt → 保存用户消息 → 保存助手回复」，避免手写顺序出错；`scheduler` 的存在说明框架已考虑流式场景下在反应式线程外完成累积与落库。

**Alternatives considered**：手写 `chatMemory.get()` 拼 prompt、结束后再 `add()`。可控性更强但重复实现框架逻辑，且容易漏掉「助手消息落库」这一步。

### 实测行为（由集成测试观测到，务必记住）

`MessageChatMemoryAdvisor` 的窗口**只约束「历史部分」**，不约束总条数。实测第 5 轮对话（`max-messages=6`）时送给模型的指令为：

```
[ SYSTEM(system prompt),
  USER(第2轮), ASSISTANT, USER(第3轮), ASSISTANT, USER(第4轮), ASSISTANT,   ← 6 条历史
  USER(第5轮) ]                                                            ← 本轮消息
```

即：**1 条 system + ≤ N 条历史 + 1 条本轮用户消息**。顺序上 Advisor 是先 `get()` 取窗口内历史，再把本轮用户消息与 system 提示追加进去。

这条结论直接影响两件事：
1. 写「上下文上限」的断言/文档时，不能按 `总条数 ≤ max-messages` 写，否则测试会误报；
2. 前端或运维估算 token 用量时，要按 `max-messages + 2` 条估算。

另一方面它也印证了窗口机制是生效的：同一个测试里，最早的「第1轮」消息已被滑出上下文，而数据库里 10 条历史一条未少 —— **上下文窗口与持久化历史确实解耦**（这正是 R-02 的设计目的）。

## R-04 流式输出：`Flux<String>` 桥接 `SseEmitter`

**Decision**：`ChatClient.prompt(...).stream().content()` 得到 `Flux<String>`，桥接到 Spring MVC 的 `SseEmitter`，**不引入 WebFlux**。

**核实的 API**（`javap` 实测）：

```java
ChatClient.ChatClientRequestSpec.stream() → ChatClient.StreamResponseSpec
ChatClient.StreamResponseSpec.content()   → Flux<String>
ChatClient.StreamResponseSpec.chatResponse() → Flux<ChatResponse>
```

**Rationale**：
- 项目已是 Spring Web MVC（阶段 0 决策），为流式单独切 WebFlux 等于双栈并存，违反原则 I。
- `SseEmitter` 是 MVC 下推 SSE 的标准做法，`Flux` 可直接 `subscribe` 到 emitter 的三个回调上。
- 仍可复用 Reactor 的 `Flux`（Spring AI 传递依赖 `reactor-core`），无需额外引入。

**Alternatives considered**：
- 改用 WebFlux：需重写全部 Web 层与安全配置，收益仅为一个流式端点 → 否决。
- 用 `ResponseBodyEmitter` / 手写 chunked：SSE 语义需自己拼 `data:` 帧，易错 → 否决。

## R-05 SSE 采用 POST 而非 GET

**Decision**：流式对话用 `POST /api/v1/chat/sessions/{conversationId}/stream`。

**Rationale**：浏览器原生 `EventSource` 只能发 GET，用户消息就只能塞进查询串——会进入访问日志、受 URL 长度限制、且语义上把「写入」放在 GET 里。改用 `fetch` + `ReadableStream` 手工解析 SSE 帧，既保留 POST 语义又避免上述问题。

**代价（已记录）**：前端需要自己实现 SSE 帧解析（约 30 行），且失去 `EventSource` 的自动重连。对话场景下不应自动重连（会重复提问），因此这一「损失」实际上是期望行为。

## R-06 无密钥可启动的策略

**Decision**：默认 `spring.ai.model.chat=ollama`；`ChatClient` 通过 `ObjectProvider<ChatClient.Builder>` 获取，取不到时**不抛异常**，而是把聊天能力标记为「未配置」，接口返回 `AI_NOT_CONFIGURED`。

**Rationale**：
- Ollama 不需要密钥，因此默认配置下应用必然能启动，延续阶段 0 的「开箱可跑」承诺。
- 若用户把提供商配成 `none` 或环境里根本没有可用的模型 Bean，直接注入 `ChatClient.Builder` 会导致**启动失败**——那会让健康检查也不可用。用 `ObjectProvider` 降级能把故障限制在聊天功能内。
- 错误码用 `50300 / SERVICE_UNAVAILABLE`，语义是「依赖未就绪」，前端可据此提示「请先配置模型」。

**Alternatives considered**：强制要求配置模型、否则启动失败（快速失败）。被否决——健康检查与其它端点不应因 AI 未配置而不可用，尤其这是自托管项目的首次上手路径。

## R-07 测试策略：桩 `ChatModel`（无密钥、无网络）

**Decision**：集成测试设 `spring.ai.model.chat=none`，并用 `@TestConfiguration` 提供一个桩 `ChatModel`，返回可预期的文本与多段流。

**核实的接口**（`javap` 实测）：

```java
public interface ChatModel extends Model<Prompt, ChatResponse>, StreamingChatModel {
  ChatResponse call(Prompt prompt);                       // 抽象方法，必须实现
  default Flux<ChatResponse> stream(Prompt prompt);        // 默认实现，可覆写
  default ChatOptions getDefaultOptions();
}
ChatResponse(List<Generation>) / Generation(AssistantMessage) / AssistantMessage(String)
```

**Rationale**：这是让 SC-008「无网络、无密钥下集成测试全绿」可达成的前提。同时桩模型能让测试**断言送给模型的消息列表**（即在桩里记录收到的 `Prompt.getInstructions()`），从而精确验证：
- 多轮上下文确实被装载（FR-009）；
- 跨会话无泄漏（FR-008 / SC-003）；
- 上下文条数受上限约束（SC-004）。

真实模型既做不到确定性，也无法检查「发送了什么」。

**Alternatives considered**：用真实 Ollama 跑集成测试（CI 不可复现）、只做单元测试 mock 掉 ChatClient（无法验证 SSE 与持久化的真实链路）→ 均否决。

## R-08 会话标识与校验

**Decision**：`conversationId` 由服务端生成 UUID 字符串（36 字符），入参校验上限 64 字符且限定 `[A-Za-z0-9-]`。

**Rationale**：与 `chat_session.conversation_id varchar(64)` 对齐；限定字符集可避免不可见字符导致的「看起来相同却查不到」。不信任客户端提供的任意字符串（宪法原则 III：边界校验）。

## R-13 必须收紧 Spring AI 的默认重试策略

**Decision**：显式配置 `spring.ai.retry.max-attempts=2`、`backoff.initial-interval=500ms`、`multiplier=2`、`max-interval=2s`。

**Rationale**：
- 实测 `spring-ai-autoconfigure-retry` 在 classpath 上（由模型 Starter 传递引入），其 `SpringAiRetryProperties` 在前缀 `spring.ai.retry` 下暴露 `maxAttempts` 与 `backoff{initialInterval,multiplier,maxInterval}`（`javap` 核实）。
- Spring AI 的默认重试是「多次尝试 + 指数退避」。在**流式**场景下这尤其糟糕：连接建立后客户端看到的是一个迟迟没有内容的流，而不是一个明确的失败。这与 SC-006（错误应在 10 秒内以 `error` 事件推送）直接冲突。
- 收紧后实测：模型不可达时 **0 秒内**推送 `error`（code 50200）。

**Alternatives considered**：关闭重试（`max-attempts=1`）。被否决 —— 瞬时抖动（如网络瞬断、上游 502）重试一次是划算的，完全禁用会把本可自愈的失败暴露给用户。2 次是「容忍抖动」与「快速失败」的折中。

## R-14 实测：失败轮次的历史语义

流式对话失败时，实测数据库中的落库情况为：

```
1  USER       正常一轮
2  ASSISTANT  这是来自本地模拟模型的流式回复。
3  USER       模型已停止，这次应当失败      ← 失败轮次的用户消息仍在
（无第 4 条：没有写入任何半截助手消息）
```

**结论与理由**：用户消息由 Advisor 在调用**之前**写入，助手消息在**完成之后**写入。因此失败时「用户消息保留、助手消息不写」是框架的固有顺序，也正是期望行为 —— 用户的话不该被丢掉，而未生成的回复不该产生伪记录。下一轮对话时这条未答的用户消息会作为历史出现在上下文中，用户可以直接重试。

**给客户端的要求**：前端在收到 `error` 事件后应**重新拉取消息**，而不是自行伪造一条助手消息，否则本地状态会与库中不一致。
