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

## R-019 真实依赖健康报告

Decision：HealthService 使用显式 Probe/本地模型配置回调和元信息；实际 PostgreSQL Probe 复用 Pool，仅 SELECT 1。数据库失败/取消报告 DOWN，模型未配置仅显示 NOT_CONFIGURED，HTTP 报告仍200，与原 Java 一致。调用方显式设置正 ProbeTimeout，较早父期限优先；不创建无法取消的旁路 goroutine。每次返回独立 components，UTC时间。日志只记通用失败和请求ID，不含驱动错误；这是对原日志的有意安全收紧。

真实故障只在本次临时集群：维护连接使用该集群 postgres 库，禁止 personalos_go_test 新连接并终止其探针连接；DOWN 后恢复UP，单槽池被占用时按50ms期限返回DOWN、释放后UP。首次从当前库禁用自身连接被 PostgreSQL 22023 拒绝，改到维护库修正，不放宽故障断言。

Alternatives considered：只用进程存活冒充数据库健康；调用模型接口当健康检查（昂贵/副作用）；数据库失败把报告改为503（改变前端契约）；改掉 readyz=false（会虚报全量迁移就绪）。Handler 暂只可显式组合，诊断应用仍未挂载任何业务端点。

## R-020 有界轮末执行与显式诊断传播

Decision：用Go受锁保护的有界队列和按需goroutine移植ContextTaskConfig：1核心/2最大/32排队/60s全体空闲退出，先排队后扩容，满载新任务丢弃。返回bool供观测，不等待/自动重试。Shutdown排空、Stop取消与清队列、Wait独立期限；任务不遵守context时不得假报退出。处理空闲退出与提交竞争，最后执行单元退出不能遗留已接受任务。

没有线程本地MDC或全局调度钩子；CopyRequestID只把提交context中的安全诊断ID复制到执行器生命周期context，不保留HTTP身份、Cookie、其他值或断连取消。已有Java主源码仅写入requestId，其他执行身份仍需业务显式传入。每次任务上下文独立，结束即释放。错误/panic只记通用文案和ID，继续队列，不重试；这是Go防止单goroutine panic终止进程的显式约定。

Alternatives considered：无限go语句（模型慢时无限堆积）；满队列立即拒绝但不扩至2（改变Java策略）；context.WithoutCancel直接保留整个请求值（长期保留身份/敏感状态）；共享可变全局请求ID（串请求）；强杀goroutine（Go不支持安全强杀）。该执行器仅供原本尽力而为轮末工作，不可替代持久化Run/数据库任务恢复协议。

## R-021 Flyway 执行权与只读历史校验

Decision：保留Java/独立迁移步骤为唯一DDL执行者，Go只校验，不migrate/baseline/repair/clean，也不伪造历史或争抢迁移锁。使用基线Boot4.1.1 BOM实际Flyway12.4.0/JDBC42.7.13和已有离线依赖，独立Java程序只连接脚本新建临时集群，完整执行原42迁移、Flyway validate和二次零执行。没有启动Spring应用或读取.env。

离线直接调用该Flyway的ChecksumCalculator生成42原脚本与12合成边界，GoCRC32独立实现并逐一核对。忽略CR/LF与开头单个BOM，其他空白/Unicode/注释保留，结果为有符号32位；原字节SHA-256仍单独用于来源完整性。Go在只读repeatable-read事务读显式schema下标准flyway_schema_history，精确校验数量、成功SQL类型、递增rank/整数版本、description/script与checksum，返回值仅完整成功。限制最多目录数+1行，错误不回显历史文本或驱动消息。

BASELINE/SCHEMA/repeatable/自定义类型、乱序或超出目录的历史当前失败关闭，不将未验证标记直接接受。原Java baseline-on-migrate场景仍待独立兼容；历史一致也不能证明实际DDL未遭人为修改，需要完整schema差分与恢复验收。上线流程必须先独占完成迁移，Go校验不提供并发DDL授权。

