# 验证记录

日期：2026-11-03。分支：`go`。固定 Java 基线：`cb5d694d62fa93742b5789b97273e76d931ce335`。

## 已执行：P0 基线工具

```bash
python3 -m unittest discover -s scripts/migration -p 'test_*.py' -v
python3 scripts/migration/go_inventory.py check
```

结果：4 项提取器边界测试通过；基线检查通过。覆盖注释/字符串/文本块不产生假注解、无参数/命名/多路径映射、动态错误路径、紧凑 Controller 与嵌套 record。

实际索引：1,093 个基线文件；Java 主源码 391、测试文件 154、SQL 迁移 42；注解 HTTP 路由 104、内部工具 23、MCP 工具 4、运行/配置钩子 95（含调度 13）、测试声明 1,119；显式环境变量名 145。

实际执行 `check-complete`：按预期返回非零，报告 2,438 个清单项尚未 verified、五项发布门禁未验证，确认未完成的迁移不能被误报为完成。

清单生成读取的是固定 Git blob，不读取本机 .env、备份、缓存或日志。检查会对照当前文件哈希、新增文件、生成文档和进度证据。

## 已执行：P1 基础代码

工具链：`go version go1.27.1 darwin/arm64`。所有 Go 命令使用可写临时缓存，`GOPROXY=off GOTOOLCHAIN=local`，没有下载第三方依赖。

在 backend-go 目录执行：

```bash
go test -race -count=1 ./...
go vet ./...
go build -o /private/tmp/personal-ai-os-go-cb5d694 ./cmd/personalos
```

结果：四个有测试的 Go 包全部通过竞态测试；命令入口编译通过；vet 通过；构建通过。该结果只覆盖已实现的基础契约，不代表 Java 业务完成。

覆盖内容：固定 Java 19 个错误码/文案/HTTP 状态快照、四字段信封/UTC/空 data、序列化失败脱敏、内存分页/SQL 页/空数组/大页码/Java offset 边界、五种角色及非法配置、Java trim/ASCII 请求 ID/32 路并发 context 隔离、诊断 200/503/405/HEAD 和未迁移 API 404、Worker 空 404。

真实进程冒烟脚本尝试申请 `127.0.0.1` 临时端口时，沙箱返回 `PermissionError: [Errno 1] Operation not permitted`；错误发生在启动 Go 子进程之前，因此未启动遗留进程，也没有实际验证监听、HTTP 网络链路和 SIGTERM 退出。处理器行为由 httptest 验证，不能替代进程验收。

## Git 提交状态

- 已提交：`f255fec chore(go): 固定 Java 迁移基线及防遗漏检查`。
- 用户已在本地完成：`b125f7e docs(go): 制定全量兼容迁移规格与分阶段验收计划`、`d59f85b feat(go): 添加基础响应契约与诊断启动入口`；本轮开始时工作区干净，分支仍为 go。
- 本轮已完成配置小阶段提交：`9614b84 feat(go): 移植环境配置来源与生产安全启动守卫`。
- 事务基础的自动提交曾被审批服务 503 阻断；用户随后已完成 `6cbfb19 feat(go): 添加显式连接池与可取消事务边界`。本轮开始确认分支为 go、工作区干净，该里程碑已进入提交历史。
- 此前本地令牌桶在竞态测试/vet/build/基线检查通过后立即申请独立提交，以及后续共享策略完成后的提交尝试，都被审批服务 503 阻断；这些失败的暂存/提交命令没有执行。
- 用户于 2026-11-03 将当前会话改为完全访问后，Git 普通命令已执行成功：`81ca139 feat(go): 移植本地模型令牌桶与并发边界`。共享限流策略、HTTP 中间件、Java 对照资产和迁移记录按另一笔增量提交保存。当前命令执行成功不证明 Aiport 自动审批服务本身已经恢复。
- 此前文档的后续暂存/提交尝试被自动审批服务连续返回 503 阻断，失败的操作未执行。这是审批基础设施故障，不是对修改安全性的否定；日志没有提供具体服务端原因。
- 后续仍按小阶段提交，并遵循当前会话权限执行；未完成的提交不算已保存到提交历史。没有推送或部署。

## 本轮：P1 配置来源与安全启动子集

