# Contracts: 阶段 6 — MCP 客户端接入

> 按关注点拆四份契约：**配置 / 装配 / 策略与确认 / 审计**。
> 每份给出**判定顺序**（顺序本身往往是契约）与**必须有的行为用例**。

## 1. 配置契约（Configuration Contract）

### 1.1 规则

| # | 规则 |
|---|---|
| C1 | 配置只经环境变量（`MCP_*`）注入；仓库只提交 `.env.example` 模板 |
| C2 | `MCP_ENABLED=false`（默认）时，**不初始化任何 MCP 组件**：零连接、零工具、零日志噪音 |
| C3 | `MCP_SERVERS` 列出的每个名字 MUST 在 `servers` 映射中有对应配置；缺配置 → 启动失败（配置错误是用户错误，不降级） |
| C4 | `command` 为空 → 启动失败（同 C3） |
| C5 | `env` 的值允许引用其它环境变量（`${VAR}` 形式由 Spring 解析）；解析不到 → 按空串处理并告警，**不因此崩溃**（server 可能不需要它） |
| C6 | 敏感值（`env` 中的 key）MUST NOT 出现在启动日志、错误响应或审计记录中 |

### 1.2 必须有的行为用例

| 用例 | 期望 |
|---|---|
| 不配任何 MCP_* 变量 | 启动行为与阶段 5 之前逐字节一致（SC-003） |
| `MCP_ENABLED=true` 但 `MCP_SERVERS` 为空 | 启动成功，零工具，日志一条 info「MCP 已启用但未配置 server」 |
| `MCP_SERVERS=filesystem` 但无对应 `MCP_SERVER_FILESYSTEM_COMMAND` | 启动失败，错误信息指明缺哪个 server 的哪项 |
| `command` 指向不存在的可执行文件 | **不启动失败**：该 server 标记不可用 + 错误日志；其余 server 与应用功能正常（R-07） |

## 2. 装配契约（Assembly Contract）

### 2.1 判定顺序（启动期）

```
1. MCP_ENABLED=false ───────────────► 特性不初始化，结束
2. 逐个 server 建客户端（带 connectTimeout 超时）
   ├─ 连接/列举失败 ────────────────► 该 server 标记不可用，其工具不进入装配，继续下一个
   └─ 成功 ─────────────────────────► 拿到工具清单（name + inputSchema）
3. 工具 schema 自检（required 齐全、类型可解析）
   ├─ 异常 ─────────────────────────► 该工具剔除 + 告警，其余工具继续
   └─ 通过 ─────────────────────────► 进入命名
4. 命名：mcp_<server>_<tool>（确定性前缀，plan 定终形态）
5. 冲突校验：MCP 工具名 × 内置工具名（@Tool 扫描结果）
   ├─ 有交集 ───────────────────────► 应用启动失败，列出全部冲突项
   └─ 无交集 ───────────────────────► 全部工具挂载 ChatClient
```

### 2.2 必须有的行为用例

| 用例 | 期望 |
|---|---|
| 两个 server 暴露同名工具 | 前缀后名不同，双双装配成功 |
| MCP 工具名与内置工具名相同 | 启动失败；错误信息含「工具名 / 来源 server / 冲突的内置工具」三要素 |
| server 暴露 0 个工具 | 允许：该 server 标记可用但无工具，不视为故障 |
| server 列举超时 | 按不可用处理；启动总耗时不得因单个 server 显著拉长（超时上限可配） |
| 无 ChatModel | MCP 客户端照常初始化、工具照常列举（可观测），仅无人使用 |

## 3. 策略与确认契约（Policy & Confirmation Contract）

### 3.1 策略判定顺序

```
1. tool ∈ servers.<name>.autoTools ──► AUTO
2. 否则 ─────────────────────────────► CONFIRM（默认，fail-closed）
```

### 3.2 AUTO 契约

