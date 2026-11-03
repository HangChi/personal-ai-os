# 技术决策记录

## R-001 同仓库独立 Go 模块

Decision：分支名按用户要求使用 `go`，新增 `backend-go/`，保留 `backend/`、前端和规格。基线固定为 cb5d694。

原因：需要对照全部业务、协议和 SQL；当前已有成熟前端与运维资产。另开仓库会增加规格、前端与回归资产同步成本。

Alternatives considered：直接删 Java 后重写（失去可运行行为对照）；新仓库（当前产品边界未变化）；一次性覆盖 backend（难以审查遗漏）。

## R-002 当前基础代码只使用标准库

Decision：首批使用 `net/http`、`encoding/json`、`context`、`crypto/rand`、`log/slog`、`os/signal`、`testing`。本机实际工具链为 `go1.27.1 darwin/arm64`，go.mod 声明 `go 1.27.0`；这只是已验证的本地基线，不宣称是公共最新版本。

原因：先移植可明确验证的契约与角色，避免依赖选型反过来改变业务模型。首批可在禁止下载依赖的环境构建。

Alternatives considered：Gin/Echo/Chi 等 HTTP 框架。当前两个诊断端点不足以证明引入框架的必要性；正式业务路由有实际需求时再决策。

## R-003 不直接选择“Spring AI 的 Go 替代框架”

Decision：模型协议适配、工具执行、上下文保护、持久化运行、计划与确认分别保留业务边界。选定 SDK 前用现有模型配置和故障 fixtures 验证 OpenAI 兼容/Ollama 等实际协议需求。

原因：当前功能超出普通 Chat Completion，包括运行恢复、预算、窗口探测、语音转写、提示装配、幂等和人工确认；选一个框架不等于迁移这些能力。

Alternatives considered：用单个 Agent/RAG 框架覆盖现有实现。只有证明协议与行为覆盖后才能采纳，不预先锁定。

## R-004 SQL 与存量数据优先于 ORM 选择

Decision：保留现有 PostgreSQL/pgvector schema 和 SQL。P1 调研并固定 PostgreSQL 驱动、连接池/事务方案；不先引入 ORM 或自动建表。

原因：已有大量直接 JDBC SQL、租约、SKIP LOCKED、Outbox 和向量操作，必须先保持数据库语义。

Alternatives considered：整体迁移到 GORM；自动建表后导入数据。均可能改变默认值、索引、锁和版本历史，不作为默认方案。

## R-005 新旧进程默认隔离

Decision：迁移骨架默认绑定 `127.0.0.1:8081`，只提供 `/livez` 与恒定未就绪的 `/readyz`，不实现生产入口。环境变量 `SERVER_PORT` 可以改变端口，运行角色用 `APP_RUNTIME_ROLE`。

原因：不抢占 Java 的 8080、不读取其 .env/密钥、不连接其数据库、不启动重复提醒/索引。骨架没有真实依赖检查，不应返回现有 `/api/v1/health` 的健康结果。

Alternatives considered：默认接入现有数据库并注册全部占位路由（容易误写或误认为完成）；固定 `/health` 为 UP（掩盖未实现依赖）。

## R-006 待实施前验证的依赖

以下是职责清单，不是已经选定或安装的第三方包。每项在进入对应阶段时用官方资料、当前可用版本和兼容试验形成补充决策。

职责 | 阶段 | 必须验证
--- | --- | ---
PostgreSQL/pgvector 与迁移执行器 | P1 | 事务/锁/类型、Flyway 历史、42 个 SQL 重放和备份恢复
密码与会话、Redis | P1 | BCrypt、Cookie/CSRF、专用 Redis TLS/namespace、切换重新登录
模型/语音/嵌入协议 | P3 | 当前 provider 参数、超时/取消、工具 JSON schema、窗口与 usage
token 计数 | P4 | 当前模型估算回退、字符/UTF-16/token 边界和预算保护
S3 | P4 | S3 兼容 endpoint、namespace、附件恢复与孤儿清理
Web Push | P5 | VAPID、加密、订阅去重与过期、可靠投递
PDF/DOCX/图片解析 | P6 | 文本层/结构/空文档/大小限制/视觉回退、许可证
RabbitMQ/OpenSearch | P6 | publisher confirm、Outbox、租约、防乱序、轮询/检索降级
MCP SDK/协议实现 | P7 | stdio/HTTP、动态工具、命名冲突、确认策略、对外只读隔离
Prometheus | P8 | 业务指标/标签、采集配置、角色拓扑与访问控制

首批没有新增第三方运行时依赖，也没有下载 SDK、修改本地基础设施或探测真实模型。

## R-007 P1 配置来源与生产守卫

Decision：按固定 Java 的 DotEnvEnvironmentPostProcessor/ProductionSecurityValidator 移植解析、查找、系统环境优先级与安全校验。保留重复键顺序、字面量 `$`/`#`、Java strip/isBlank 和 UTF-8 字节长度语义。Go 迁移进程只有显式 `GO_DOTENV_ENABLED=true` 才允许查找 .env；现有 `APP_DOTENV_ENABLED=false` 仍可以关闭。测试只使用临时目录里的虚构数据。

原因：配置兼容不能让未完成迁移的进程自动读取当前 Java 凭据。配置校验不是认证实现；在认证与生产装配完成前，Go 启动遇到 prod/production-mode 或显式启用认证/CSRF 时应失败，不能给出已经受保护的假象。

Alternatives considered：默认加载仓库 .env（打破当前隔离约定）；直接用 Go TrimSpace（与 Java 对 NBSP、NEL、控制字符的处理不同）；只做密码长度检查后允许生产启动（认证/CSRF 尚未实现）。完整 YAML/profile/relaxed binding 仍属于 T104 的后续范围。

## R-008 无外部驱动的数据库事务基础

Decision：先用标准库 database/sql 与 driver.Connector 实现连接池生命周期、获取连接超时和显式事务。调用方在事务回调中拿到同一 *sql.Tx，通过显式传参加入事务；再次从 Pool 开始事务代表独立事务。不做全局/context 隐式事务、自动重试、自动建表或 Flyway 历史写入。

原因：2026-11-03 查询 pgx 可用版本时，沙箱 DNS 不可用，联网审批返回 503。未确认和下载的版本不能写入 go.mod，也不能声称已接入 PostgreSQL。标准库事务边界可先用受控测试驱动验证；真实 pgx Connector 与 PostgreSQL/pgvector 集成保持待完成。

Alternatives considered：猜测并锁定 pgx 版本（无法验证或构建）；自写 PostgreSQL 协议（增加认证/TLS/类型/取消风险）；仅增加空数据库接口（没有可执行的事务与资源管理行为）。获取超时仅约束连接池等待，不把已取消的获取 context 用作事务 context；提交失败不自动重试，以防重复副作用。