已执行 `go test -race -count=1 ./...`、`go vet ./...` 和命令构建；测试通过。新增覆盖 Java .env 字面量解析、重复键顺序、CR/LF/CRLF、Unicode strip 差异、无效 UTF-8、最近文件/三层父目录边界、空值与系统环境覆盖、profile 静态值高于文件、运行角色顺序、生产守卫顺序/中文错误/16–72 UTF-8 字节边界、日志格式化脱敏。入口测试证明 prod 在监听之前被拒绝；这不验证真实监听或认证实现。

前端已实际执行 `npm run type-check` 和 `npm run build`，均通过，前端源码未修改。尚未跑全量 lint/组件/E2E。

Java 已实际执行 `./mvnw -o -B -Dapp.dotenv.enabled=false verify`，失败：汇总 859 tests、0 failures、494 errors；报告包含 Docker 环境不可访问、HTTP 模拟器 Socket Operation not permitted 等沙箱限制。未跳过失败项来声称全绿；真实环境 Java 全量质量门禁仍待恢复环境后重跑。

另执行 `./mvnw -o -B -Dapp.dotenv.enabled=false -Dtest=DotEnvParserTest test`：11 个解析测试全部通过，但 Maven 整体仍失败，原因是单独执行测试后的全项目 JaCoCo 覆盖率检查（0.80 < 0.83，并有已有执行数据与 class 不匹配警告）。未关闭覆盖率门禁；该结果只用于核对原 Java 解析用例，不能视为 Java 质量门禁通过或 Go 行为差分。

查询 pgx 可用版本时 DNS 失败，联网审批返回 503，命令未执行；未下载或锁定未经验证的驱动。Docker socket 访问被拒绝，未启动容器、未连接现有数据库。T102/T103 仍未完成。

## 本轮：P1 标准库连接池与事务

先加入事务测试，确认因待实现 API 而编译失败，再实现 Pool。最终重新执行 `go test -race -count=1 ./...`、`go vet ./...`、`go build -o /private/tmp/personal-ai-os-go-p1 ./cmd/personalos`，六个有测试的包全部通过；没有新增外部依赖。

受控测试驱动覆盖：API/Worker 池上限与 5s 默认值、非法配置在连接前失败、探测失败清理、握手期限、同连接执行/提交、只读/隔离选项透传、业务失败/panic 回滚、原始错误可用 errors.Is 识别但普通格式化脱敏、回滚失败保留业务错误、连接池队列超时不取消父 context、独立嵌套事务、父取消不提交、幂等关闭与关闭后拒绝、32 路并发与 Worker 4 连接上限。提交失败不重试。

这些测试不验证 PostgreSQL SQL、数据原子性、锁、TLS、驱动类型、pgvector 或 Flyway 历史；Pool 尚未接入应用或生产退出流程。完整 T102、T103、T108 和 P1 验收仍待完成。驱动联网查询的第二次审批仍返回 503，未执行；后续需先恢复依赖下载与隔离数据库环境。

## 增量：P1 本地令牌桶

按固定 Java `RateLimitProperties`、`TokenBucketRateLimiter` 和四个本地桶测试移植。测试使用可控时钟而非 sleep，覆盖初始注满/耗尽、7 次每分钟的分数补充、容量上限、零间隔/时钟回拨、关闭开关仍校验参数、关闭时不消耗、Java int 范围及 1,000 路并发不透支。

本地桶包先通过定向竞态测试，再执行 `go test -race -count=1 ./...`（七个包通过）、`go vet ./...` 和命令构建，均通过；基线检查通过。无新依赖，无应用接线、数据库或 Redis 访问；共享后端和指标仍待迁移，T107 保持未完成。本轮 PostgreSQL 驱动查询又被审批 503 阻断，查询命令未执行、未锁定版本。

## 增量：P1 模型路径与共享限流策略

先加入共享后端/模型路径测试，确认待实现 API 编译失败，再实现共享选择和路径匹配；随后先加入 HTTP 测试，确认缺少中间件时失败，再实现中间件。两个包的定向竞态测试均通过。

