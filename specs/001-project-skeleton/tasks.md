---

description: "Task list for 阶段 0 — 项目骨架初始化"
---

# Tasks: 阶段 0 — 项目骨架初始化

**Input**: Design documents from `/specs/001-project-skeleton/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/http-api.md

**Tests**: 本特性**要求**测试（spec FR-023 / SC-002），故测试任务为必做。

**Organization**: 按用户故事分组，每个故事可独立实现与验证。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无依赖）
- **[Story]**: 所属用户故事（US1 / US2 / US3 / US4）
- 所有路径均相对仓库根 `personal-ai-os/`

---

## Phase 1: Setup（仓库骨架）

**Purpose**: 建立 Monorepo 顶层结构与仓库卫生约束

- [x] T001 [P] 重写根 `.gitignore`，覆盖 Java/Maven（`target/`、`*.class`）、Node（`node_modules/`、`dist/`）、密钥（`.env`、`.env.*`、`!.env.example`）、IDE、macOS、`.workbuddy/`，并逐条用 `git check-ignore -v` 验证
- [x] T002 [P] 创建根 `.env.example`，列出 `POSTGRES_DB/USER/PASSWORD/PORT`、`SERVER_PORT`、`SPRING_PROFILES_ACTIVE` 及本地默认值（默认端口 55432，见 research R-05）
- [x] T003 [P] 创建根 `LICENSE`（Apache-2.0 全文）
- [x] T004 [P] 创建 `docs/architecture.md`（架构与模块划分说明，中文）
- [x] T005 [P] 创建 `docs/development.md`（本地开发与排错指南，中文）

**Checkpoint**: 顶层结构与仓库卫生就位 ✔

---

## Phase 2: Foundational（后端基础设施，阻塞所有故事）

**Purpose**: 后端工程可构建的最小底座

**⚠️ CRITICAL**: 未完成前，US2/US3 均无法开始

- [x] T006 创建 `backend/pom.xml`：`spring-boot-starter-parent:4.1.1`、Java 21、`spring-ai-bom:2.0.1`（仅 BOM）、web/jpa/validation/actuator/flyway/postgresql 依赖、test 依赖（spring-boot-starter-test、spring-boot-testcontainers、testcontainers postgresql）
- [x] T007 生成 Maven Wrapper：`mvn -N wrapper:wrapper -Dmaven=3.9.16`，产出 `mvnw`/`mvnw.cmd`/`.mvn/wrapper/maven-wrapper.properties`（实测 `./mvnw` 可用）
- [x] T008 创建 `backend/src/main/java/com/personalos/PersonalOsApplication.java`（启动入口）
- [x] T009 [P] 创建 `backend/src/main/resources/application.yml`（datasource/jpa/flyway/actuator/app.version）
- [x] T010 [P] 创建 `backend/src/main/resources/db/migration/V1__enable_extensions.sql`（vector / hstore / uuid-ossp）
- [x] T011 [P] 创建八个模块包占位 + `package-info.java`：`common`、`ai`、`agent`、`chat`、`task`、`knowledge`、`calendar`、`memory`

**Checkpoint**: `./mvnw -B compile` 通过（9 个源文件，BUILD SUCCESS）✔

---

## Phase 3: User Story 1 - 一键拉起本地基础设施 (P1)

**Goal**: 一条命令拉起 PostgreSQL + pgvector，数据卷持久化

**Independent Test**: `docker compose up -d` 后容器 healthy，`psql` 可查询；后端跑过之后可见三个扩展

- [x] T012 [US1] 创建 `compose.yml`：服务 `postgres`（镜像 `pgvector/pgvector:pg17`）、环境变量引用 `.env`、端口 `${POSTGRES_PORT:-55432}:5432`、命名卷、`pg_isready` 健康检查
- [x] T013 [US1] 实际执行 `cp .env.example .env && docker compose up -d`：实测 4 秒转 `healthy`，`psql SELECT 1` 返回 1
- [x] T014 [US1] 验证扩展创建路径：**修正了初稿的逻辑错误** —— 扩展由后端 Flyway `V1` 创建，不是 compose 创建。实测迁移后 `\dx` 出现 `vector 0.8.7` / `hstore 1.8` / `uuid-ossp 1.1`

**Checkpoint**: 基础设施可用 ✔

---

## Phase 4: User Story 2 - 后端可构建、可启动、可自查 (P1) 🎯 MVP

**Goal**: 后端构建/测试通过，健康检查返回统一结构，异常统一处理

**Independent Test**: `./mvnw -B verify` 全绿；`curl /api/v1/health` 返回 `code=0`

- [x] T015 [P] [US2] 创建 `common/api/ApiResponse.java`（统一响应信封）
- [x] T016 [P] [US2] 创建 `common/api/ErrorCode.java`（错误码枚举，含 HTTP 状态映射）
- [x] T017 [P] [US2] 创建 `common/exception/BusinessException.java`
- [x] T018 [P] [US2] 创建 `common/exception/ResourceNotFoundException.java`
- [x] T019 [US2] 创建 `common/exception/GlobalExceptionHandler.java`（`@RestControllerAdvice`，覆盖业务异常/资源不存在/参数校验/兜底；兜底不回传堆栈）
- [x] T020 [P] [US2] 创建 `common/health/HealthStatusDto.java`
- [x] T021 [US2] 创建 `common/health/HealthService.java`（构造器注入，返回应用状态与版本）
- [x] T022 [US2] 创建 `common/health/HealthController.java`（`GET /api/v1/health`，使用统一响应）
- [x] T023 [P] [US2] 创建 `backend/src/test/java/com/personalos/common/api/ApiResponseTest.java`（单元测试，4 例）
- [x] T024 [US2] 创建 Web 层单元测试（**偏差 D-1**：见文末）
- [x] T025 [US2] 创建 `backend/src/test/java/com/personalos/PersonalOsApplicationTests.java`（`@SpringBootTest` + Testcontainers pg17 + `@ServiceConnection`，4 例）
- [x] T026 [US2] 实际执行 `./mvnw -B verify`：**Tests run: 10, Failures: 0, Errors: 0，BUILD SUCCESS**；并用真实 jar 验证 HTTP 端点

**Checkpoint**: US2 独立可用（MVP）✔

---

## Phase 5: User Story 3 - 前端可构建、可启动、可连后端 (P2)

**Goal**: 前端构建通过，首页真实调用后端健康检查

**Independent Test**: `npm run type-check` 与 `npm run build` 均成功

- [x] T027 [P] [US3] 创建 `frontend/package.json`（vue3 / vite / typescript / vue-router / pinia / element-plus / axios + vue-tsc）
- [x] T028 [P] [US3] 创建 `frontend/vite.config.ts`（`@` 别名 + `/api` 代理到 `http://localhost:8080`）
- [x] T029 [P] [US3] 创建 `frontend/tsconfig.json`、`tsconfig.node.json`、`env.d.ts`（**偏差 D-2**：未拆 `tsconfig.app.json`）
- [x] T030 [P] [US3] 创建 `frontend/index.html`、`src/main.ts`、`src/App.vue`、`src/styles/main.css`
- [x] T031 [P] [US3] 创建 `frontend/src/router/index.ts`（`/` 与 404 路由）
- [x] T032 [P] [US3] 创建 `frontend/src/stores/app.ts`（Pinia store，持有健康状态）
- [x] T033 [P] [US3] 创建 `frontend/src/types/api.ts`、`src/api/http.ts`（Axios 实例 + 统一错误处理）、`src/api/health.ts`
- [x] T034 [US3] 创建 `frontend/src/views/HomeView.vue`（调用健康检查并展示，失败给出可读提示）与 `src/views/NotFoundView.vue`
- [x] T035 [P] [US3] 创建 `frontend/.env.example` 与 `frontend/.gitignore`
- [x] T036 [US3] 实际执行 `npm install`（136 包 / 0 漏洞）→ `npm run type-check`（退出码 0）→ `npm run build`（1658 模块，dist 产出）