Alternatives considered：用SHA-256代替Flyway checksum；读取版本号存在便通过；自动repair或写入看似成功历史；Go启动时与Java双执行器竞争；空库重放成功便声称存量备份兼容。初版集成引用不存在的fixture函数导致编译失败，已补充显式隔离连接夹具后重跑成功。

## R-022 Flyway 锁竞争与七类 schema 定义差分

Decision：Java夹具使用两个独立Flyway实例/线程，由同一latch同时释放，真实竞争新集群的迁移执行；两者执行数合计必须恰好42，之后Flyway validate和再次零执行。Go不执行DDL或实现另一套Flyway锁，不将此验证误报为Go迁移器。

在同一临时PostgreSQL版本中，对照Java迁移的public与Go原SQL逐事务重放的独立schema。只读repeatable-read采集七类pg_catalog定义：表属性、列顺序/类型/NULL/identity/generated/default/collation、约束定义/可延迟/验证状态、索引定义/唯一/有效性、序列参数、触发器开关/定义、自有函数定义。排除Flyway自身历史表与扩展拥有的函数，仅归一化各自schema限定符；不忽略函数体、默认值和约束。

空重放和合成升级均为39表/287列/116约束/84索引/10序列/13触发器/9函数逐项一致。实际在Go副本加额外列必须产生差分，随后清理。不比较数据/部署属主ACL/注释或未来新增对象类型；这些另列验收，不能仅凭同一SQL来源宣称全库完全等价。

Alternatives considered：只比表数或最终版本；用Go自身预期生成双方结构；正例通过但比较器从不失败；忽略全部默认值/函数体以消掉差异；在原Java数据库故障注入。当前所有DDL仅在脚本新集群或自己的重放副本。

## R-023 合成备份恢复与表达式重解析

Decision：--backup隐含--flyway，只对新建migration专用集群注入明确虚构的数据，使用原生pg_dump custom和pg_restore --exit-on-error到同一临时集群另一个空数据库personalos_go_restore。备份仅驻留进程内存，--no-owner/--no-acl，退出删除本次容器，不接触现有服务。恢复后只读验证42条Flyway历史；40表（含历史）的全部行列排序后SHA-256及10序列last_value/is_called逐项一致。补充中文/emoji/组合字符、时区、含NUL的bytea、1024维向量与余弦查询、虚构opaque字符串，以及恢复后BIGSERIAL继续、唯一约束和源库不受写入影响。

发现PostgreSQL17 dump/restore将ANY数组整体::text[]重排为逐元素::text，部分索引条件也有相同变化。保留原始Java/Go结构对照的严格定义比较；仅备份场景在同版本PostgreSQL的临时空表重解析双方CHECK/索引，再比较规范化结果，不忽略常量/运算/类型。分别改变CHECK的QUEUE和部分索引PENDING为DIFFERENT，必须仍检出差异。临时表创建/删除不改业务DDL。

Alternatives considered：删除全部cast或跳过约束（可能掩盖真实差异）；仅比较表数/恢复命令退出码（不证明数据一致）；直接使用真实用户备份（越过本轮隔离）；把opaque字符串保留误报为真实密文加解密。此证据不覆盖生产属主/ACL、真实用户备份、真实密文/附件或跨PostgreSQL版本恢复；T103父任务和发布门禁继续开放。

## R-024 显式错误分类与已提交响应保护

Decision：业务层使用api.BusinessError承载明确公开文案，保留显式空字符串；非法/成功业务码失败关闭为50000。HTTP层提供显式校验/参数/404/405/406/上传/完整性冲突/断连类型，普通包装按最外层已知分类处理，不从原始异常字符串猜业务语义，多原因Join/循环链均通用500。仓储负责明确标记数据完整性冲突，网络失败不能误报409。