已直接执行固定 Java 基线的 ModelRequestPaths，生成并再次核对 216 项结果。版本来自固定基线的 Spring Boot 4.1.1 BOM（Spring Framework 7.0.9、Tomcat 11.0.24），全部使用本机缓存 JAR；Java 21 离线 javac/java，无 Maven 下载、Spring 启动、数据库或监听。输入含全部十个 POST/一个 PUT 模式、编码/双编码/编码斜杠/非法转义、方法大小写、Unicode/无效 UTF-8、挂载前缀、重复/末尾斜杠和普通路由；Go 对照全部通过。直接 matcher 接受的路径不代表 Servlet/安全链/路由也接受，特别是点段/非法 UTF-8 等用例。

共享策略使用受控替身验证 PostgreSQL/Redis 选择、`model-rate`/容量/周期与原始 context、关闭时不访问、Redis 出错拒绝且不回退、PostgreSQL 出错向上传递、成功/拒绝/失败计时及 Redis 错误回调。没有实现 Redis Lua/PostgreSQL 原子消费适配器，也没有接入真实指标系统。

HTTP 测试覆盖原 Java 四项断言及编码、单次消费、请求 ID 先行/拒绝回显、42900/中文文案/null data/UTC 时间戳、日志不含 query/正文/Authorization、放行 SSE 透传及后端错误的脱敏 50000。中间件尚未接入诊断应用；Servlet 容器级异常映射的真实差分仍待 T106。

全部增量完成后再次执行 `go test -race -count=1 ./...`（七个包通过）、`go vet ./...` 和命令构建，均通过。Java 216 项快照重新执行核对通过；四项 Python 索引测试、1,093 文件基线检查、Markdown 本地链接与 git diff --check 通过。`check-complete` 仍按预期拒绝全部 2,438 个未 verified 项及发布门禁，没有预先升级验证状态。本轮未重复运行未变更的 Java 全量/前端门禁，其既有未通过事项继续保留。

## 增量：P1 真实进程冒烟（T100a）

2026-11-03 权限调整后重新构建 `/private/tmp/personal-ai-os-go-p1` 并实际执行 `python3 scripts/migration/smoke_go_process.py --binary /private/tmp/personal-ai-os-go-p1`，combined/api/index/review/delivery 五种角色全部通过。每个进程使用新临时目录和最小显式环境、随机回环端口，禁止 .env；验证 GET livez 200/readyz 503、HEAD 无正文、POST 405/Allow、请求 ID 回显、未知 API 404（Worker 无正文）、SIGTERM 返回 0 和端口释放。所有测试子进程均已退出。

该结果解除此前端口绑定受限造成的 T100a 阻塞；不证明数据库/认证/真实 Worker/生产优雅退出已完成。没有访问现有 Java 监听端口、数据库、模型或用户数据。

## 增量：P1 pgx 连接器与真实 PostgreSQL（T102b）

2026-11-03 下载并固定 pgx/v5 v5.12.0 与间接依赖，保留实际模块许可证。连接器配置单测覆盖非法参数、隐式 PG* 拒绝、显式特殊字符凭据、禁回退、TLS 1.2/verify-full/根证书、格式化与错误脱敏。配置测试不证明真实 TLS 握手通过。

执行 python3 scripts/migration/run_go_postgres_tests.py，在固定镜像摘要的独立 tmpfs PostgreSQL/pgvector 容器中运行 integration 标签、race 与 count=1 测试，三个真实集成测试均通过：提交、业务错误/panic/父取消回滚、只读 SQLSTATE 25006、serializable、UTC/UUID/JSON；pg_sleep(30) 被 100ms deadline 取消且池可恢复；32 路事务竞争验证 Worker 最大 4 连接与释放。脚本已清理本次容器，不使用持久卷、现有 Compose 或 Java 数据库。

全部七个 Go 包的 race 单测、go vet、二进制构建和 go mod verify 通过。尚未接入应用/原 SQL、验证 pgvector、真实 TLS、最小权限、Flyway 历史/锁或恢复。该阶段补充此前受限驱动/数据库验收，不改变历史执行记录，也不代表 T102/T103/T107 完成。

## 增量：P1 PostgreSQL 共享限流（T107c）

先加入单测并确认 NewPostgresBucket 未实现时编译失败，再实现独立短事务和 Java 原条件 SQL。定向 race 单测通过。随后执行一次性数据库脚本，数据库基础与共享桶 integration/race 测试通过。限流直接加载 V37 原 SQL，没有改写表结构或创建 Flyway 历史。