**Checkpoint**: 前端可构建、链路可验证 ✔

---

## Phase 6: User Story 4 - 新贡献者可按文档跑起来 (P3)

- [x] T037 [US4] 重写根 `README.md`：项目定位、技术栈与版本、目录结构、快速开始（三段式）、阶段路线图、开发约定、许可证
- [x] T038 [US4] 在 README 与 `docs/development.md` 写明前置条件（Docker 未启动时测试会失败等）与排错指引
- [x] T039 [P] [US4] 补齐 `docs/architecture.md` / `docs/development.md` 与本阶段实际实现的一致性

**Checkpoint**: 文档可独立复现 ✔

---

## Phase 7: Polish & 交付

- [x] T040 [P] 校验 `.gitignore` 每条规则生效：`git check-ignore -v`
- [x] T041 密钥扫描：`git grep -nE "AKID[A-Za-z0-9]{8,}|BEGIN.*PRIVATE KEY"` 零命中
- [x] T042 回填 `quickstart.md` 验证记录表（实际执行过的标 ✅ 并附证据）
- [x] T043 按 spec 流程提交，并更新 `.workbuddy/memory/`

---

## 实现偏差记录（与 plan / tasks 初稿不一致之处，均已回改上游文档）

| 编号 | 偏差 | 原因 | 已同步的文档 |
|---|---|---|---|
| D-1 | `HealthControllerTest` 用**普通单元测试 + Mockito**，而非计划中的 `@WebMvcTest + @MockitoBean` | Boot 4 把 `@WebMvcTest` 移出 `spring-boot-test-autoconfigure`，改由独立模块 `spring-boot-webmvc-test`（需 `spring-boot-starter-webmvc-test`）提供。为保持依赖最小（原则 I），改为纯单测；**HTTP 层契约由集成测试真实验证**（含端口、状态码、JSON 字段数） | research.md R-11 |
| D-2 | 前端 tsconfig 简化为 `tsconfig.json` + `tsconfig.node.json`，未拆 `tsconfig.app.json` | 计划中的 project references 需要 `composite: true` 与 `vue-tsc --build`，配置面更大且对骨架阶段无收益。改为单 tsconfig + `vue-tsc --noEmit` | 本文件 |
| D-3 | 新增 `common/exception/ApiErrorController.java`（plan 未列） | 需要接管绕过 Controller 的容器级错误，保证「所有错误响应格式统一」（FR-009 / SC-007）。实现 Boot 4 的 `org.springframework.boot.webmvc.error.ErrorController` 标记接口以令自带实现退让 | research.md R-11 |
| D-4 | `ErrorCode` 增加 `METHOD_NOT_ALLOWED(40005)` | 统一错误端点需要把 405 映射到明确错误码，避免误报为参数校验失败 | contracts/http-api.md |
| D-5 | pom 使用 `spring-boot-starter-flyway`，而非计划中的 `flyway-core` | **实测**：Boot 4 将 Flyway 自动配置拆到 `spring-boot-flyway`，只加 `flyway-core` 时**迁移静默不执行**（应用正常启动、无报错、但 `flyway_schema_history` 不生成） | research.md R-11 |
| D-6 | 前端 `typescript` 锁 `~5.9.3`，未采用 `latest` 的 7.0.2 | **实测**：`vue-tsc@3.3.13` 解析 `typescript/lib/tsc` 失败，TS 7 已不再导出该子路径 → 类型检查直接崩溃。这是全项目唯一「不取 latest」的依赖，依据是兼容性实测而非保守偏好 | research.md R-12 |
| D-7 | quickstart 场景 A 初稿要求 compose 启动后 `\dx` 即见扩展 | 逻辑错误：扩展由后端 Flyway `V1` 创建。已改为「跑过后端后再看扩展」 | quickstart.md |

## 推荐提交（Conventional Commits）

```
chore: 初始化 Personal AI OS 阶段 0 项目骨架

- 接入 Spec Kit：宪法 + specs/001-project-skeleton（spec/plan/research/data-model/contracts/tasks）
- backend：Spring Boot 4.1.1 + Spring AI BOM 2.0.1 + Java 21，按业务模块分包，
  统一响应/全局异常/健康检查，Flyway V1（vector/hstore/uuid-ossp）
- frontend：Vue 3 + TS + Vite + Router + Pinia + Element Plus，Axios 客户端 + 健康状态页
- infra：compose.yml（pgvector/pgvector:pg17，host 端口 55432）
- docs：README / architecture / development / LICENSE(Apache-2.0) / .env.example

已知的 Boot 4 模块化陷阱与实测结论记录于 specs/001-project-skeleton/research.md R-11 / R-12
```

---

## Notes

- [P] 任务 = 不同文件、无依赖，可并行
- 每个 Checkpoint 必须实际验证后再进入下一阶段
- 未实际执行的验证一律标记 ⏳，不得谎报通过
