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

## R-009 限流路径差分与故障策略

Decision：先移植可独立执行的本地桶、共享后端选择和 HTTP 保护边界，不把本地桶装进应用冒充共享限流。共享 Redis 失败直接拒绝，PostgreSQL 失败上抛，保留原 Java 行为；前者没有数据库/本地回退。真实共享适配器继续等待依赖与隔离设施验收。

模型路径不能简化为对解码后 URL.Path 的前缀判断。使用固定 Git blob 和基线 BOM 对应的本机缓存 Spring/Tomcat，离线编译执行原始 ModelRequestPaths，记录包含编码、上下文、空段和末尾斜杠的 216 项结果。Go 用单次 PathUnescape 和固定逐段模式匹配对照；不引入通用 Ant 匹配器。快照生成器不启动 Spring、不下载依赖、不读 .env，也不取代 Maven 质量门禁或服务器端安全测试。

Alternatives considered：只复制注释中的聊天/知识路径（遗漏上传/转写/探测/PUT）；以 Go 行为生成 expected（失去独立对照）；对 URL.Path 再解码（双编码路径语义改变）；共享设施错误回退本地（多实例可超发）。Servlet 容器错误映射和实际安全链仍待集成差分。

## R-010 显式 pgx 连接器与一次性数据库验收

Decision：2026-11-03 已通过 Go 官方模块代理查询并下载 pgx/v5 v5.12.0（模块发布记录 2026-10-31，来源提交 b2ef08bbb951384e3936e45de60de60609f2d2ba），使用 stdlib.GetConnector 接入既有 database/sql Pool，不再增加另一套连接池。go.mod/go.sum 固定版本，MIT/BSD 许可证正文见 backend-go/THIRD_PARTY_NOTICES.md。模块归档 SHA 为 h1:hWwax8YiGT0YiN50U1oIDn5kgR7KC2tG3rmavwpPkxY=。

配置只接受显式字段，拒绝任何 PG* 环境，避免 pgx 解析器隐式读取用户的 service/pass/cert 文件；不临时改写全局环境。固定非敏感种子后设置完整参数，禁止 fallback。默认 verify-full、最低 TLS 1.2，可显式根证书；仅回环测试可 disable。会话和 timestamptz 扫描均固定 UTC；对外格式化与连接错误脱敏。这只是受限子集，不宣称已覆盖 Java 所有 JDBC/TLS 配置。

验收采用现有 Compose 同系列的 pgvector/pgvector pg17 镜像，固定已验证摘要 sha256:ac08538c6f8b9904c33c8224c5e5706dbe760aca29db1d096972b4052c22a75d。脚本创建独立容器、tmpfs 数据目录、随机密码与回环端口，并在 finally 中仅清理自身容器。不读取 .env、不使用现有数据库、不执行原 SQL 或伪造 Flyway 历史。应用尚不装配数据库，readyz 继续 503。

Alternatives considered：直接 ParseConfig 用户 DSN（隐式来源可能越过迁移隔离）；默认非回环明文连接或 require/prefer（未验证的降级）；同时引入 pgxpool（重复现有连接池边界）。真实 TLS 握手、最小权限和全部迁移重放仍单列验收。

## R-011 原 SQL 目录与重放先于生产迁移器

Decision：Go 只提供显式 fs.FS 的单层整数版本目录，失败关闭地校验 V1..Vn 顺序/唯一/连续、原字节 SHA-256 和编码；当前不执行生产迁移，不复制或改写 Java SQL，不把 SHA-256 当 Flyway CRC32。全部原文件逐一对照固定基线 inventory。

在专用 tmpfs PostgreSQL/pgvector 中，用独立 schema、每迁移一个事务和事务内 search_path 重放完整文件，不按分号拆开 PL/pgSQL。空 schema 和插入虚构历史记忆/模型配置的升级两种场景均跑 V1–V42，测试后清理各自 schema。不生成 Flyway 历史；历史/锁/执行权与备份恢复继续独立验收。

Alternatives considered：复制 DDL（两份可能漂移）；只检查文件存在（遗漏执行/排序/编码/函数体问题）；创建看似成功的 Flyway 历史（无法证明真实 checksum 和兼容）；以合成数据冒充用户备份恢复（证据不成立）。

## R-012 BCrypt 兼容与锁定状态

Decision：固定实际下载的 golang.org/x/crypto v0.58.0，使用其 bcrypt 实现（默认 2a/cost10）；模块依赖同时要求 x/text v0.43.0、x/sync v0.24.0，许可证取自实际模块。直接运行基线 BOM 的 Spring Security 7.1.1 BCryptPasswordEncoder/BCrypt，生成 74 项仅含虚构密码与固定盐的快照，包含 Unicode、组合字符、NUL、72 字节及超长。生成配置哈希时拒绝 >72 字节，校验时保留 Java 的长输入行为，不自行截断/正规化；空原始密码按 Spring 包装器返回 false。

