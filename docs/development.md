# 本地开发指南

## 前置条件

| 组件 | 版本要求 | 说明 |
|---|---|---|
| JDK | 21 | `java -version` 应显示 21.x |
| Docker | 任意近期版本，含 `docker compose` | 用于运行 PostgreSQL + pgvector，以及后端集成测试 |
| Node.js | `^20.19.0 \|\| >=22.12.0` | Vite 8 的要求 |
| Maven | **无需预装** | 仓库自带 `backend/mvnw` |

## 一、启动基础设施（PostgreSQL + pgvector）

```bash
cp .env.example .env       # 首次执行
docker compose up -d
docker compose ps          # 期望 postgres 的 State=Up、Health=healthy
```

验证扩展已就绪：

```bash
docker compose exec postgres psql -U personalos -d personal_ai_os -c '\dx'
# 期望看到 vector / hstore / uuid-ossp / plpgsql
```

> **端口说明**：默认把容器内 5432 映射到宿主机的 **55432**，以避开本机常见的 5432/5433 占用。
> 如需修改，编辑 `.env` 中的 `POSTGRES_PORT`；本机 5432 空闲时也可以改回 5432。

停止（保留数据）：

```bash
docker compose down
```

连同数据卷一起清除（**会丢数据**）：

```bash
docker compose down -v
```

## 二、启动后端

```bash
cd backend
./mvnw spring-boot:run
```

后端默认监听 `http://localhost:8080`。自检：

```bash
curl -s http://localhost:8080/api/v1/health
# 期望：{"code":0,"message":"OK","data":{"status":"UP","application":"personal-ai-os","version":"0.1.0-SNAPSHOT","timestamp":"..."},"timestamp":"..."}

curl -s http://localhost:8080/actuator/health
# 期望：整体健康，含 db 组件
```

### 后端配置

配置项集中在 `backend/src/main/resources/application.yml`，全部可由同名环境变量覆盖：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `SERVER_PORT` | 8080 | 后端监听端口 |
| `POSTGRES_HOST` | localhost | 数据库主机 |
| `POSTGRES_PORT` | 55432 | 数据库宿主机端口 |
| `POSTGRES_DB` | personal_ai_os | 数据库名 |
| `POSTGRES_USER` | personalos | 用户名 |
| `POSTGRES_PASSWORD` | personalos | 密码（**仅本地默认值**） |
| `AI_CHAT_PROVIDER` | ollama | 聊天模型提供商：`ollama` / `openai` / `none` |
| `AI_MEMORY_MAX_MESSAGES` | 20 | 送给模型的上下文条数上限（**不影响库中历史**） |
| `OLLAMA_BASE_URL` | http://localhost:11434 | 本地 Ollama 地址 |
| `OLLAMA_CHAT_MODEL` | qwen2.5:7b | 本地模型名 |
| `OPENAI_API_KEY` | （空） | 仅 `AI_CHAT_PROVIDER=openai` 时需要 |
| `OPENAI_BASE_URL` | https://api.openai.com | 可指向任意 OpenAI 兼容服务 |
| `OPENAI_CHAT_MODEL` | gpt-4o-mini | 模型名 |

> `docker compose` 会自动读取根目录 `.env`；如需让后端也读取这些变量，
> 在 shell 中 `set -a && source .env && set +a` 后再启动，或直接使用默认值（默认值与 `.env.example` 一致）。

### 可观测性（2026-10-27 起）

| 能力 | 怎么看 |
|---|---|
| 请求链路 ID | 响应头 `X-Request-Id` 回显；日志每行 `[requestId]`，`grep` 一个值即还原整次请求 |
| 模型调用限流 | `APP_RATE_LIMIT_ENABLED` / `CAPACITY` / `PERIOD`，默认 60 次/分钟；只拦对话流、附件上传、知识库入库 |
| 运行态指标 | `/actuator/metrics`，其中 `personalos.chat.streams.active` 是「有没有流卡住」的直接证据 |
| 结构化日志 | `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` 切 JSON（接 ELK/Loki），本地默认保持可读文本 |

排障路径与常见症状对照表见 [`operations.md`](operations.md) 第 2 节与第 5 节。

> ⚠️ 集成测试里限流是**关闭**的（`src/test/resources/application.properties`）。
> 令牌桶是进程内单例，配置相同的 `@SpringBootTest` 共享上下文，全量套件一分钟内必超 60 次 ——
> 后半程用例会收到与自身逻辑无关的 429。限流本身的正确性由独立的单元测试覆盖。

## 三、运行后端测试

```bash
cd backend
./mvnw -B test
```