真实场景：两个独立 Pool 共享预算/数据库时钟补充、分数拒绝不更新、未来 refilled_at 不负补充、容量缩小裁剪/扩大不重新注满、业务外层回滚不返还消费；128 路并发初始化容量 17 正好放行 17；两个独立 OS 子进程共 64 次尝试容量 13 正好放行 13；100ms 锁等待取消不改预算且归还连接；缺表 SQLSTATE 42P01 脱敏报错，不建表或回退。所有子进程退出、临时容器清理完成。全量七个 Go 包 race 单测、go vet、构建与基线 1,093 文件检查通过。

只迁移 DatabaseCoordination 的 consume 及原测试对应限流场景，不把 acquire/renew/Lease/fence/延迟释放或 API 预留标成已实现。Redis/指标系统/应用装配、断网与提交不确定故障、完整 Java/Go 服务差分待完成；T107 和 P1 保持未完成。

## 增量：P1 原 SQL 全量隔离重放（T103a）

先写目录测试并确认缺少 LoadVersioned 时编译失败，再实现显式数字排序、连续唯一、原字节哈希与格式拒绝；对照固定 Java inventory 的全部 42 个文件及 SHA-256，通过 race 单测。没有复制或改写原 SQL。

执行 python3 scripts/migration/run_go_postgres_tests.py，数据库基础、共享限流和迁移三个包全部 integration/race 通过。migration 在自己创建的 schema 中分别从 V1 到 V42 重放空库和合成历史数据两种场景，每个原文件一个事务，保留 PL/pgSQL 原函数体。核对最终 39 张表、13 个用户触发器、四个扩展、两处 vector(1024) 和两个 HNSW vector_cosine_ops 索引。

实际断言还覆盖 CHECK/唯一/FK/级联、单会话 RUNNING 部分唯一与 WAITING_USER、向量余弦查询/错误维度拒绝；文档来源初始化、修订/审查任务重试归零、搜索 claim/重试重置、review/search 唤醒 Outbox、候选 generation 更新和失败事务不残留业务/唤醒记录。合成 V16/V20 数据验证 V17 UTF-8 记忆哈希、V21/V22 模型配置搬迁、V23 模型清单。测试 schema/子进程/容器均清理。详细日志：/private/tmp/personal-ai-os-go-postgres-tests.log。

阶段结束再次执行全量八个 Go 包 race 单测、含 integration 标签的 go vet、命令构建、go mod verify，全部通过；五种角色真实进程冒烟再次通过。四项 Python 索引测试、1,093 文件基线、Markdown 本地链接及 git diff --check 通过；Docker 标签检查没有残留测试容器。check-complete 仍按预期拒绝全部 2,438 个未 verified 项。

此处没有真实 Flyway 执行、历史/CRC32/锁验证、存量备份恢复或完整 Java/Go 结构/业务差分。目录 SHA-256 不是 Flyway checksum；全部 SQL 仍仅 in_progress，T103/P1 与发布门禁保持开放。

## 增量：数据库租约与事务完成边界（T107d）

事务完成与租约测试先确认待实现 API 编译失败，再实现 WithManagedTx/Transaction/AfterCompletion 和 coordination。受控测试验证完成回调在归还连接后、提交/回滚/panic/提交失败路径、全部清理执行与业务 panic 优先、错误保留、只读/结束事务限制。

专用临时 PostgreSQL 的原 V37 集成通过：跨池容量/幂等释放；过期 owner fencing 失败且不能释放替代 owner；续租失效不复活；提交/回滚/panic 后延迟释放；无事务/只读/结束事务拒绝；32 路初始化竞争容量 4；SKIP LOCKED 跳过锁行；缺表续租撤销本地信任；实际等待 11 秒验证 10 秒循环续租与关闭释放。日志 /private/tmp/personal-ai-os-go-coordination.log，容器已清理。集成脚本现为每包新建独立容器，避免原 V37 与其他测试 DDL 相互污染。

全量九包 race 和含 integration 标签的 vet 通过；基线检查修正测试声明行号后通过。显式 context、活动写事务要求与独立 5 秒清理期限为 Go 生命周期约定；应用/Worker 业务退出装配、断网/提交不确定仍待完成，不能把整文件或 T107 标为 verified。

