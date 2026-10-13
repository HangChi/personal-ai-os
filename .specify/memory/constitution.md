<!--
Sync Impact Report
- Version change: 0.0.0 (template scaffold) → 1.0.0
- Modified principles: 全量重写（模板占位符 → 项目专属原则）
- Added sections: 技术栈与架构约束 / 安全与合规边界 / 开发工作流与质量门禁 / Governance
- Follow-up TODOs（未决项，故意不锁）：
  1. 默认大模型提供商未定 —— 阶段 1 拍板（OpenAI 兼容协议 / DeepSeek / 本地 Ollama）。
  2. 前端 Element Plus 引入方式未锁 —— 阶段 0 先用「全量引入」保证可构建，阶段 1 评估按需引入。
  3. 身份认证方案未定 —— 第一版仅本地单用户；对公网开放前必须先设计认证。
  4. 后端是否需要拆分为 Maven 多模块 —— 阶段 0 采用单模块 + 按包划分，待模块数量与耦合度上升后再评估。
-->

# Personal AI OS Constitution

Personal AI OS 是一个开源、自托管、可扩展的个人智能操作系统。本宪法是全仓库最高约束，优先级高于任何单点技术偏好与历史习惯。

## Core Principles

### I. 简单优先，拒绝过度设计（NON-NEGOTIABLE）

- 采用 Monorepo + 模块化单体。MUST NOT 在第一版引入微服务、消息队列、分布式事务或复杂 DDD 聚合。
- 任何新增抽象层 MUST 能回答「不这样做的具体代价是什么」。
- 新增运行时依赖 MUST 在 `plan` 阶段的 `research.md` 中记录 Decision 与 Alternatives considered。

### II. 边界即安全（NON-NEGOTIABLE）

- 模型 MUST NOT 直接访问数据库、文件系统或执行任意代码；所有副作用 MUST 经由显式 Java 接口暴露的 Agent 工具。
- 可能破坏数据的 Agent 操作 MUST 具备显式人工确认环节。
- 仓库中 MUST NOT 出现任何密钥（API Key / 数据库密码 / Token）。一律经环境变量注入，只提交 `.env.example` 模板。
- 第一版仅本地单用户。若服务需对公网开放，MUST 先完成身份认证设计，否则不得开放。

### III. 分层与写路径收敛

- 后端按业务模块分包：`com.personalos.{common, ai, agent, chat, task, knowledge, calendar, memory}`。
- 每个模块内部按 `controller / service / repository / entity / dto` 分层。
- 所有写操作 MUST 经过 Service 层；Controller MUST NOT 直接调用 Repository。
- MUST NOT 向前端暴露 JPA Entity；对外一律 DTO，并做参数校验。
- 统一使用构造器注入，MUST NOT 使用字段注入。

### IV. 数据变更可追溯

- 所有数据库结构变更 MUST 通过 Flyway 迁移脚本完成；MUST NOT 依赖 Hibernate 自动建表（`ddl-auto` 只能是 `validate` 或 `none`）。
- 每个迁移脚本 SHOULD 只做一件事，并 MUST 能在干净库上完整重放。

### V. 可验证的交付

- 每个阶段的成果 MUST 能通过真实命令验证（构建 / 测试 / 启动）。MUST NOT 用「看起来对」代替「实际跑通过」。
- 核心业务逻辑 MUST 有单元测试；上下文装配与数据库交互 MUST 有集成测试。
- 交付说明 MUST 如实区分「已实际执行并验证」与「未执行」。

## 技术栈与架构约束

- 后端：Java 21 / Spring Boot 4.1.x / Spring AI 2.0.x / Spring Web MVC / Spring Data JPA / Flyway / Maven。
- 数据库：PostgreSQL + pgvector。
- 前端：Vue 3 + TypeScript + Vite + Vue Router + Pinia + Element Plus + Axios。
- 依赖版本 MUST 取自 Maven Central / npm registry 上真实存在的稳定版，且 MUST 彼此兼容（Spring AI 2.0 要求 Spring Boot 4.0.x / 4.1.x）。
- MUST NOT 混用 Spring AI 1.x 与 2.x 的 API 或 artifact 命名。
- 代码注释、README 与架构文档使用中文；类名、方法名、API 路径与 Git 分支名使用英文。

## 安全与合规边界

- 敏感配置只经环境变量注入，`.env` 不入库。
- 所有对外 HTTP 接口使用统一错误响应格式；MUST NOT 泄漏堆栈信息或内部路径。
- 许可证 MUST 明确且可审计（本项目采用 Apache-2.0）。

## 开发工作流与质量门禁

- 采用 Spec Kit 流程：`constitution → specify → plan → tasks → implement`；每阶段一次提交。
- 阶段严格串行，MUST NOT 提前实现未进入当前阶段的模块。
- 质量门禁（提交前 MUST 全绿）：后端 `./mvnw -B verify`；前端类型检查 + 生产构建通过。
- 提交信息使用 Conventional Commits（`feat: / fix: / docs: / chore: / test: / refactor:`）。

## Governance

- 本宪法优先级高于其他实践与文档；发生冲突时以本宪法为准。
- 修订 MUST 记录 Sync Impact Report 并递增版本号（语义化版本）。
- 所有评审 MUST 逐条核对本宪法；违例 MUST 在 `plan.md` 的 Complexity Tracking 中论证，否则不得合并。

**Version**: 1.0.0 | **Ratified**: 2026-10-14 | **Last Amended**: 2026-10-14
