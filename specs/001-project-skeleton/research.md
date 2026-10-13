# Research: 阶段 0 — 项目骨架初始化

> 每个决策均以 Maven Central / npm registry / 官方文档为事实来源。凡未亲自核实的结论，均标注「未验证」。

## R-01 后端框架版本：Spring Boot 4.1.1

**Decision**：`spring-boot-starter-parent` 版本取 **4.1.1**。

**Rationale**：
- Maven Central 上 `org.springframework.boot:spring-boot-starter-parent` 的 4.1.x 线有 4.1.0（2026-06-11）与 **4.1.1（2026-08-20，当前最新）**；4.2.x 仅有 `4.2.0-M1/M2` 里程碑版，不适合作为稳定项目基线。
- 4.1.1 已包含 4.0.7 的全部缺陷与安全修复，且是 Spring AI 2.0.1 starter 硬编码依赖的 Boot 版本（见 R-02）。
- Boot 4.x 基于 Spring Framework 7.0.x，Java 基线为 17，本项目用 Java 21（LTS）在支持范围内。

**Alternatives considered**：
- Spring Boot 4.2.0-M2：里程碑版，有破坏性变更风险，开源项目不应以里程碑为基线 → 否决。
- Spring Boot 4.0.8：仍在维护，但 4.1.x 为当前主线且是 Spring AI 2.0.1 的目标版本 → 否决。

**验证方式**：`curl -sI https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-starter-parent/4.1.1/spring-boot-starter-parent-4.1.1.pom` → HTTP 200。

## R-02 AI 框架版本：Spring AI 2.0.1（只导入 BOM，不引 Starter）

**Decision**：`dependencyManagement` 导入 `org.springframework.ai:spring-ai-bom:2.0.1`；**阶段 0 不引入任何 `spring-ai-starter-*`**。

**Rationale**：
- Spring AI 2.0.0 GA 于 2026-06-12 发布，**2.0.1（2026-08-20）为当前最新**。
- 官方明确 Spring AI 2.0 **最低支持 Spring Boot 4**，支持 4.0.x / 4.1.x；2.0.1 的 starter POM 直接依赖 `spring-boot-starter*:4.1.1`，与 R-01 完全对齐。
- Spring AI 2.0 是一次重构：Jackson 2 → **Jackson 3**，工具调用架构统一，引入 JSpecify 空安全注解。**1.x 与 2.x 的 artifact 命名不兼容**（2.x 统一为 `spring-ai-starter-model-{provider}` / `spring-ai-starter-vector-store-{store}`），宪法要求不得混用。
- 阶段 0 的目标是「无密钥即可启动」。任何 `spring-ai-starter-model-*` 都会引入需要 API Key 的模型自动配置，导致骨架在无密钥时启动失败，与 SC-004 / 假设相冲突 → 阶段 0 只锁版本，阶段 1 再加 Starter。

**Alternatives considered**：
- 阶段 0 直接引入 `spring-ai-starter-model-openai`：需要 `spring.ai.openai.api-key`，否则自动配置报错；骨架将无法「开箱可跑」 → 否决。
- 使用 Spring AI 1.1.x：与 Spring Boot 4 不兼容（1.x 面向 Boot 3.x） → 否决。

**已核实的 2.0.1 真实 artifact（取自 spring-ai-bom 2.0.1 的 POM，阶段 1 直接可用）**：
`spring-ai-starter-model-openai`、`spring-ai-starter-model-ollama`、`spring-ai-starter-model-deepseek`、
`spring-ai-starter-model-anthropic`、`spring-ai-starter-vector-store-pgvector`、
`spring-ai-starter-model-chat-memory`、`spring-ai-starter-model-chat-memory-repository-jdbc`、
`spring-ai-starter-mcp-client`、`spring-ai-starter-mcp-server-webmvc`、`spring-ai-rag`、
`spring-ai-client-chat`、`spring-ai-advisors-vector-store`(→ 实为 `spring-ai-vector-store-advisor`)。