| # | 规则 |
|---|---|
| P1 | 调用直达底层 MCP 工具，不过确认机制 |
| P2 | 调用结果（含 server 侧错误）原样进入对话上下文 |
| P3 | 调用必记审计（§4） |

### 3.3 CONFIRM 契约

| # | 规则 |
|---|---|
| P4 | 调用被拦截：**不触达 MCP server**，生成 `action=MCP_TOOL_CALL` 的待确认记录 |
| P5 | 摘要由服务端依据 payload 生成：`MCP 工具调用确认：server=<name>，工具=<tool>，<关键参数 1~2 个>`；参数值截断（单值 ≤ 80 字符，总数 ≤ 3 个） |
| P6 | 用户确认（正确令牌、未过期、未消费）→ `McpConfirmationHandler` 真正调用 MCP 工具，结果与确认记录关联 |
| P7 | 令牌错误 / 过期 / 已消费 → 拒绝，**不执行**（复用 `ConfirmationService` 既有语义，零特例） |
| P8 | 策略只来自配置；对话内容（含「不用确认」的话术）不改变策略 |
| P9 | 确认执行阶段的 MCP 调用同样记审计，且与「生成待确认」那次拦截记录可区分（后者标记为 `PENDING_CONFIRMATION`） |

### 3.4 必须有的行为用例

| 用例 | 期望 |
|---|---|
| AUTO 工具调用 | 直接执行，无待确认记录，审计一条 |
| CONFIRM 工具调用 | 零执行触达 server；待确认记录生成；审计一条（PENDING_CONFIRMATION） |
| 确认后 | server 真被调用；审计一条（EXECUTED） |
| 确认时 server 已不可用 | 返回明确错误（含 server 名），确认记录状态正确流转，不悬挂 |
| 重复确认同一令牌 | 第二次拒绝（单次性） |
| 待确认过期后确认 | 拒绝，记录标 EXPIRED |

## 4. 审计契约（Audit Contract）

### 4.1 字段

复用阶段 2 的 `ToolInvocation` 记录结构，新增区分维度：

| 维度 | 取值 |
|---|---|
| 来源 | `BUILTIN`（既有）／`MCP`（新） |
| MCP 附加信息 | `server` 名（进入工具名或独立字段，plan 定） |
| 状态 | 既有状态机 + `PENDING_CONFIRMATION`（拦截未执行） |

### 4.2 必须有的行为用例

| 用例 | 期望 |
|---|---|
| MCP AUTO 成功 | 审计一条，含 server、工具、耗时、SUCCESS |
| MCP AUTO 失败（server 报错） | 审计一条，含脱敏后的 server 原始错误（FR-014） |
| MCP CONFIRM 拦截 | 审计一条，状态 PENDING_CONFIRMATION |
| MCP 确认执行 | 审计一条，状态 EXECUTED，可与拦截记录关联 |
| 内置工具调用 | 行为与字段与本阶段之前一致（来源 BUILTIN） |

## 5. 生命周期契约（Lifecycle Contract）

| # | 规则 |
|---|---|
| L1 | server 子进程随 Spring 容器启动而拉起、随关闭而销毁 |
| L2 | 应用关闭后 `ps` 无残留 server 进程（SC-04 真机断言 + 自动化测试断言） |
| L3 | 关闭顺序：先停 MCP 客户端连接，再由容器销毁子进程；不得依赖 server 「检测到 stdin 关闭自退」 |
| L4 | 单个 server 销毁失败 → 错误日志，不影响其它 server 与应用关闭 |

## 6. 前端契约（Presentation Contract）

| # | 规则 |
|---|---|
| F1 | MCP 待确认项经既有确认接口（`/api/v1/confirmations*`）呈现，**不新增接口** |
| F2 | 确认卡片 MUST 展示 server 名与工具名（用户在点「确认」前必须知道「这是哪个外部工具的什么操作」） |
| F3 | 本轮不新增任何 MCP 管理页面 / 入口（范围决策①） |