## 增量：模型共享并发与 HTTP 流生命周期（T107e）

先写本地门控/HTTP 测试并确认缺少 API 编译失败，再实现。1,000 路竞争上限、幂等释放、非法 Java int 配置及取消通过。独立 PostgreSQL 容器验证 index/review/delivery 共享后台配额并保留 API 槽、combined 前台策略、limit=1 原行为、共享拒绝归还后台、本地/数据库释放，以及触发器注入的共享获取失败/释放失败仍完整清理。日志 /private/tmp/personal-ai-os-go-modelgate.log，容器清理完成。

ConcurrencyMiddleware 对同一模型路径持有整段 ServeHTTP/SSE 许可；channel 控制的流处理中第二请求 42900，处理结束归还，正文与 Flusher 透传。成功/请求取消/panic 后均归还许可，普通任务路径绕过。另以真实回环 HTTP 服务验证流建立、存续期间 429、客户端断开导致处理器退出/许可释放以及下一请求 204。全量十包 race/含 integration 标签 vet 和五个独立容器数据库集成通过；应用/Worker/安全链尚未装配，T107 保持开放。

## 增量：单用户凭据与 BCrypt 对照（T105a）

先加入认证测试并确认待实现 API 编译失败，再实现构造校验、默认用户名/Java strip、disabled/null/空输入、正确与错误凭据、私有 BCrypt 哈希、成功清零、阈值后下一请求 42900、秒数向下取整/最少 1、到期解锁。100 路并发失败严格只执行阈值 3 次校验，其余 97 次锁定；该串行化收紧 Java 原并发状态窗口，串行契约不变。

离线 javac/java 直接执行基线 Spring Security 7.1.1 编码器，记录并核对 74 项合成密码结果，覆盖 Unicode/组合字符/NUL/空输入、71/72/>72 字节；Go bcrypt 2a/cost10 和校验结果全部一致。固定盐仅用于对照快照，运行时仍使用 crypto/rand。定向及全量十一包 race、含 integration 标签的 vet、二进制构建、go mod verify、1,093 文件基线和 diff 空白检查通过。全量认证/会话/CSRF/共享存储与真实应用仍未装配，T105 与发布门禁不升级为完成。

## 增量：本地会话与认证 HTTP 边界（T105b）

先写会话/HTTP 测试并确认待实现 API 编译失败，再实现 MemoryStore 和可组合安全处理器。定向 race 覆盖闲置续期/恰好到期、轮换旧 ID 失效/旧令牌丢弃、100 路首次 CSRF 只生成一个值、ID 冲突不删除旧会话、取消不写、过期/注销后 EnsureCSRF 不复活。

HTTP 验证所有请求认证/公共路径、匿名 me、认证关闭不造假登录、登录失败/阈值下一次 42900、CSRF 在认证前 403、令牌绑定 Cookie/登录后变化、MCP 仍需认证、Secure/HttpOnly/Lax/path、注销删除 Cookie/null data、HEAD/405、表单/参数 token、重复 Cookie/歧义路径拒绝、后端失败 50000 和日志/响应脱敏。真实回环 HTTP+CookieJar 完成取 CSRF → 登录换 ID → 身份访问 → 新 CSRF → 注销 → 旧状态 401，未使用 Java 实例或用户数据。完整 Java Servlet/框架差分、Redis 与应用/真实任务和健康接口尚未装配，T105/P1 保持开放。

全量十一包 race、含 integration 标签的 vet、二进制构建、四项 Python 索引测试、1,093 文件基线及 diff 空白检查通过。

## 增量：专用 Redis 与跨实例会话（T105c）

先写客户端/存储构造测试并确认缺少实现时失败，再实现显式 URI、原 Java 的解码后 userinfo 冒号拆分、rediss TLS 验证配置、2 秒父 context 受限操作、有限连接池、无命令自动重试、关闭和错误脱敏。回环不响应 TCP 测试证明 100ms 父 deadline 不被 2 秒默认值覆盖；预取消和幂等关闭通过。URI/证书单测不代表实际 TLS 握手。