从固定基线直接编译ApiResponse/ErrorCode/BusinessException/ResourceNotFoundException/GlobalExceptionHandler，使用实际Boot BOM的Spring7.0.9和离线依赖，真实调用36个返回分支，同时检查405路径无查询串及断连DEBUG。只对照处理器输出，不把它声称为Servlet/JSON绑定/Bean Validation provider或安全链全量差分。Go普通错误强制JSON，即使Accept为SSE；HEAD不写body；日志仅级别/方法/路径/安全ID/业务码，不含原始错误、正文、查询串和panic堆栈，是原日志的安全收紧。

用显式Endpoint返回错误和局部响应状态适配器，保留ResponseController解包及原有Flusher/Hijacker可用性。100/103不是最终提交，101/写入/Flush/Hijack才锁定响应；已提交后的异常或panic抛http.ErrAbortHandler，由net/http中止连接，不能追加JSON。明确客户端断连只记DEBUG且无响应，不将所有context取消/超时都误当断连。正常SSE仍由业务协议发送错误事件，边界只是兜底。未接入主诊断应用。

Alternatives considered：直接回显err.Error（泄密）；errors.As任意深层业务cause覆盖外层JSON错误（错误优先级反转）；panic后始终补500（污染SSE/下载）；无限缓冲所有响应（破坏实时流和内存上限）；伪造不支持的Flusher/Hijacker（改变能力检测）；把上游deadline当客户端离开（吞掉真实服务故障）。

## R-025 Spring时长与数据大小转换

Decision：直接调用固定Boot4.1.1/Spring7.0.9的ApplicationConversionService，使用离线缓存依赖与合成字符串，不启动应用。固定228项未注解Duration/DataSize结果；空字符串保持null，纯空白不是null。Go转换返回新指针，区分null与零，不对负值/零值擅自增加业务限制。时长无后缀为毫秒，简写只允许整数ns/us/ms/s/m/h/d且后缀忽略ASCII大小写，不接受Go的1.5s/1h30m/µs；ISO支持Java的日/时/分/秒、符号、点/逗号与最多9位纳秒。

DataSize无单位为byte，B/KB/MB/GB/TB使用1024倍率且大小写敏感，移除全部Java Character.isWhitespace（包括中间空白），不把NBSP/NEL等额外吞掉。错误仅给通用文案，不回显配置值。用big.Int检查精确乘法/加法，返回前必须fit int64；8个Java合法但Go Duration纳秒范围无法表达的对照值明确返回ErrScalarOverflow，此为有意范围收紧，不能饱和或截断。

复查本机JDK Duration源与真实转换器，确认ISO实际按days + (hours + (minutes + seconds))检测long溢出，不能换成左结合或一次大数相加后才检查。补充两个会区分顺序的真实Java反例：一项合法1793秒、一项中间溢出必须拒绝；Go按相同顺序。正则用显式ASCII大小写，不用Go Unicode Fold接受Java不认的长s/全角字符。

Alternatives considered：直接time.ParseDuration（默认单位/ISO/简写不一致）；浮点解析小数（边界精度丢失）；空字符串直接默认（隐藏明确空值）；不限范围转time.Duration（整数回绕）；把8MB解释为十进制8,000,000（改变容器上传限制）。此处只完成标量转换，不宣称任意YAML、profile、relaxed绑定或所有配置类验收。

## R-026 显式兼容默认Flyway baseline1

Decision：--baseline隐含--flyway，只在新建临时集群另建go_flyway_baseline schema，预执行原V2（两张聊天表）并放虚构中文会话/消息，模拟无Flyway历史的非空旧schema；扩展已在本次集群通过原V1安装。真实Flyway12.4.0的baselineOnMigrate(true)生成版本1 BASELINE默认标记，只执行V2–V42共41项，validate和二次零执行通过。最终七类DDL与标准42迁移的public逐项相同，旧聊天数据保持。