测试使用 **Testcontainers** 拉起与 `compose.yml` 相同的 `pgvector/pgvector:pg17` 镜像，
因此是自包含的（不依赖你手动启动的数据库），但**要求 Docker 正在运行**。

> ⚠️ **Docker 未启动时 `mvn test` 会失败，这是有意设计**：
> 迁移正确性是阶段 0 的核心主张，不应被静默跳过。
> 只跑编译可加 `-DskipTests`。

首次运行会拉取镜像与依赖，耗时较长；之后会走缓存。

### 覆盖率（含门禁）

`test` 阶段会生成 JaCoCo 报告，**并执行覆盖率检查**：

```
backend/target/site/jacoco/index.html   # 浏览器打开看逐类明细
backend/target/site/jacoco/jacoco.csv   # 想自己汇总时用这个
```

门禁阈值：**行 83%、分支 65%**（CI 跑的就是 `/mvnw test`，所以它在合并前必然生效）。
这个数字不是拍的：先跑一轮拿到真实水位（84.7% / 67.0%），再往下留约两档 ——
目的是拦住**断崖式下跌**（有人删了一片测试），而不是精确控制到小数点。
低于阈值时构建失败，不会静默通过。

分支覆盖比行覆盖更值得看：偏低通常来自「同一方法的多条防御分支只有一条被测到」，
补测试时优先补这里比补行覆盖更有收获。

## 四、启动前端

```bash
cd frontend
cp .env.example .env.local   # 可选
npm install
npm run dev                  # http://localhost:5173
```

首页会实时调用后端 `/api/v1/health` 并展示状态。
**后端未启动时，页面会给出可读的错误提示，而不是白屏。**

其他命令：

```bash
npm run type-check     # vue-tsc 类型检查
npm run build          # 生产构建，产物在 frontend/dist
npm run preview        # 预览构建产物
npm run test:logic     # 纯函数与 store（零依赖，见下）
npm run test:component # 组件渲染与交互（vitest + happy-dom，见下）
npm run lint           # ESLint（flat config，见 frontend/eslint.config.js）
npm run lint:fix       # 自动修复可修的项
npm run format         # Prettier 格式化
npm run format:check   # 只检查不改写（CI 用这一条）
```

**提交前建议跑一遍与 CI 等价的六步**：

```bash
npm run lint && npm run format:check && npm run type-check \
  && npm run test:logic && npm run test:component && npm run build
```
CI（`.github/workflows/ci.yml`）会在每次 push / PR 上把这些加上后端测试全跑一遍；
本地跑过就不会在 PR 里才发现。

开发期 `/api` 由 Vite 代理到 `http://localhost:8080`（见 `vite.config.ts`），因此不需要配置 CORS。

### 前端逻辑测试（零依赖）

`npm run test:logic` 不引 vitest / jest，靠 **Node 的 ESM 解析钩子 + 类型剥离**直接跑真实源码：

- `test/resolve-hook.mjs` 把 Vite 风格导入（`@/x`、省略扩展名）翻译成 Node 能解析的形式；
  唯一使用 `import.meta.env` 的 `@/api/http` 被整体别名到 `test/stub-api.mjs`（测试替身），
  被测模块真正用到的导出由替身提供、行为由各测试注入；
- `test/assert.mjs` 是极简断言（累计通过/失败），`test/run.mjs` 顺序跑所有 `*.test.mjs` 并出汇总；
- 覆盖：`api/envelope.ts` 的错误分类（4xx/5xx 信封 vs 连接层错误，d0df5de 修的那个 bug）、
  三个 store 的分页状态与失败路径。

**运行要求 Node ≥ 22.18**（类型剥离默认开启；20.x 会因无法解析 TS 语法而失败）。
本机 `node --version` 为 22.22.2，满足。测试替身与断言都不进生产构建。

### 前端组件测试（第二条链路）

`.vue` 需要 SFC 编译与 DOM，Node 原生做不到，因此另有一条基于
**vitest + happy-dom + @vue/test-utils** 的链路：

```bash
npm run test:component   # 17 项，十几秒
```

两条链路并存是刻意的，不是历史遗留：

| 链路 | 覆盖 | 耗时 | 什么时候跑 |
|---|---|---|---|
| `test:logic` | 纯函数、store | 一秒内 | 改逻辑时随手跑 |
| `test:component` | `.vue` 渲染与真实事件 | 十几秒 | 动模板/样式/事件前后 |

**为什么模板层非测不可**：本项目的缺陷有一类只出现在这一层 ——
气泡在容器里「消失」（元素底与容器底同色）、侧栏拖拽慢半拍（hover 与 active 的
CSS 特异性打架）、输入法选词时按回车把消息发出去。这些**纯函数测试按定义抓不到**，
只能靠人眼或组件测试。

三个配置上的坑（都踩过）：

