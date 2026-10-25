# Personal AI OS

开源、自托管、可扩展的**个人智能操作系统**。

用自然语言和个人 AI 助手交互，让它帮你管理任务、检索自己的知识库、安排日程与提醒——全部跑在你自己的机器上。

> **当前状态**：阶段 13（日历月视图）实现完成，接下来按新功能逐个开阶段。
> 五个内置模块可用：对话、任务、知识库、日程与提醒、长期记忆；助手内置 15 个工具，
> 并可经 MCP 接入外部工具 server（只读直行 / 变更类需人工确认）。
> 「活动」页可查助手调用过哪些工具、以及每个 MCP server 当前是否连上。

## 这个项目要做什么

1. 用自然语言与个人 AI 助手交互
2. 通过 AI 工具调用管理个人任务
3. 构建个人知识库，支持 RAG 检索问答
4. 管理个人日程与定时提醒
5. 多轮对话与可持久化的会话记忆
6. 通过 MCP 扩展外部工具
7. 未来扩展 Coding Agent、资讯 Agent 等独立模块

**明确不做**（第一版）：多租户 SaaS、支付系统、复杂权限管理。
第一版只支持**本地单用户**；若需对公网开放，必须先完成身份认证设计。

## 技术栈

| 层 | 技术 | 版本 |
|---|---|---|
| 后端 | Java / Spring Boot / Spring AI (BOM) | 21 / 4.1.1 / 2.0.1 |
| Web | Spring Web MVC + SSE | — |
| 持久化 | Spring Data JPA + Flyway | Flyway 12.4.0 |
| 数据库 | PostgreSQL + pgvector | 17（镜像 `pgvector/pgvector:pg17`） |
| 构建 | Maven（含 Wrapper） | 3.9+ |
| 前端 | Vue 3 / TypeScript / Vite | 3.5 / — / 8 |
| 前端生态 | Vue Router / Pinia / Element Plus / Axios | 5 / 4 / 2 / 1 |
| 测试 | JUnit 5 + Mockito + Testcontainers | 由 Boot BOM 管理 |

版本选择依据与兼容性论证见 [`specs/001-project-skeleton/research.md`](specs/001-project-skeleton/research.md)。

## 目录结构

```
personal-ai-os/
├── backend/                    Spring Boot 应用（根包 com.personalos）
│   ├── mvnw / mvnw.cmd         Maven Wrapper（无需预装 Maven）
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/personalos/
│       │   ├── PersonalOsApplication.java
│       │   ├── common/         统一响应 / 错误码 / 全局异常 / 健康检查 / 公共配置 / AI 装配（common/ai）
│       │   ├── ai/             空占位：跨模块 AI 基础设施实际在 common/ai（未搬迁）
│       │   ├── chat/           阶段 1：会话与消息（含会话内上下文窗口）
│       │   ├── memory/         阶段 8：长期记忆（跨会话，注入系统提示）
│       │   ├── agent/          阶段 2：Agent 编排与工具调用（含审计查询）
│       │   ├── task/           阶段 2：任务
│       │   ├── knowledge/      阶段 3：知识库与 RAG
│       │   ├── calendar/       阶段 4：日程与提醒
│       │   ├── mcp/            阶段 6：MCP 客户端（阶段 11 加状态查询）
│       │   └── export/         阶段 12：数据导出（SPI + 组装）
│       ├── main/resources/
│       │   ├── application.yml
│       │   └── db/migration/   Flyway 迁移脚本
│       └── test/java/com/personalos/
│           ├── PersonalOsApplicationTests.java      （Testcontainers 集成测试）
│           └── common/                              （单元测试）
├── frontend/                   Vue 3 + TypeScript + Vite
│   └── src/{api,lib,router,stores,types,views,styles}
├── docs/                       架构与开发文档
├── specs/                      Spec Kit 特性规格
├── .specify/                   Spec Kit 配置与项目宪法
├── compose.yml                 PostgreSQL + pgvector
├── .env.example                环境变量模板
└── LICENSE                     Apache-2.0
```