**验证方式**：`curl` 拉取 `spring-ai-bom-2.0.1.pom` 并解析其 `<dependencyManagement>` → HTTP 200 且 artifact 列表已逐项确认。

## R-03 构建工具：Maven 3.9+ + Maven Wrapper

**Decision**：`backend/` 内置 Maven Wrapper（`mvnw`），CI 与开发者一律用 `./mvnw`；本机使用已安装的 Maven 3.9.16。

**Rationale**：
- 本机 `mvn` 不在 PATH（此前仅通过 Wrapper 缓存了 3.9.16）。内置 `mvnw` 可让任何人在无 Maven 的机器上直接构建，这是开源项目的通行做法。
- Spring Boot 4 要求 Maven 3.9+；wrapper 自带 3.9.x 可满足。
- 额外收益：避免团队成员 Maven 版本漂移。

**Alternatives considered**：要求贡献者预装 Maven → 增加上手门槛，与 US4 冲突 → 否决。

## R-04 数据库：PostgreSQL 17 + pgvector（镜像 `pgvector/pgvector:pg17`）

**Decision**：`compose.yml` 与集成测试统一使用 `pgvector/pgvector:pg17`。

**Rationale**：
- Spring AI 的 `spring-ai-starter-vector-store-pgvector` 在 2.0 中**默认关闭自动建表**（`initialize-schema=false`），官方推荐由 Flyway/Liquibase 管理 `vector_store` 表 → 与本项目「结构变更走 Flyway」的宪法一致。
- `pgvector/pgvector` 官方镜像同时预装 `vector` / `hstore` / `uuid-ossp` 扩展，`V1` 迁移可直接 `CREATE EXTENSION`。
- Docker Hub 上该仓库存在 `pg13`–`pg18` 各主版本的 tag（实测 tag 列表）；选择 pg17 兼顾「现代」与「生态成熟度」。
- PostgreSQL 16+ 对 HNSW 索引支持完善，pg17 亦在支持周期内。

**Alternatives considered**：
- Python 侧独立向量库（Chroma/Milvus）：引入第二套存储与服务，违反原则 I → 否决。
- 使用本机已缓存的 `pgvector/pgvector:pg16`：可用，但 pg17 更适合作为新项目基线 → 选 pg17。

**验证方式**：`docker pull pgvector/pgvector:pg17`（实际执行，见 quickstart 验证记录）。

## R-05 默认数据库端口：55432

**Decision**：默认 host 端口取 **55432**，并可由环境变量 `POSTGRES_PORT` 覆盖；容器内始终是 5432。

**Rationale**：
- **实测本机 5432 与 5433 均已被其它项目的容器占用**（`lsof` 显示两者均由 Docker 代理监听）。若默认取 5432，`docker compose up -d` 在本机**必然失败**，直接破坏 US1 的「开箱可跑」与 SC-001。
- 55432 是 PostgreSQL 官方 Docker 文档中常见的「非默认端口」约定，语义清晰且冲突概率低。
- 容器内保持标准 5432，因此镜像、健康检查与迁移脚本不受影响。

**Alternatives considered**：
- 默认 5432、仅文档提示改端口 → 在本机与任何已跑 PostgreSQL 的开发机上开箱即失败，需人工介入后才能验证 → 否决。
- 默认 5433 → 本机同样被占用 → 否决。

## R-06 统一响应与异常策略

**Decision**：统一成功/失败信封 `ApiResponse<T>{code,message,data,timestamp}`；错误码集中在 `ErrorCode` 枚举；全局处理 `@RestControllerAdvice`，覆盖业务异常、参数校验、资源不存在、兜底异常。

**Rationale**：
- 前端只需判断 `code`，无需解析 HTTP 状态与消息体两套语义。
- 兜底处理器统一返回 `INTERNAL_ERROR` 且**不回传堆栈**，直接满足 FR-009 / SC-007。
- 与 Spring 原生 `ProblemDetail` 相比，信封式更贴近本项目前端约定；代价是偏离 RFC 9457，记为已知取舍（阶段 1 若接入第三方客户端再评估）。