Service 不保留 Options 或明文，仅保留私有哈希；fmt/JSON 不公开凭据。配置拒绝非 UTF-8 的 Go 字符串；有效 Unicode 的 strip/isBlank 对齐 Java。Go duration 的范围小于 Java Duration，超范围配置应在未来绑定层明确拒绝。Verify 保留指针输入来区分 null 和空串。失败锁定使用互斥锁覆盖密码检查和状态变更，保持原串行规则并收紧原 AtomicInteger/volatile 组合下的并发窗口。锁定仍为每进程状态，与 Java 一致，不冒充多实例共享锁定。

Alternatives considered：自写 BCrypt（增加密码学风险）；用 Go 自己生成期望值（无法独立验证 Java 兼容）；一律截断密码到 72 字节（配置超长会静默改变凭据）；保留完整配置对象（长期保留明文）。当前没有导入 Java 会话、启用认证或解除诊断应用的安全启动拒绝。

## R-013 显式会话存储与 HTTP 安全顺序

Decision：以 SessionStore 明确 Load（读且续期）、Rotate（新 ID 不冲突才删除旧 ID）、EnsureCSRF（只在活跃 ID 上首次写入）、Delete 四个原子操作。先实现真实单进程内存存储，默认 30 分钟闲置超时；不用将整个可变会话写回的 Save，防止注销后并发请求复活旧会话。CSRF 创建匿名会话，登录成功丢弃原会话全部属性并换 ID；令牌采用随机 UUIDv4，会话 ID 为 256 位随机 hex，均为前端不解释的 opaque 值。Java 原序列化会话不导入 Go，切换后须重新登录，共享 Redis 使用独立新命名空间。

HTTP 按保守路径防火墙、会话读取、CSRF、认证、控制器顺序组合。保留所有公共路径、所有其他请求需认证、MCP 仅免 CSRF、401/403 JSON 和 me 的匿名 200。令牌支持 X-CSRF-TOKEN、_csrf query/urlencoded/multipart；表单读取最多 10 MiB，multipart 临时文件请求结束清理。Cookie 为 HttpOnly/SameSite Lax/显式 Secure，登录才签发身份会话。JSON 登录读取最多 10 MiB，拒绝重复 Cookie；这些 Go 明确边界及完整媒体类型/字段强制转换/验证错误排序/StrictHttpFirewall 响应仍需 T106 真服务差分。

Alternatives considered：每请求全量 Save（会把过期或注销会话恢复）；登录复用匿名 ID（会话固定攻击）；CSRF 只保护已登录请求（遗漏登录 CSRF）；/mcp 绕过认证（原 Java 仅豁免 CSRF）；把内存会话装作共享 Redis（破坏跨实例注销）。未解除应用的生产/认证/CSRF 启动拒绝。

## R-014 专用 Redis 连接与 Go 共享会话

Decision：2026-11-03 从官方 Go 模块代理确认并下载 github.com/redis/go-redis/v9 v9.23.0（发布记录 2026-10-05、来源提交 6f2b2639e563b11709e06f42bc0addbfb5a1abb6）。先固定版本并实现显式 redis/rediss URI、TLS、2 秒操作边界、有限连接池和错误脱敏；关闭命令自动重试，防止不确定写入被重复执行。只支持专用 standalone，不读取环境/默认服务或全局客户端。许可证随实际模块保存。

共享会话使用显式 namespace 下附加 :go:v1: 的独立键，JSON 值及 Redis TTL；不读取/改写 Spring Session 的序列化内容，切换需要重新登录。Lua 原子实现读取续期、ID 轮换、首次 CSRF 与删除；存储失败拒绝请求，禁止回退本地。将使用固定已缓存 redis:8.2.1-alpine 摘要的独立 tmpfs/随机回环端口容器，只传入新生成测试凭据，跨两客户端和真实 HTTP 实例验证，完成后补充实际结果。

Alternatives considered：手写 RESP/TLS/池（增加协议和生命周期风险）；依赖 URI 默认值或连接现有 Redis（会越过数据隔离）；直接兼容 Java 二进制序列化（不必要且存在对象反序列化风险）；自动重试 Lua 写入（可能把已成功但丢响应的轮换再次执行）。实际 TLS 握手、ACL 最小权限、真实断网和应用装配另列验收，不以配置单测替代。

## R-015 Redis 原 Lua 与 150ms 模型准入

Decision：RedisBucket 直接保留固定 Java RedisRateBucket 的 TAKE Lua（单测逐词比对），key 仍为 personalos:rate:SHA256(namespace UTF-8)。只接受 SharedLimiter 使用的 model-rate 资源，避免给不同资源名错误共用一个 Java 单桶。周期按 Java toMillis 向下取整，最少 1ms；脚本使用 Redis TIME，拒绝也写回分数状态/at 并更新 period+1000ms TTL，已有配置不一致报错而非重新注满。此处与 PostgreSQL 拒绝不写状态的行为刻意不同。