## 快速开始

前置：JDK 21、Docker（含 `docker compose`）、Node.js `^20.19.0 || >=22.12.0`。
**无需预装 Maven** —— 使用仓库自带的 `backend/mvnw`。

### 1. 启动基础设施

```bash
cp .env.example .env
docker compose up -d
docker compose ps      # 等待 postgres 变为 healthy
```

### 2. 启动后端

```bash
cd backend
./mvnw spring-boot:run
```

自检：

```bash
curl http://localhost:8080/api/v1/health
curl http://localhost:8080/actuator/health
```

### 3. 启动前端

```bash
cd frontend
npm install
npm run dev            # http://localhost:5173
```

首页会实时展示后端健康状态。更详细的步骤、配置项与排错见 [`docs/development.md`](docs/development.md)。

## 构建与测试

```bash
# 后端：单元测试 + Testcontainers 集成测试（需 Docker 运行）
cd backend && ./mvnw -B verify

# 前端：类型检查 + 生产构建
cd frontend && npm run type-check && npm run build
```

## 阶段路线图

| 阶段 | 内容 | 状态 |
|---|---|---|
| 0 | 项目骨架：Monorepo、后端基座、数据库迁移、前端基座、文档 | ✅ |
| 1 | AI 对话与聊天历史（ChatClient + ChatMemory + SSE） | ✅ |
| 2 | 任务 CRUD 与 Tool Calling（含两阶段人工确认与两层幂等） | ✅ |
| 3 | 个人知识库与 RAG（pgvector 向量检索 + 引用溯源） | ✅ |
| 4 | 日程与定时提醒（重复规则 + 到点提醒） | ✅ |
| 6 | MCP 客户端接入（外部工具 + 策略分级 + 人工确认） | ✅ |
| 7 | 会话重命名（部分更新语义 + 标题校验口径统一） | ✅ |
| 8 | 长期记忆（显式写入 + 每次对话注入 + 管理页） | ✅ |
| 9 | 可靠提醒（浏览器通知 + 站内降级 + 权限状态可见） | ✅ |
| 10 | 富文档解析（PDF 文本层 / Word docx / 图片走视觉模型） | ✅ |
| 11 | 工具审计与 MCP 状态（活动页：调用记录分页筛选 + server 可用性） | ✅ |
| 12 | 数据导出（全部业务数据 → 单个 JSON 文件，首页一键下载） | ✅ |
| 13 | 日历月视图（7×6 网格、切月、按本地时区归属修复） | ✅ |

> 编号 5 空置：原定的「Docker 部署与开源发布」不在当前规划内，暂不实施。
> 阶段 6 保留既有编号，避免重编号导致 `specs/` 目录、迁移与提交历史错位。
> 后续功能阶段从新编号继续。

## MCP 外部工具接入

在 `.env` 里声明 server 即可让助手获得外部工具（默认不启用任何 server）：

```bash
MCP_ENABLED=true
MCP_SERVERS_FILESYSTEM_COMMAND=npx
MCP_SERVERS_FILESYSTEM_ARGS=-y,@modelcontextprotocol/server-filesystem,./mcp-sandbox
MCP_SERVERS_FILESYSTEM_AUTO_TOOLS=read_file,list_directory,directory_tree
```

**策略分级（安全默认值）**：

- 列进 `AUTO_TOOLS` 的工具按**只读**处理，直接执行；
- 其余全部按**变更类**处理，生成「待确认事项」，你在界面上确认后才会真正执行；
- 漏配的代价是多弹一次确认，而不是一次未授权的写入。

**运行期状态在「活动」页可查**：每个 server 是否连上、暴露几个工具、哪些是只读直行，
连不上时给出原因。这是刻意补上的一块 —— server 连不上只降级不阻断启动（见下条），
代价是这个故障原本**只出现在启动那一瞬间的日志里**，没有查询入口就等于静默失效。