**Alternatives considered**：直接使用 `ProblemDetail`（RFC 9457）→ 语义更标准，但与「前端统一判 code」的约定不一致，且校验错误结构定制成本更高 → 暂否决，记录待评估。

## R-07 集成测试隔离方案：Testcontainers

**Decision**：集成测试使用 Testcontainers 拉起 `pgvector/pgvector:pg17`，通过 `@ServiceConnection` 注入数据源。

**Rationale**：
- 集成测试必须验证「Flyway 迁移在真实 PostgreSQL 上可执行」这一核心主张（SC-002）；H2 无法验证 pgvector 扩展。
- 复用开发者已装的 Docker，无需额外服务。
- 代价：`mvn test` 需 Docker 可用；已在 README 与 quickstart 中显式说明。

**Alternatives considered**：
- 用 H2 跑上下文测试 → 无法验证 `CREATE EXTENSION vector`，证明力不足 → 否决。
- 用「外部已启动的 compose 数据库」跑测试 → 测试依赖外部状态，非 hermetic → 否决。
- 用 `@SpringBootTest` + 关闭 DataSource 自动配置 → 无法验证迁移 → 否决。

## R-08 前端 UI 库引入方式

**Decision**：阶段 0 采用 Element Plus **全量引入**（`app.use(ElementPlus)`）。

**Rationale**：保证在最少配置下生产构建一次通过（SC-003）；按需引入需要 `unplugin-auto-import` + `unplugin-vue-components` 及样式解析器，配置面更大。牺牲部分包体，换取骨架阶段的确定性与可验证性。

**Alternatives considered**：`unplugin-*` 按需引入 → 包体更优，但阶段 0 收益有限、风险更高 → 列为阶段 1 优化项（已记入宪法 Follow-up TODOs）。

## R-09 前端跨域策略

**Decision**：开发期由 Vite `server.proxy` 将 `/api` 转发到 `http://localhost:8080`；前端 Axios `baseURL` 取相对路径 `/api/v1`。

**Rationale**：避免开发期引入 CORS 配置（生产由同域反代解决），前端代码无需感知后端地址。

**Alternatives considered**：后端开启 CORS → 生产环境并不需要，且放宽跨域属安全面变更 → 否决。

## R-10 许可证

**Decision**：Apache-2.0。

**Rationale**：与 Spring 生态主流一致，含显式专利授权条款，适合以开源为目标的项目。

**Alternatives considered**：MIT → 更宽松但无专利条款；本项目可能涉及「AI 工具调用」等实现，专利授权更有保障 → 选 Apache-2.0。

## R-11 Spring Boot 4 模块化对依赖选择的影响（实测踩坑与修正）

Spring Boot 4 把原本集中在 `spring-boot-autoconfigure` / `spring-boot-test-autoconfigure` 的自动配置按下沉模块拆分。以下四条均为**实测结论**（直接解包 Maven Central 上的 jar 验证），不是推测：

| 事项 | Boot 3 的位置 | Boot 4 的实际位置 | 影响 |
|---|---|---|---|
| Flyway 自动配置 | `spring-boot-autoconfigure` | **`spring-boot-flyway`**（在 `spring-boot-autoconfigure-4.1.1.jar` 中为 0 处命中） | 只加 `flyway-core` 时**迁移静默不执行**：应用正常启动、无任何报错，但 `flyway_schema_history` 不生成、`V1` 扩展未创建。必须改用 `spring-boot-starter-flyway` |
| `@WebMvcTest` | `spring-boot-test-autoconfigure` | **`spring-boot-webmvc-test`**（需 `spring-boot-starter-webmvc-test`） | 只引 `spring-boot-starter-test` 拿不到 Web 切片测试 |
| `ErrorController` | `org.springframework.boot.web.servlet.error` | **`org.springframework.boot.webmvc.error`**（marker 接口，无方法） | 自定义 `/error` 的包名需改写 |
| `org.testcontainers:postgresql` / `junit-jupiter` | 同名 artifact | **`org.testcontainers:testcontainers-postgresql` / `testcontainers-junit-jupiter`**（旧名在 2.0.5 返回 404） | 依赖名与类名双变更：类迁至 `org.testcontainers.postgresql.PostgreSQLContainer`（非泛型，有 `String` 构造器） |