运行 run_go_redis_tests.py：每包独占固定摘要 redis:8.2.1-alpine 的新 tmpfs 容器，无持久卷/既有服务，密码随机生成，只暴露随机回环端口。真实测试验证两独立客户端共用身份、100 路首次 CSRF 只有一个值、ID 冲突不破坏旧状态、轮换/注销和过期不复活；TTL 读续期、CSRF 不重置 TTL、实际过期、错误 schema/持久无 TTL 拒绝，模拟 Spring 命名空间保持不变。独立 OS 子进程成功读取并注销父进程会话；两个真实 HTTP 服务通过 SESSION Cookie 跨实例认证/取新 CSRF/注销，另一实例拒绝旧 Cookie。关闭 Redis 客户端后请求为 50000，没有本地或匿名成功回退。日志 /private/tmp/personal-ai-os-go-redis-tests.log；本次容器全部清理。

Go 值格式刻意不兼容 Java 序列化，命名空间附加 :go:v1:，切换须重新登录。真实 TLS/ACL 最小权限、网络分区/不确定响应、完整配置与应用装配仍待验收；T105/P1/五项发布门禁继续开放。

新增依赖后全量十二包 race、含 integration 标签的 vet、二进制构建、go mod verify 和五个专用 PostgreSQL 包集成再次通过；1,093 文件基线与 diff 空白检查通过。go mod tidy 首次离线因缺少新依赖的测试模块失败，随后仅开放官方代理完成 tidy；实际测试/构建继续使用 GOPROXY=off。

## 增量：Redis 共享模型桶（T107f）

先写构造/原脚本对照测试并确认缺少 API 编译失败，再实现原 Lua 与 namespace SHA-256。定向 race 通过；真实不响应回环 TCP 验证准入使用 150ms 边界，不会沿用会话默认 2 秒。

专用临时 Redis/race 集成通过：原 Java 32 路/容量 5 正好放行 5，扩展 128 路/容量 17 正好放行 17；两个独立 OS 子进程共 64 次尝试容量 13 正好放行 13。还验证分数不足拒绝仍更新 tokens/at、未来 at 不额外补充、容量/周期变更报错不破坏原状态、period+1000ms TTL、纳秒周期夹至 1ms、预取消不消费、Redis 失败返回拒绝/错误指标且不访问 PostgreSQL。日志 /private/tmp/personal-ai-os-go-redis-ratelimit.log，子进程与容器已清理。

原 Lua 的执行行为得到真实 Redis 验证，不代表原 Java 服务完整差分；实际 TLS/不确定提交/配置与真实指标/安全角色链装配仍待完成，T107/P1 保持开放。

全量十二包 race、含 integration 标签的 vet、二进制构建、1,093 文件基线与 diff 空白检查通过。

## 增量：真实 Redis TLS（T105d）

运行 python3 scripts/migration/run_go_redis_tests.py --tls，三个专用容器（redis/auth/ratelimit）全部 integration/race 通过。分别生成临时 CA/服务端证书，不接现有 Redis，不安装系统 CA；CA 私钥不挂载容器。实际验证正确证书/主机名可用、未知 CA/错误主机名为对应 x509 拒绝，明文连接 TLS 端口无法成功。同一 rediss 传输下，全部共享会话、真实 HTTP/CookieJar、跨进程注销和 Lua 限流/跨进程预算场景通过。日志 /private/tmp/personal-ai-os-go-redis-tls.log，容器/临时证书已清理。首次夹具证书配置混用造成就绪失败，已分离服务端配置修正，并保留就绪失败脱敏诊断/清理路径。

另在最小环境、禁 .env、Maven 离线模式执行 -Dtest=AuthServiceTest test：原 Java 10 项断言全部通过，Maven 目标仍因 JaCoCo 累计行覆盖率 0.80 < 0.83 失败；没有关闭/跳过覆盖率门禁，也不据此声称 Java verify 通过。日志 /private/tmp/personal-ai-os-java-auth-test.log。

## 增量：真实 PostgreSQL TLS 与事务权限（T102c）

执行 run_go_postgres_tests.py --tls --package database 后，再执行 --tls 全五包；database/ratelimit/migration/coordination/modelgate 全部 integration/race 通过。真实 pg_stat_ssl 为受支持 TLS；未知 CA、错误主机名、明文拒绝，正常连接不受失败探测影响。全部原 SQL、共享桶、数据库租约及实际续租、模型并发测试同时在 TLS 复验。日志 /private/tmp/personal-ai-os-go-postgres-tls-all.log。