Go保留NewHistoryValidator的默认严格策略；新增NewHistoryValidatorWithBaselineV1才允许第一行rank1/version1/BASELINE/success/checksumNULL/description与script均为<< Flyway Baseline >>。剩余41条仍校验全部SQL元信息/checksum/顺序/数量，不接受额外标记、未来版本、自定义描述或基线2。普通完整SQL历史在显式兼容策略下也有效。HistoryStatus.Applied仅计实际SQL，CurrentVersion=42，BaselineVersion=1（无基线为0），不把基线当作实际执行V1。

真实生成历史调用前后全字段摘要相同，破坏测试只改副本，所有失败均零成功状态/脱敏。不伪造或修复Flyway历史，不执行baseline或争抢DDL锁。历史标记不能证明V1扩展已存在，显式兼容也不替代实际结构验证；生产角色/属主ACL、其他基线版本/标记或用户备份仍待验收。

Alternatives considered：默许任意BASELINE就跳过校验（可隐藏缺失迁移）；Go自动baseline（更改迁移执行权）；把42行直接报告为42个已执行脚本（误导）；只改一个合成SQL行冒充真实Flyway行为。当前场景使用真实默认Flyway生成和校验，未连接现有数据库。

## R-027 全配置来源索引与Cookie真实读取路径

Decision：用基线离线依赖的YamlPropertySourceLoader和独立编译record反射，固定7 YAML文件/17文档/205属性出现项、12配置record/67字段及容器节点、构造器DefaultValue；另按词法提取70处@Value和8处直接getProperty。动态键不丢弃，MCP Map值递归展开通配record；保留泛型、源码位置、依赖/基线哈希、原始占位符。只替换构建期project.version，不读取真实.env，不实例化配置record或启动Spring。默认重新生成并比较，显式--write才更新；不支持的声明失败关闭。

索引发现原YAML在spring.servlet.session.cookie.secure放值，三个安全类却读取server.servlet.session.cookie.secure。用覆盖customizePropertySources的StandardEnvironment排除宿主属性/环境，只放原base/prod YAML和合成SystemEnvironment来源，实际调用原ProductionSecurityValidator。4场景证明：SESSION_COOKIE_SECURE=true会改变YAML路径但不会改变守卫读取值，prod仍拒绝；额外canonical环境变量才通过。原Java/YAML保留，历史差异显式记录。

Go LoadSecurity改按canonical路径的四种真实环境别名解析，先查系统来源所有别名再查.env，防止低优先级精确键压过高优先级别名。不把prod错误路径/未被消费的SESSION_COOKIE_SECURE强行当true。原三个prod开关优先级保持；诊断生产/认证/CSRF监听守卫不放开。此前参数级生产校验虽通过，但未证明来源绑定，本次以真实Java来源结果修正。完整YAML/profile、Binder默认与所有属性仍属T104。

Alternatives considered：只grep环境变量（漏record默认与重复文档）；先合并YAML再索引（丢来源/profile）；同时改原Java消掉差异（破坏固定基线）；把SESSION_COOKIE_SECURE当canonical别名（掩盖原行为）；为拿到true绕过生产守卫。当前只对合成来源验证，不接现有服务。

## R-028 三类record的真实Binder语义

Decision：先为Auth/RateLimit/AgentExecution提供纯函数绑定器，输入仅为已解析canonical字符串map，输出类型化配置。独立编译固定Java的三个record，以真实Binder.bindOrCreate/MapConfigurationPropertySource/ApplicationConversionService/PlaceholdersResolver.NONE生成241项结果；不把这误报为YAML/profile/系统环境绑定。DefaultValue与紧凑构造器分别验证：缺省整数取注解，显式空整数失败；Duration空串转换null后回落注解，而纯空白失败；即使关闭认证/限流，非法预算仍拒绝。

整数保留32位边界与Spring NumberUtils规则，移除所有Java空白（包括数字中间）；只接受原hex前缀与Integer.parseInt的BMP Character.digit，拒绝补充平面数学数字、Unicode正负号、浮点与分隔符。时长复用已验证转换器；4个Java合法但超Go纳秒范围的值保持ErrScalarOverflow。错误只含固定属性名和安全原因，不带输入或Binder原始异常。