使用显式专用 Redis Client，但每次准入再限定 150ms（原 Jedis 专用超时）；父 deadline 更早则优先。无自动命令重试。底层异常经脱敏返回，SharedLimiter 计错并拒绝，不回退数据库或本地。调用方负责客户端生命周期；构造/普通单测不访问 Redis。

Alternatives considered：统一成 Go 本地桶算法（会改变服务端时钟、拒绝写回/配置变更规则）；namespace 拼接未哈希键（改变已有键契约）；沿用会话默认 2 秒（改变模型准入延迟）；失败回退 PostgreSQL（可能超发预算）。真实 TLS、网络分区和不确定响应验收继续保留。

## R-016 真实 Redis TLS 与证书失败边界

Decision：扩展同一隔离 Redis 脚本的 --tls 模式，每包新建临时 CA 和独立服务端证书（SAN localhost/127.0.0.1），仅挂载服务端所需证书/密钥，CA 私钥留在宿主临时目录。显式 PEM 通过测试子进程环境传入 Client，不改系统信任。Redis 只开 TLS 端口，业务测试全部用 rediss。退出 finally 同时清理自身容器和证书目录。

客户端真实握手验证受信根/正确主机名通过，未知根和错误主机名保留可识别的 x509 原因并拒绝，明文访问该端口不成功；同一套会话/两个 HTTP 实例/独立进程注销和原 Lua 限流竞争在 TLS 下均通过。原 R-014/R-015 的实际 Redis TLS 待验项由此补充，生产 ACL/真实网络分区/不确定写入与应用装配仍未完成。

Alternatives considered：只检查 tls.Config 字段（不证明握手）；修改系统 CA 或 InsecureSkipVerify（绕过实际验证）；将 CA 私钥装入 Redis 容器（不必要）；拿现有生产 Redis 做测试（破坏隔离）。首次证书生成复用 CA 主题配置造成测试就绪失败，分离 server.cnf 并补齐证书标识后修复，失败资源均清理。

## R-017 PostgreSQL TLS 与受限事务账号

Decision：复用显式临时 TLS 证书夹具，PostgreSQL 只挂载本次服务端证书源，容器内复制并设置私钥 0600/postgres 属主。测试只修改自己创建的 pg_hba.conf，在原规则前加 hostnossl reject；TCP 就绪后重载，不使用初始化阶段的 Unix-only 临时服务。五个数据库包以 verify-full/显式临时根复跑，检查真实 pg_stat_ssl；未知 CA/错误主机名保留 x509 原因，明文被拒。

仅在本次集群创建 NOSUPERUSER/NOCREATEDB/NOCREATEROLE/NOREPLICATION/NOBYPASSRLS 账号，独立 schema 内授权一张表的 DML/identity 序列。真实完成 Unicode 事务读写；创建角色/数据库/表、DROP、读未授权表和自提 SUPERUSER 均 42501。该探针验证连接器/事务权限边界，不是全 42 schema 的部署权限方案。

Alternatives considered：只检查 TLS 配置字段（无法证明握手）；sslmode=require 或忽略证书（失去身份验证）；更改现有集群 pg_hba 或系统信任（越过隔离）；超级用户通过便宣称最小权限（证据不成立）。CA 私钥不进容器，所有资源 finally 清理。

## R-018 Redis ACL 与丢失响应的不确定结果

Decision：仅在新建临时容器中创建 go_session/go_rate 两个账号，reset 后只开 AUTH/HELLO/PING/EVAL、各自 Lua 所需命令和精确键空间。会话仅 fixture:acl-session:go:v1:session:*，限流仅指定 namespace SHA-256 的单键；不授通配命令/管理权限。真实验证所有会话原子操作/模型预算，CONFIG/ACL/FLUSHALL/KEYS、Java 会话键及脚本越界均 NOPERM。误配限流账号或会话键空间时 HTTP 500/模型429，计错误且不访问 PostgreSQL。该 ACL 清单仅是已实现会话/模型桶的权限，未来缓存需另定权限。

透明回环 TCP 代理先完成真实连接/PING，再丢弃下一次服务端返回的数据并断开连接；不读取/输出协议或凭据，TLS 原样转发密文。实际 EVAL INCR 已执行但调用返回错误，独立管理员连接读取计数恰好1，代理只接受一次连接，证明未自动重试。之后显式新 PING 可重连，原计数保持1。普通/TLS 两种模式均通过。不能据此将不确定写入当作未发生，业务恢复仍需协议。

Alternatives considered：只检查 MaxRetries 字段（不证明真实故障）；停掉容器后测试（无法模拟写入已执行/响应丢失）；给 ACL +@all（掩盖越权）；ACL 错误降级本地/其他共享后端（可能身份或预算不一致）。完整半开/长期网络分区和应用生命周期尚未验收。