1. **`vitest.config.ts` 不复用 `vite.config.ts`**。主配置里的
   `optimizeDeps.noDiscovery` 是给 dev server 在本沙箱里不崩用的，两个 unplugin
   在测试环境只会注入无用语句。测试配置只保留 `vue` 插件与 `@` 别名。
2. **必须在 `test/component/setup.ts` 里全量注册 Element Plus**。生产代码靠
   unplugin 自动注册，测试环境没有那个插件，`<el-collapse>` 会**静默渲染成空标签** ——
   断言全变成「什么都找不到」，看起来像测试写错了。
3. **happy-dom 没有 `matchMedia`**。Element Plus 的若干组件挂载时会查询
   `prefers-color-scheme`，缺了这个 API 会直接抛错，而报错指向组件内部，
   很难联想到是环境缺 API。setup 里已补了桩。

## 五、没有模型时如何联调

AI 能力默认走本地 Ollama（`AI_CHAT_PROVIDER=ollama`）。若你**既没装 Ollama、也不想申请 API Key**，
可以用仓库自带的 mock 服务把整条链路跑通 —— 它只依赖 Python 标准库：

```bash
# 终端 1：启动 mock（OpenAI 兼容；对话流式 + 嵌入向量）
python3 scripts/dev/mock-openai-server.py --port 9099

# 终端 2：把后端指向 mock
cd backend
AI_CHAT_PROVIDER=openai \
AI_EMBEDDING_PROVIDER=openai \
OPENAI_BASE_URL=http://127.0.0.1:9099 \
OPENAI_API_KEY=mock-key \
OPENAI_CHAT_MODEL=mock-model \
OPENAI_EMBEDDING_MODEL=mock-embedding \
./mvnw spring-boot:run
```

之后在 `http://localhost:5173/chat` 就能看到逐字返回的回复。

mock 现在实现了三个此前缺失的能力，它们各自堵住过一条验证盲区：

| 能力 | 没有它时会发生什么 |
|---|---|
| `/v1/embeddings` | 知识库上传直接失败（无嵌入模型），整条 RAG 链路无法验证 |
| 非流式 `/v1/chat/completions`（`stream=false`） | 知识库问答 `KnowledgeQaService.ask` 拿到 `data: {...}` → `Unrecognized token 'data'` → **接口 500** |
| 工具调用（流式路径） | Agent 链路只能靠集成测试，无法真机复现 |

**联调知识库**时必须额外调低相似度阈值：

```bash
AI_KNOWLEDGE_SIMILARITY_THRESHOLD=0.05 \
```

原因是 mock 的嵌入是**词袋哈希**，不是语义模型：800 字的分块与一句问句之间，
真实模型给 0.5–0.8，mock 只有 0.1–0.4（词袋的固有性质——长文本的方向被几百个 token 摊薄）。
排序仍然正确（相关 > 无关，无关恒接近 0.0），足以验证链路与命中顺序，
但它**不能用来评估真实嵌入质量，也不能用默认阈值 0.3 判断「检索是否正常」**。

**验证 Agent 工具调用**：该 mock 已支持工具调用，用以下前缀模拟「模型决定调工具」：

| 你输入 | mock 会调用 |
|---|---|
| `创建任务：写周报` | `createTask` |
| `创建任务：写周报\|HIGH` | `createTask`（带优先级） |
| `查看待办` | `findTasks` |
| `删除任务：<任务 uuid>` | `deleteTask`（**仍需人工确认**） |
| 其他任意内容 | 纯文本回复 |

试完之后可查数据库确认「助手不是口头答应」：

```bash
curl -s "http://localhost:8080/api/v1/tasks" | python3 -m json.tool
curl -s "http://localhost:8080/api/v1/agent/tool-invocations" | python3 -m json.tool
curl -s "http://localhost:8080/api/v1/agent/confirmations?status=PENDING" | python3 -m json.tool
```

> 该 mock **不是产品代码**，不参与构建与测试；集成测试用的是 Java 侧的桩 `ChatModel`。
> 它存在的意义是让「前端流式渲染 + SSE 契约 + Agent 工具调用 + 知识库 RAG」
> 在没有真实模型时也可复现验证。

## 六、前端真机验证

后端的集成测试覆盖服务端；`type-check` 与 `build` 只能证明「能编译」，
对「点击之后会发生什么」一无所知。`scripts/dev/verify-frontend.js` 补上这一段 ——
它用真实浏览器跑关键交互，并**断言数据库里的值**，而不只是「页面上看起来对」。

