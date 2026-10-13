# Research: 阶段 2 — 任务管理与 Agent 工具调用

> Spring AI 2.0.1 的所有 API 结论均通过**解包 Maven Central 的 jar 并用 `javap` 核实**得出。

## R-01 `@Tool` 注解与工具注册方式

**Decision**：用 `@Tool` / `@ToolParam` 注解普通 Java 方法，通过 `ChatClient...tools(Object...)` 注册。

**核实的 API**（`javap` 实测）：

```java
// org.springframework.ai.tool.annotation.Tool   （位于 spring-ai-model.jar，无独立 spring-ai-tool 模块）
String name(); String description(); boolean returnDirect();
Class<? extends ToolCallResultConverter> resultConverter();

// org.springframework.ai.tool.annotation.ToolParam
boolean required(); String description();

// org.springframework.ai.chat.client.ChatClient
ChatClientRequestSpec tools(Object... toolObjects);
ChatClientRequestSpec toolCallbacks(ToolCallback... callbacks);
ChatClientRequestSpec toolContext(Map<String, Object> context);
ChatClient.Builder    defaultTools(Object... toolObjects);
```

**Alternatives considered**：手工实现 `ToolCallback` 接口（可完全控制参数 schema，但要自己写 JSON Schema）→ 否决，`@Tool` 已由框架自动生成 schema，维护成本低得多。

## R-02 流式对话中支持工具调用

**Decision**：工具直接挂在**现有的流式对话**上，不新增独立的非流式 Agent 端点。

**Rationale**：`javap` 显示 `ToolCallingAdvisor implements CallAdvisor, StreamAdvisor, ToolAdvisor` —— 框架明确支持流式路径下的工具调用循环（累积流式片段 → 识别工具调用 → 执行 → 再次调用模型 → 继续流式输出）。

**Alternatives considered**：新增 `/api/v1/agent/chat` 非流式端点 → 与阶段 1 的 SSE 体验割裂，且用户要「用自然语言操作任务」，本来就在对话里。

## R-03 工具内部如何拿到「当前对话轮次」（用于工具层幂等）

**Decision**：用 `ToolContext` 传参，不依赖 `ThreadLocal`。

**核实的 API**（`javap` 实测）：

```java
public final class org.springframework.ai.chat.model.ToolContext {
    public ToolContext(Map<String, Object> context);
    public Map<String, Object> getContext();
}
```

调用侧：`chatClient.prompt()...toolContext(Map.of("turnId", turnId, "conversationId", id))`。

**Rationale**：工具执行发生在框架的工具循环里，线程可能与请求线程不同（流式场景下尤其如此）。`ThreadLocal` 会在反应式调度中静默丢失，导致幂等键算错 —— 那种错误只会在并发下偶发，极难复现。`ToolContext` 是框架显式提供的传递通道。

## R-04 两层幂等的边界划分（关键设计）

**Decision**：
- **工具层**：幂等键 = `hash(conversationId + turnId + toolName + 规范化参数)`
- **HTTP 层**：幂等键 = 客户端提供的 `Idempotency-Key` 请求头

**Rationale**：
- 工具层要防的是「模型在**同一轮**里把同一个操作调两遍」（LLM 的典型行为）。
- HTTP 层要防的是「客户端/网络重试」。
- **为什么不用「同会话 + 同工具 + 同参数 + 时间窗」做工具层幂等**：那会产生误判 —— 用户在同一分钟内真诚地要「建两个标题相同的任务」是完全合法的，若被去重就是 bug。把范围收紧到**单轮**，误判概率降为 0，同时仍然覆盖了真正的问题（模型自重复）。
- 客户端若对**整个对话请求**重发并携带相同的 `Idempotency-Key`，该键会作为轮次标识向下传递，工具层随之命中同一组幂等键 —— 两层自然衔接，无需额外协调。

**Alternatives considered**：
- 只做时间窗去重 → 上述误判风险 → 否决。
- 引入 Redis 做分布式幂等 → 单机单用户场景纯属过度设计（原则 I）→ 否决。

## R-05 幂等存储：与工具审计共用一张表

**Decision**：`operation_record` 一张表同时承担「工具执行日志」与「幂等判定」。