**Decision**：基于上表修正 `pom.xml`，并采用「`spring-boot-starter-flyway` + `flyway-database-postgresql`」的组合。

**最重要的教训**：这次失败**不是编译错误，而是运行时静默失效**——`mvn compile` 全绿、应用启动全绿、健康检查全绿，只有「数据库里没有 vector 扩展」这一个事实暴露了问题。这正是宪法原则 V 要求「必须实际运行验证」的原因：如果只做编译验证就宣称阶段 0 完成，会交付一个**迁移根本没跑**的骨架。

**Alternatives considered**：
- 只加 `flyway-core` 并用 `@Import` 手工装配 `FlywayAutoConfiguration` → 与 Spring Boot 约定背道而驰，且后续升级更易碎 → 否决。
- 放弃 Flyway 改用 JPA 自动建表 → 违反宪法原则 IV → 否决。

## R-12 前端依赖版本选择

**Decision**：Vue 3.5 / Vite 8 / vue-router 5 / Pinia 4 / Element Plus 2 / Axios 1；TypeScript 版本按实测兼容性决定（见下）。

**Rationale**：
- 版本取自 npm registry 的 `dist-tags.latest`，并用各包的 `peerDependencies` 交叉校验一致性：
  `vue-router@5.4.0` 声明 `vue ^3.5.34 || ^4.0.0`、`vite ^7.3.0 || ^8.0.0`、`pinia ^3.0.4 || ^4.0.2` —— 与 Vue 3.5.44 / Vite 8.3.4 / Pinia 4.0.3 完全相容。
  `pinia@4.0.3` 声明 `vue ^3.5.11`、`typescript >=5.6.0`。
  `@vitejs/plugin-vue@6.0.10` 声明 `vite ^5||^6||^7||^8`。
  `element-plus@2.14.7` 声明 `vue ^3.3.7`。
- 引擎要求：Vite 8 需要 Node `^20.19.0 || >=22.12.0`，本项目用 Node 22.22.2，满足。

**TypeScript 的取舍（已实测得出结论）**：npm 上 `typescript` 的 `latest` 已是 **7.0.2**（原生重写版本），5.x 线最新为 5.9.3。

- **实测 7.0.2 不可用**：`vue-tsc@3.3.13` 在运行时会 `require.resolve('typescript/lib/tsc')`，而 TypeScript 7 的 `package.json` 已不再导出 `./lib/tsc` 子路径，直接抛
  `Error [ERR_PACKAGE_PATH_NOT_EXPORTED]: Package subpath './lib/tsc' is not defined by "exports"`。
  即 **`typescript >= 7` 与 `vue-tsc` 3.x 事实上不兼容**，尽管 vue-tsc 的 peer 只写了 `>=5.0.0`（peer 范围过宽，不可作为兼容性依据）。
- **Decision**：锁 `typescript ~5.9.3`。这是本阶段唯一一处「不取 latest」的依赖，理由是**兼容性实测结果**而非保守偏好。
- 附带结论：`vite build` 不依赖 tsc（走 esbuild/rolldown），因此在 TS 7 下依然能构建成功 —— 这掩盖了问题，只有单独跑类型检查才会暴露。故 `type-check` 必须作为独立门禁，不能只看 `build` 是否绿灯。

**已知包体问题**：全量引入 Element Plus 导致主包 1.01 MB（gzip 327 KB），`vite build` 会给出 chunk > 500 kB 的告警。这是 R-08 取舍的直接代价，**不是错误**；阶段 1 改为按需引入后应显著下降。