Auth保留密码null/显式空字符串，用户名按Java strip/default。密码私有存储、访问返回独立副本，用户名/密码均不参与JSON或常规格式化；仍由AuthService校验启用后的必填和编码，避免将record构造和服务启动混为一谈。真实BCrypt两次失败后锁定及本地桶容量/关闭/关闭后非法配置验证通过。Agent仅绑定预算，不提前实现P4执行逻辑。所有函数不读文件、环境或创建连接，主入口守卫不变。

Alternatives considered：用strconv.Atoi替代Java整数（Unicode/空白/hex不一致）；把空字符串视作完全缺省（整数误放行）；在record里额外要求密码（改变错误优先级）；把原配置map保留在对象里（泄漏与可变别名）；在此阶段放开生产装配。来源解析、其余record和应用接线继续属T104/T105/T109。

## R-029 MCP动态绑定与集合来源边界

Decision：扩展原真实Binder夹具至四类record398项，MCP新增157项。显式BindingSource区分普通Map与SystemEnvironment来源，按高到低组合，不读取宿主变量。Java环境夹具的来源名使用原约定*-systemEnvironment；初版随意命名synthetic-0导致Boot没有选择环境映射器，发现后修正并加入原.env五字段独立断言，避免把错误夹具当基线行为。

动态server map按键/字段合并；List/Set只能由最高包含它的来源整体替代，空值与缺口不能向低来源凑齐。逗号转换只用String.trim、不当shell/CSV；索引原样保留空格。命令null/空串分开，unknown字段不凭空创建server；空record/map、只配置空时长、同源标量/索引与低来源非法值被遮蔽均直接对照。绑定器只覆盖MCP已列键形态，任意relaxed别名冲突/ConfigData/profile仍未验收。

实际SystemEnvironment将MCP_SERVERS_FETCH_ENV_API_KEY映射为透传键api.key，而普通属性env.[API_KEY]保留API_KEY；HTTP_PROXY同理。Go保留并记录原行为，不能按注释猜测成大写原样键。相关运行子进程/环境可用性/协议仍属P7；该绑定不执行命令或授权工具。

MCP record对null/非正时长先归一到10秒，所以合法负Duration即便原纳秒超Go范围也可归一；不能先套用Go Duration范围误拒。复用内部精确大整数解析，并补Java简单单位秒乘法及Duration long秒/纳秒总边界，拒绝Java自身溢出后再归一化的伪合法值；正值归一化结果超Go范围仍明确拒绝。外部ParseDuration的228项契约不变。

容器字段全部私有，访问返回副本；格式化/JSON隐藏server名称、命令、参数和环境。来源map仅同步读取，调用期间不能并发修改；返回对象不保留可变别名。显式map固定排序键用于对照，尚不承诺任意宿主来源别名冲突顺序。

Alternatives considered：把全部来源先合并成一个map（错误拼接列表/丢来源）；自动纠正ENV_API_KEY（悄改基线行为）；未知字段也注册空server（幽灵进程）；先将巨大负时长转Go再归一（误拒）；写通用反射Binder或引入Spring运行时。当前只提供配置边界，不提前实现P7。

## R-030 全部record构造器与primitive缺省

Decision：补齐其余8类canonical纯绑定，独立编译全部12类原record，真实Binder夹具从398扩展到1475项。复用既有整数语法但按字段区分int32/long64，先转换再交由各紧凑构造器归一，不能把正值校验错误套用到允许归一的record。Double遵循NumberUtils移除Java空白、Double.parseDouble大小写/hex/后缀/非有限值/IEEE溢出；原record保留的NaN也保留，不擅自修复业务行为。测试输出只对非有限值使用字符串标签，生产配置不从该测试编码器输出。