**几条刻意的设计选择**：

- **外部工具经显式 Java 装饰层进入**（命名 `mcp_<server>_<工具>`、策略、审计、确认），
  副作用路径仍收敛在本进程内 —— 模型不直接访问外部系统。
- **故障区别对待**：server 连不上或超时只标记该 server 不可用（应用照常启动）；
  工具名与内置工具冲突则**启动失败**（模型按名调用会落到错误的工具上，正确性问题不降级）。
- **确认机制零特例**：MCP 的变更类工具复用「删除任务」同一套两阶段确认
  （令牌 / 有效期 / 单次性），摘要由服务端依据参数生成，不采信模型文本。
- **沙箱是 server 自己的事**：filesystem server 只访问启动参数里声明的目录，
  所以 `ARGS` 里务必写专用目录（如 `./mcp-sandbox`），不要开放 home 或整个磁盘。

## 日程与提醒

支持「不重复 / 每天 / 每周 / 每月 / 工作日」四类重复规则，以及全天事件。
提醒同时覆盖**日程**与**任务截止时间**；前端每 30 秒轮询一次「已到点未读」的提醒。

送达分两条通道：页面切到后台且**已授权通知**时弹浏览器通知，页面可见时用站内提示；
未授权或被阻止时，日程页会明确写出当前状态与恢复方法。
**限制（已在界面上写明）**：应用完全关闭时收不到 —— 真正的后台送达需要 Service Worker + Web Push，
不在当前规划内。

```bash
AI_CALENDAR_SCAN_ENABLED=true        # 定时扫描开关
AI_CALENDAR_SCAN_INTERVAL_MS=60000   # 扫描间隔
AI_CALENDAR_DEFAULT_LEAD=15m         # 任务提醒的默认提前量（日程可逐个覆盖）
```

**几条刻意的设计选择**：

- **调度是「数据库状态驱动的周期扫描」**，不是为每条提醒注册内存定时任务 ——
  后者重启即丢，用户会静默地收不到提醒。重启后行为一致这一点有测试与真机验证。
- **幂等靠数据库唯一约束**（`来源类型 + 来源标识 + 发生时刻`）+ `ON CONFLICT DO NOTHING`，
  不靠「先查再插」。反复扫描、重启、甚至系统时间回拨都不会产生重复提醒。
- **删除日程会一并删除它的提醒**；扫描还会清理「来源已不存在」的孤儿提醒 ——
  后者兜住那些不经过清理的删除路径（典型是任务删除：`task` 模块并不知道提醒的存在）。

> **「到点提醒」的送达方式**：页面轮询 + 浏览器通知。页面在后台且已授权时弹系统通知，
> 页面可见时用站内提示；**应用完全关闭时收不到**（真后台送达需要 Service Worker + Web Push，
> 不在当前规划内）。

## 知识库配置

上传即自动解析、分块、向量化，支持四类内容：

| 类型 | 说明 |
|---|---|
| `.md` / `.markdown` / `.txt` | 需 UTF-8；严格解码，编码不对直接报错而不是"尽力解码"成乱码 |
| `.pdf` | 取**文本层**；扫描件没有文本层，会明确提示需要 OCR（当前不支持） |
| `.docx` | 段落与**表格单元格**都会被提取 |
| `.png` / `.jpg` / `.jpeg` / `.webp` / `.gif` | 经视觉模型转录成文字；需要配置支持图片的模型 |

单文件 ≤ 5 MiB。检索与问答走 pgvector 的余弦相似度，默认返回 4 条、相似度阈值 0.3。

```bash
AI_EMBEDDING_PROVIDER=ollama          # 默认，本地无需密钥
OLLAMA_EMBEDDING_MODEL=bge-m3         # 1024 维
AI_KNOWLEDGE_EMBEDDING_DIMENSIONS=1024  # 必须与迁移里的 vector(1024) 一致
```