**表的关键约定**：
- `idempotency_key` **唯一**；命中时直接返回缓存的 `response_payload`。
- `request_digest` 记录参数摘要；同键不同摘要 → 返回**冲突**（40901），而不是静默执行成另一件事（FR-016）。这是幂等实现里最容易被忽略、也最危险的一条。
- `outcome` 区分 `SUCCESS` / `REQUIRES_CONFIRMATION` / `REJECTED` / `DUPLICATE` / `FAILED` —— 让「被去重的调用」在审计里与真实执行分开（FR-018）。

**Rationale**：拆两张表需要在同一事务里写两处，且「被去重的调用算不算一次执行」会含糊。合并后语义清晰：**每一行就是一次「请求过的操作」**，`outcome` 说明它实际发生了什么。

**代价（已记录）**：`response_payload` 在 TOOL 与 HTTP 两种来源下都存序列化后的响应体，因此表会随调用量增长。单用户场景下可接受；阶段 5 若需要可加定期归档。

## R-06 两阶段确认的安全性来自「结构」而非「秘密」

**Decision**：模型可以**创建**待确认记录，但**没有任何工具**能确认它；确认只能经面向人类的 REST 接口完成。

**Rationale**：
- 如果只靠「令牌保密」，一旦提示注入让模型把令牌打印出来，防线即失效。
- 本项目采用更强的不变量：**工具集合中不存在确认类工具**（SC-003）。模型在能力层面就无法完成删除，与它是否"被说服"无关。
- 令牌仍然保留，作用是单次性与有效期校验（FR-011），以及绑定「确认」到确切的操作与参数。

**Alternatives considered**：纯对话式确认（「你确认吗？」→ 用户说「确认」→ 模型再调删除）→ 模型完全可以跳过询问直接调删除，等于没有防线 → 否决。

## R-07 确认范围的服务端裁定

**Decision**（按用户选择细化）：

| 操作 | 需确认 | 理由 |
|---|---|---|
| 删除任务（含批量） | **是** | 不可逆 |
| 修改截止时间 / 优先级 | **是** | 用户选定为「关键字段」 |
| 批量操作（影响 >1 行） | **是** | 用户选定 |
| 创建 / 查询 | 否 | 无破坏性 |
| 改标题 / 描述 | 否 | 可逆、影响局部 |
| 标记完成 / 取消完成 | 否 | **可逆且非破坏**，且是最高频的助手动作 |

**关于「标记完成」的说明**：用户选择了「修改关键字段需确认」，但把状态变更也纳入会导致日常最高频的操作（说一句「这个做完了」）每次都要弹确认，与「用自然语言操作任务」的目标直接冲突。标记完成不破坏数据且可随时撤回，因此**不纳入确认范围**。这一裁定写在 spec 的 Assumptions 与本节中，便于后续调整。

## R-08 Jackson 3 与 Jackson 2 并存

**Decision**：统一使用 Jackson 3 的 `tools.jackson.databind.ObjectMapper`（Boot 4 自动配置的那个），并封装进 `common/json/JsonCodec`。

**Rationale**：`mvn dependency:list` 实测 classpath 上**同时存在**：
- `tools.jackson.core:jackson-databind:3.1.5`（Jackson 3，Boot 4 主用）
- `com.fasterxml.jackson.core:jackson-databind:2.21.5`（Jackson 2，被 Spring AI 的 `victools:jsonschema-module-jackson:5.0.0` 等传递引入）
- `com.fasterxml.jackson.core:jackson-annotations:2.21`（**注解仍是 2.x**，Jackson 3 沿用）

两个 `ObjectMapper` 同名不同包，散落注入极易混淆。封成一个 `JsonCodec` 后，序列化策略只有一处可改。

## R-09 让 Agent 的操作效果「可被断言」的测试策略

**Decision**：集成测试用**脚本化桩 ChatModel**驱动工具调用：首轮返回带 `ToolCall` 的回复，次轮返回最终文本；断言口径一律是**数据库受影响行数**。

**核实的 API**（`javap` 实测）：

```java
AssistantMessage.Builder.content(String).toolCalls(List<ToolCall>).build();
AssistantMessage.ToolCall(String id, String type, String name, String arguments);
ToolResponseMessage.ToolResponse(String id, String name, String responseData);
```