同一临时集群新建无管理权限账号，仅授独立探针 schema/单表 DML/identity 序列权限；Unicode 事务写读更新删除成功，建库/角色/DDL/未授权读/自提权限均 SQLSTATE 42501。plain database 模式也通过。提取 disposable_tls.py 为 PostgreSQL/Redis 共用，Redis --tls --package redis 回归通过。日志 /private/tmp/personal-ai-os-go-postgres-security-plain.log 与 /private/tmp/personal-ai-os-go-redis-tls-refactor.log。含 integration 标签的 go vet、1,093 文件基线及 diff 检查通过；本次容器清理。完整配置/全部 schema 权限、Flyway 历史锁/恢复、真实网络分区与应用装配仍未完成，T102/P1 保持开放。

## 增量：Redis 最小 ACL 与已执行写入丢响应（T105e）

run_go_redis_tests.py --package redis 和 --tls --package redis 全部 integration/race 通过。使用本次临时创建的两个受限账号，验证完整会话 Load/Rotate/EnsureCSRF/Delete 和容量2共享桶；直接命令及 Lua 的管理权限/键空间越界均 NOPERM。真实 ACL 失败使会话 HTTP 返回脱敏500且不签 Cookie，模型限流计错误并429，无 PostgreSQL/本地回退。首次 HTTP 用例错误地使用不存在的模型路径，改为原 /api/v1/chat/sessions/fixture/stream 后通过，没有改变原路径策略。

透明代理实际切断已执行 EVAL INCR 的响应；调用错误但管理员观察计数1，只有原一次连接；下一次显式 PING 重连，计数仍1。在 TLS 下同样验证。日志 /private/tmp/personal-ai-os-go-redis-acl-loss.log 与 /private/tmp/personal-ai-os-go-redis-acl-loss-tls.log。临时容器清理。该验证覆盖精确丢响应，不代表完整生产网络分区/部署验收。

## 增量：真实依赖健康报告（T106a）

先加入服务/HTTP测试并确认缺少实现时编译失败，再实现。定向 race 和全量十三包 race、含integration标签的vet通过。对照原Java三种数据库/模型状态和控制器信封，增加UTC/独立对象/并发、探测期限/父取消、日志脱敏、GET/HEAD/405/匿名安全链断言。日志 /private/tmp/personal-ai-os-go-health.log 和 /private/tmp/personal-ai-os-go-stage-health-race.log。

run_go_postgres_tests.py --package health 和 --tls --package health 均通过。只在自己创建的新集群，由维护库连接实际禁用测试库连接并终止探针连接，真实HTTP保持200并报告DOWN；恢复后UP。单槽池饱和时50ms探测期限生效，归还后UP/零占用；健康探测不建业务表。日志 /private/tmp/personal-ai-os-go-health-postgres.log 与 /private/tmp/personal-ai-os-go-health-postgres-tls.log。初版夹具尝试从当前库禁用自身连接被22023拒绝，已改为同一临时集群维护库，资源清理。应用/配置/模型provider与完整框架差分尚未装配，T106/P1保持开放。

## 未执行 / 尚未实现

- Java 全量 Maven 回归已尝试但未通过；前端类型与构建已通过，完整前端检查仍待完成；本次未修改原 Java/前端实现。
- 已有一次性 PostgreSQL 驱动/事务集成；Java/Go 业务差分及其他真实第三方设施集成尚未执行。
- 没有迁移现有数据库、模型密钥、附件、Redis 会话、RabbitMQ/OpenSearch/S3 数据。
- 没有替换本地运行实例、推送分支、合并 main 或执行部署。
- 全部业务模块和路由迁移、完整配置/认证/安全/持久化/模型/后台任务均未完成。
- `check-complete` 当前必须失败；最终验收依赖 tasks.md 的 P1–P8 和五项发布门禁。

## 证据边界

源码哈希和词法索引能防止基线文件静默缺席或漂移，不能证明 Go 方法行为与 Java 完全一致。必须结合代码审查、逐项测试替代、真实数据升级/恢复、故障注入与前端验收，才能将条目标记为 verified。