> ⚠️ **换嵌入模型前先确认输出维度**：维度已写进 Flyway 迁移（`vector_store.embedding`）。
> 维度不一致时应用会**在启动阶段就失败**并给出修复指引，而不是等到写入时才报 SQL 错。

本地跑真实语义检索需要 `ollama pull bge-m3`（约 1.2GB）；
没有真实模型时也可以用仓库自带的 mock（已实现 `/v1/embeddings`），
但要同时把相似度阈值调到 `0.05` —— 词袋嵌入的相似度尺度比真实模型低一个量级，
详见 [`docs/development.md`](docs/development.md) 第五节。
**自动化测试不需要任何模型** —— 它用确定性词袋嵌入替代，详见 `docs/development.md`。

## 配置模型提供商

默认走**本地 Ollama**（无需密钥）；也可以指向任意 OpenAI 兼容服务（DeepSeek / 通义 / Kimi / 智谱 / vLLM 等）：

```bash
# 本地 Ollama（默认）
AI_CHAT_PROVIDER=ollama OLLAMA_CHAT_MODEL=qwen2.5:7b

# 云端 OpenAI 兼容服务
AI_CHAT_PROVIDER=openai \
OPENAI_BASE_URL=https://api.deepseek.com \
OPENAI_API_KEY=sk-xxx \
OPENAI_CHAT_MODEL=deepseek-chat
```

**没有任何模型也能跑**：不配置模型时应用照常启动（健康检查可用），对话接口返回明确的 `50300 AI 模型未配置`。
用于本地联调的 OpenAI 兼容 mock 见 [`docs/development.md`](docs/development.md) 第五节。

## 数据备份

首页「数据备份」卡片可一键把全部业务数据导出为**一个 JSON 文件**：
`sessions`（含各自消息）、`tasks`、`calendarEvents`、`memories`、`knowledgeDocuments`（含分块文本）。
顶层还有 `counts`，它的值是各数组的**实际长度**，用来核对「文件里到底有几条」。

几条刻意的边界：

- **不含向量**：向量是「分块文本 + 嵌入模型」的派生物，换模型即失效，
  带上它只会让文件膨胀几十倍。
- **知识库不含原始文件**：上传的 PDF / Word / 图片本身不落库（只保留解析出的分块）。
  分块文本足以重建检索，但拿回原始文件只能重新上传 —— 界面上写明了这一点。
- **只有导出，没有导入**：导入的难点不在搬运数据，而在「目标库已有数据时怎么办」
  （按 id 覆盖？按内容合并？冲突谁优先？）。这些策略做错就是静默数据损坏，留给独立阶段。
- **不压缩、不加密**：文件落在你自己的机器上，自制加密只会制造「以为安全」的错觉。

对应端点 `GET /api/v1/export`，响应**直接是文件**（带 `Content-Disposition`），
不包统一响应信封 —— 失败时才返回 `{code, message}`。

## 开发约定

本项目使用 [Spec Kit](https://github.com/github/spec-kit) 做规格驱动开发，**项目宪法是全仓库最高约束**：

- 宪法：[`.specify/memory/constitution.md`](.specify/memory/constitution.md)
- 规格：`specs/NNN-<feature>/`（spec → plan → research → data-model → contracts → tasks）
- 阶段严格串行，不提前实现后续阶段

关键工程约定：

- 结构变更一律走 Flyway，`ddl-auto` 只能是 `validate`
- 所有写操作经过 Service 层；不向前端暴露 JPA Entity
- 统一响应结构；错误响应不得泄漏堆栈
- 统一构造器注入，禁止字段注入
- 仓库中不得出现任何密钥

## 文档

- [架构说明](docs/architecture.md)
- [本地开发指南](docs/development.md)
- [阶段 0 规格](specs/001-project-skeleton/spec.md) ｜ [实现计划](specs/001-project-skeleton/plan.md) ｜ [版本决策与踩坑](specs/001-project-skeleton/research.md)

## 许可证

[Apache License 2.0](LICENSE)