**Rationale**：SC-001/002/004/006 都以「行数」为口径，因为**接口返回值可以撒谎，行数不会**。若只断言「接口返回了成功」，一个「工具压根没执行但返回了成功」的实现也能全绿。

## R-10 ⚠️ 最凶险的一个坑：工具静默不执行（零报错、零日志）

这是本阶段排查成本最高的问题，**症状与原因完全不成比例**，必须完整记录下来。

### 症状

桩模型返回了带 `ToolCall` 的回复，对话正常结束（`done` 事件正常推送），
**日志零警告零异常**，但数据库一行都没变、工具的执行日志一条都没有。
非流式 `call()` 与流式 `stream()` 表现完全一致。模型只被调用 1 次（说明工具循环根本没启动）。

### 排查路径（每一步都拿到硬证据，没有靠猜）

| 步骤 | 手段 | 结论 |
|---|---|---|
| 1 | 打印工具对象数量 | 已注册（`tools=1`）—— 排除「没传进去」 |
| 2 | `ToolCallbacks.from(taskTools)` | **8 个回调全部解析成功** —— 排除「`@Tool` 没被识别」 |
| 3 | 单纯单元测试 `new ChatResponse(List.of(new Generation(带 toolCall 的 AssistantMessage)))` | `hasToolCalls() == true` —— 排除「响应结构不对」 |
| 4 | 自写一个 Advisor 打印到达时的 options 类型 | **`DefaultChatOptions`，`instanceof ToolCallingChatOptions == false`** ← 抓到真凶 |
| 5 | 反编译 `ToolCallingAdvisor.adviseCall` | offset 12–43：`if (!(options instanceof ToolCallingChatOptions)) return chain.nextCall(request);` —— **静默早退** |
| 6 | 反编译 `DefaultChatClientUtils.toChatClientRequest` | offset 190–231：以 `chatModel.getOptions().mutate()` 为基准，**只有该 builder 是 `ToolCallingChatOptions.Builder` 时才会把工具回调写进 options** |
| 7 | 反编译 `ChatModel` 接口默认实现 | `getOptions()` 返回**普通** `ChatOptions`；`getDefaultOptions()` 只是 `return getOptions()` |

### 根因

桩模型只重写了 `getDefaultOptions()`（**而它只是转发给 `getOptions()`**），
而框架真正调用的是 `getOptions()`，于是返回了普通 `DefaultChatOptions` →
ChatClient 不注入工具回调 → `ToolCallingAdvisor` 判定不满足前提 → **直接放行，工具永不执行**。

**修复**：桩模型必须重写 `getOptions()` 并返回 `ToolCallingChatOptions`。

### 对生产代码的启示（这才是要记住的部分）

1. **生产路径不受影响**：真实厂商的 Options（`OllamaChatOptions` / `OpenAiChatOptions` 等）
   都实现了 `ToolCallingChatOptions`，所以官方接入方式是正常的。
   这个坑只在**自己实现 `ChatModel`**（测试桩、自定义网关、Mock）时才会踩到。
2. **但有一条生产结论必须保留**：`ChatClient` 的静态工厂 `ChatClient.builder(chatModel)`
   **不会**自动装配 `ToolCallingAdvisor`；只有自动配置的 Builder 才会（`ChatClientAutoConfiguration`
   引用了它）。因此本项目改为**一律显式装配** `ToolCallingAdvisor`，
   不去赌「当前配置下自动配置的 Builder 是否存在」。
3. **这类故障的教训**：它不抛异常、不打日志、接口返回 200、对话看起来完全正常。
   如果没有「以数据库行数为断言口径」的集成测试，它会一路混到生产，
   表现为「助手答应了但什么都没做」——而所有接口监控都是绿的。

### 顺带确认的两个事实

- `ChatModel.getDefaultOptions()` 与 `getOptions()` 是**反向**委托：`getDefaultOptions()` → `getOptions()`。
  若以为「重写 getDefaultOptions 就够了」，症状正是上面那个静默失败。方向搞反不会报错，只会让人排查很久。
- 工具调用上下文通过 `ToolContext` 参数注入（`MethodToolCallback` 引用了 `ToolContext`），
  因此工具方法可以拿到 `conversationId` / `turnId`，无需依赖会在线程切换中丢失的 `ThreadLocal`。