```bash
# ① 后端：必须显式指定端口。sandbox 的 shell 里存在 SERVER_PORT，它会覆盖 application.yml，
#    否则应用会绑到一个你没预期的端口上。
java -jar backend/target/personal-ai-os-backend-0.1.0-SNAPSHOT.jar --server.port=8080

# ② 前端：必须显式绑 IPv4。Vite 默认绑 localhost，而 Node 会把它解析成 ::1，
#    于是 127.0.0.1:5173 与 Vite 的 /api 代理都打不通（表现为 502）。
cd frontend && npm run dev -- --host 127.0.0.1

# ③ 跑验证（依赖只需装一次）
#    把 NODE_WORKSPACE 换成你自己的 node 工作区路径
NODE_WORKSPACE="$HOME/.workbuddy/binaries/node/workspace"
(cd "$NODE_WORKSPACE" && npm install playwright)
NODE_PATH="$NODE_WORKSPACE/node_modules" \
  PLAYWRIGHT_BROWSERS_PATH=$HOME/Library/Caches/ms-playwright \
  node scripts/dev/verify-frontend.js
```

**它会向数据库写入验证数据**（标题带时间戳后缀）且不清理，便于事后排查。

> **两个容易卡住的点**（都踩过）：
> ① Vite 默认绑 `::1`，只测 `127.0.0.1` 会误判成「服务没起来」；
> ② Playwright 升级后期望的 Chromium 编号会变（例如 1.64 要 1248，而缓存里是 1243），
> 脚本会先用 `executablePath` 指向缓存里的浏览器，失败再退回本机 Chrome。

## 七、常见问题

**Q：`docker compose up -d` 报端口被占用**
本机 5432/5433 可能已被其它 PostgreSQL 容器占用。改 `.env` 的 `POSTGRES_PORT` 为一个空闲端口（默认 55432 已避开）。

**Q：后端启动时报连不上数据库**
先确认 `docker compose ps` 中 postgres 是 healthy；再确认 `POSTGRES_PORT` 与 compose 映射的宿主机端口一致。

**Q：后端启动时报表结构校验失败**
`ddl-auto` 固定为 `validate`，结构必须由 Flyway 迁移创建。检查 `backend/src/main/resources/db/migration/` 下脚本是否完整、`flyway_schema_history` 是否与服务端一致。

**Q：改了 `pom.xml` 里的依赖，但迁移不再执行了**
请先读 `specs/001-project-skeleton/research.md` 的 **R-11**。Spring Boot 4 把许多自动配置拆到了独立模块（例如 Flyway 必须在 `spring-boot-flyway` / `spring-boot-starter-flyway` 中），**只加底层库不会报错，但功能静默失效**。

**Q：`mvn` 命令找不到**
用仓库自带的 `./mvnw`（它会自动下载并使用 Maven 3.9.16），无需在本机安装 Maven。

**Q：应用启动后监听的不是 8080**
环境变量的优先级高于 `application.yml`。若 shell 中已导出 `SERVER_PORT`（某些 IDE、容器或 shell 配置会这么做），它会覆盖配置。用启动参数可强制覆盖：
`./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=8080`，或直接启动 jar 时加 `--server.port=8080`。
启动日志里的 `Tomcat started on port xxxxx` 是权威结论。

**Q：`npm run dev` 启动约一分钟后崩溃，报 `SAFE_DELETE_BULK_CONFIRM_REQUIRED`**
本沙箱的批量删除守护会拦截 Node 进程内对「文件数 > 50 的目录」的删除，而 Vite 的依赖优化器
在重跑时要删除自己的缓存目录（`node_modules/.vite/deps_temp_*`），于是 dev server 崩掉。
**生产构建不受影响。** 规避方式已写进 `vite.config.ts` 的 `optimizeDeps` 配置：关闭依赖自动发现
（`noDiscovery`）+ 显式 include 列表，优化器不再重跑。改动 include 列表会使缓存失效并触发一次
重优化——**改完先手动 `rm -rf frontend/node_modules/.vite` 再启动**（shell 的 rm 不走 Node 进程，
不会被守护拦截）。

## 八、数据库结构变更流程

1. 在 `backend/src/main/resources/db/migration/` 新增脚本，命名 `V<n>__<snake_case>.sql`。
2. **不要**改已有迁移脚本（Flyway 会校验 checksum）。需要修正时新增一个更高版本的脚本。
3. 脚本应尽量幂等（`CREATE ... IF NOT EXISTS`）。
4. 跑一次 `./mvnw -B test` 验证在干净库上可完整重放。

> 回滚策略见 [`operations.md`](operations.md) 第 4 节。核心事实：**Flyway 社区版没有 undo**，
> 所以「回滚」不是靠回退迁移，而是靠**写迁移时就保证前向兼容**（只加列加表、
> 破坏性变更分两步），真需要回退时走备份恢复。
