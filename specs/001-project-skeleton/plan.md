# Implementation Plan: 阶段 0 — 项目骨架初始化

**Branch**: `001-project-skeleton` | **Date**: 2026-10-14 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-project-skeleton/spec.md`

## Summary

搭建 Personal AI OS 的 Monorepo 骨架：`backend`（Spring Boot 4.1.1 + Spring AI BOM 2.0.1 + Java 21，按业务模块分包，统一响应与全局异常，健康检查，Flyway 迁移）、`frontend`（Vue 3 + TS + Vite + Router + Pinia + Element Plus，含 Axios 客户端与健康状态页）、`compose.yml`（PostgreSQL 17 + pgvector）、`docs/`、`README.md`、`LICENSE`。本阶段**不引入任何 AI Starter**，只导入 Spring AI BOM 锁定版本，确保骨架在无任何模型密钥的情况下可启动、可测试。

## Technical Context

**Language/Version**: Java 21（本机 21.0.12）；TypeScript 5.x；Node.js 20.19+（本机 22.22.2）

**Primary Dependencies**:
- 后端：Spring Boot 4.1.1（Spring Framework 7.0.x）、Spring AI BOM 2.0.1、Spring Web MVC、Spring Data JPA、Validation、Flyway（core + database-postgresql）、PostgreSQL Driver、Actuator
- 测试：JUnit 5、Mockito、AssertJ、Spring Boot Test、Testcontainers（PostgreSQL）
- 前端：Vue 3.5.x、Vite 7.x、Vue Router 4.x、Pinia 3.x、Element Plus 2.x、Axios 1.x
- 构建：Maven 3.9.16（本机，另有 Homebrew 3.10.0）+ Maven Wrapper

**Storage**: PostgreSQL 17 + pgvector 0.8.x（镜像 `pgvector/pgvector:pg17`）

**Testing**: 后端 JUnit 5 + Mockito（单元）+ Spring Boot Test + Testcontainers（集成）；前端 TypeScript 类型检查 + 生产构建

**Target Platform**: 本地单用户（macOS / Linux 开发机）；交付形态为可自托管的单体服务 + 静态前端

**Project Type**: Web application（Monorepo：backend + frontend）

**Performance Goals**: 阶段 0 无性能指标；仅要求应用冷启动 < 20s、健康检查响应 < 200ms（本地）

**Constraints**: 无密钥即可启动；`ddl-auto` 仅允许 `validate`；所有结构变更走 Flyway；不得提交任何密钥

**Scale/Scope**: 单用户、单机；阶段 0 产出约 40 个文件；不含业务实体与接口

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 判定 | 依据 |
|---|---|---|
| I. 简单优先，拒绝过度设计 | ✅ 通过 | 后端采用**单 Maven 模块 + 按包分模块**，不引入多模块聚合与微服务；前端单应用，不引入微前端 |
| II. 边界即安全 | ✅ 通过 | 阶段 0 不含任何 AI 工具，故无模型越权面；仓库无密钥，仅提交 `.env.example`；本地单用户，不对公网开放 |
| III. 分层与写路径收敛 | ✅ 通过 | 八个业务模块包全部建立；`common` 层承载统一响应/异常；阶段 0 无写接口，分层目录先就位 |
| IV. 数据变更可追溯 | ✅ 通过 | `V1__enable_extensions.sql` 唯一迁移；`spring.jpa.hibernate.ddl-auto=validate` |
| V. 可验证的交付 | ✅ 通过 | 交付时实际执行 `mvn test` 与前端构建，并在交付说明中区分「已执行/未执行」 |

**结论**：无违例，无需 Complexity Tracking 论证。

## Project Structure

### Documentation (this feature)

```text
specs/001-project-skeleton/
├── spec.md              # 本特性规格（已完成）
├── plan.md              # 本文件
├── research.md          # 版本与方案决策记录
├── data-model.md        # 阶段 0 数据基线（无业务实体）
├── quickstart.md        # 可执行验证场景
├── contracts/           # 对外接口契约（HTTP）
│   └── http-api.md
├── checklists/
│   └── requirements.md  # 需求质量自检
└── tasks.md             # 任务清单
```

### Source Code (repository root)

```text
personal-ai-os/
├── backend/
│   ├── pom.xml
│   ├── mvnw / mvnw.cmd
│   ├── .mvn/wrapper/maven-wrapper.properties
│   └── src/
│       ├── main/
│       │   ├── java/com/personalos/
│       │   │   ├── PersonalOsApplication.java
│       │   │   ├── common/            # 统一响应、错误码、全局异常、健康检查
│       │   │   │   ├── api/           # ApiResponse / ErrorCode
│       │   │   │   ├── exception/     # BusinessException / GlobalExceptionHandler
│       │   │   │   ├── health/        # HealthController / HealthService / HealthStatusDto
│       │   │   │   └── config/        # 公共配置
│       │   │   ├── ai/                # 阶段 1 起填充
│       │   │   ├── agent/
│       │   │   ├── chat/
│       │   │   ├── task/
│       │   │   ├── knowledge/
│       │   │   ├── calendar/
│       │   │   └── memory/
│       │   └── resources/
│       │       ├── application.yml
│       │       └── db/migration/V1__enable_extensions.sql
│       └── test/java/com/personalos/
│           ├── PersonalOsApplicationTests.java   # Testcontainers 集成测试
│           ├── common/api/ApiResponseTest.java   # 单元测试
│           └── common/health/HealthControllerTest.java  # Web 层单元测试
├── frontend/
│   ├── package.json / tsconfig*.json / vite.config.ts / index.html
│   └── src/
│       ├── main.ts / App.vue
│       ├── router/index.ts
│       ├── stores/app.ts
│       ├── api/http.ts / api/health.ts
│       ├── types/api.ts
│       └── views/HomeView.vue / views/NotFoundView.vue
├── docs/
│   ├── architecture.md
│   └── development.md
├── compose.yml
├── .env.example
├── .gitignore
├── README.md
└── LICENSE
```

**Structure Decision**：采用 Web 应用结构（`backend/` + `frontend/`），后端为**单个 Maven 模块**，模块边界落在 Java 包（`com.personalos.<module>`）上，而非 Maven module 上。理由：当前只有一个可部署单元，多模块聚合只会带来 POM 维护成本而无可观测收益（原则 I）；待模块数量增长或需独立发布时，可按包无损迁移为多模块。

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

本特性无宪法违例，无需论证。