真实Binder证明Calendar初始延迟缺省0、Knowledge相似阈值缺省0、Protection重试缺省0（静态defaults工厂是1）。Context/Memory父record缺省创建enabled=true的nested；实际绑定任一已知子字段才采用primitive false。空Duration转换null时nested仍不被创建，首版按键存在判断造成两个真实差分，已修正并全量复验。未知nested子字段不能改变默认开关。

时长先检查Java自身完整范围，再按record允许零/负值归一，最后检查Go范围。字符串null/空保留，提示与应用字符串私有/脱敏、访问指针副本；扩展名集合私有且访问复制。Unicode小写使用已有x/text的完整大小写实现，不引入新运行时版本；业务执行与ConfigData/来源发现仍未接线。

Alternatives considered：把YAML默认值灌进record缺省（掩盖真实primitive差异）；按所有非正数报错（改变紧凑构造器语义）；用Go float语法直接接收Inf/小写nan（扩大Java接受集）；把NaN比较误判为越界；所有nested只按键存在判定。T104父任务继续开放。

## R-031 固定基线ConfigData与Dotenv执行顺序

Decision：将索引中原七个YAML/十七文档的原始扁平属性作为可重生成嵌入资源，保留全部未解析占位符和原文件哈希。Go无新增YAML依赖，也不读取任意外部文件；从显式map构建不可变来源。脚本从Git基线重建唯一临时配置目录，真实ConfigDataEnvironmentPostProcessor.applyTo执行文件发现/profile/default/include与加载，之后附加合成dotenv、ConfigurationPropertySources，再逐项查询189属性，133场景对照。

原DotEnvEnvironmentPostProcessor无Ordered/Order，而ConfigData有显式ORDER，故dotenv晚于文件发现。初版oracle先加dotenv再执行ConfigData，错误允许dotenv激活profile文件；复查源接口和顺序后修正。真实最终Environment可以延迟报告dotenv中的active，但文件未加载；例如dotenv worker-index与系统worker-index有不同role/池大小。Go分别记录用于文件加载的bootstrap列表和最终报告列表，不能按最终active重载。此前诊断配置的窄profile逻辑继续保留原边界，不将其视为Boot全量等价。

保留显式空、canonical环境映射与SystemEnvironment别名、profile顺序与多文档后者覆盖、嵌套key/default、未使用default不求值、反斜线转义和循环/缺失拒绝。深度64/结果1MiB为显式资源边界。来源和值均私有复制，错误/JSON/格式化脱敏，100路各10次读取无串扰。

Alternatives considered：在运行时调用Java（违背独立Go）；假装支持任意YAML/外部import却静默忽略；把dotenv提前参与激活（改变原装配顺序）；先合并所有来源（丢优先级与列表边界）；按未知profile直接默认combined而丢弃合法激活。任意外部ConfigData位置/导入/profile分组失败关闭，完整Binder/应用接线仍待完成。

## R-032 显式基础组合与资源所有权

Decision：OpenFoundation组合已有P1组件；默认CLI仍离线。调用方提供连接器、原迁移FS/public schema与可选池参数，Flyway只读校验成功后才启动续租。Redis为显式借用客户端，开关/namespace来自Properties，URI/TLS由创建者控制，不关闭借用资源，不回退本地。自定义schema因协调SQL使用public表拒绝，共享容量超过100在启动前拒绝。

HTTP保持Worker先隔离，RequestID/异常边界覆盖限流→并发→安全→路由；已提交SSE异常只中止。未知业务404、ready503、模型NOT_CONFIGURED。Close用受锁保护的准入计数防止Add/Wait竞争；依次排空HTTP/执行器、关闭协调器/池。期限耗尽取消并返回，清理等实际退出，避免任务持租约时关池。

Alternatives considered：默认进程自动连接当前服务；认证先于限流；Redis故障退内存；期限耗尽直接关池；用成功stub填未迁移API。均不采用。真实TLS临时库仅证明P1组合子集，管理端口/指标/CLI和发布门禁仍开放。
