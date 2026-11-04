# P8 全量验收与运维进度

2026-11-04：P8实施中，T800–T808父项尚未收口。固定Java基线继续为`cb5d694d62fa93742b5789b97273e76d931ce335`。五个发布门禁未verified；frontend_e2e已开始并保留未通过记录，所有清单条目不自动升级verified。现有Java部署与SQL保留；没有自动推送、合并、生产部署或删除Java目录。

## 实施顺序

1. 补齐默认关闭的数据保留策略及生命周期，避免部署遗漏实际运行钩子。
2. 独立Go镜像/Compose/Caddy、五角色/管理端口、非root及显式凭据，先在隔离环境验证。
3. 本地启动停止、备份恢复、Push密钥和监控告警，复用基线资产并记录差异。
4. 全量前端检查与现有E2E接Go；逐页、通知和上传下载在真实浏览器验证。
5. 汇总并审查154类/1119测试声明、全部来源文件和接口证据，逐项补缺。
6. 真实设施全量/故障/恢复、性能与隔离切换演练；证据通过后才核验发布门禁。
7. 更新Go运维/开发说明，保留迁移历史；Java归档须用户验收后另作决定。

## T801a 数据保留策略

`--foundation --p8` 包含P7及全部前序业务，新增用量/操作记录清理；默认保持回环监听，不解除prod拒绝或readyz503。`app.retention.token-usage-days`与`app.retention.operation-record-days`默认0（永久保留），负值/超过Java int的值启动拒绝。两类截止时间独立，按UTC瞬时减整天，严格早于截止时间才删除；不能用Go time.Duration乘天数导致大整数溢出。

`app.retention.cron` 默认本地04:30，支持原P4已验收的六字段数字/名称/范围/列表/步长/?、标准宏及`-`。L/W/#扩展在连接前明确拒绝，不静默改成默认。与附件GC复用同一解析器，原附件测试重新race通过。串行循环不重叠，同秒去重，每轮30秒限制；停止先取消并等待清理后才关池。

安全差异（R-053）：原Java仓储按时间删除全部操作记录，会连同结果未明的TOOL占位删除。Go依据FR-006及R-043保留`source=TOOL AND outcome=IN_PROGRESS`，避免自动解除幂等保护并重放未知外部副作用。完成的旧审计仍按显式保留期删除，截止边界记录保留；需要人工核查未知结果，不能用清理代替决定。

原Java声明映射：`DataRetentionServiceTest.permanentRetentionNeverDeletes`、`enabledPoliciesUseIndependentCutoffs`、`eachPolicyCanBeEnabledIndependently`、`rejectsNegativeDays` → `TestP8RetentionPermanentAndIndependentCutoffs`、`TestP8RetentionBinding`；仓储和调度补充为`TestP8RetentionCutoffsAndUncertainTools`、`TestP8RetentionFoundationValidationAndShutdown`、`TestP8RetentionCronDisableCancellationAndDeduplication`。

实际验证：

- `go test -race -count=1 ./internal/platform/maintenance ./internal/platform/config ./internal/business/chat`：通过。
- `go test -race -count=1 ./internal/platform/maintenance ./internal/platform/config ./cmd/personalos ./internal/app -run '^TestP8'`、integration vet/build：通过；无integration标签的app未运行数据库测试，另列下项。
- `python3 scripts/migration/run_go_postgres_tests.py --package app --tls --run '^TestP8'`：2个真实TLS PostgreSQL/pgvector测试全部通过race（10.743s）；原42迁移真实Flyway竞争、validate/二次零执行通过，临时容器已清理。
- 真实库覆盖永久保留无写、两类独立截止、精确边界、第二次清理零删除、未知TOOL保留；真实Foundation开启每秒清理、负值/非法cron拒绝、正常/重复关闭通过。
- P8 CLI包含全部前序模块；容器网络仅在显式`--container --foundation --p8`且认证/CSRF同时开启时允许通配监听，其他入口继续回环。`TestP8ContainerNetworkIsExplicitAndAuthenticated`和`TestP8ContainerListenerBoundary`通过race；这只是容器监听边界，镜像/五角色实际运行另行验收。
- 24项Python迁移脚本测试、78项配置台账和1093文件固定基线check通过。两处保留期配置已wired，7项保留策略来源/调度/声明记implemented；其他项目与五门禁不变。

该子项不代表T801的全部备份/恢复/监控职责完成，也不代表P8发布验收完成。

## 首批提交门禁

2026-11-04 Java全量离线verify通过：1137测试、零失败/错误/跳过，JaCoCo原覆盖率门禁通过，BUILD SUCCESS（27:49）。命令另加`-Dapp.dotenv.enabled=false -Dspring.test.context.cache.maxSize=2`，不读取现有.env。日志`/tmp/personalos-p8-java-verify.log`。

前端lint/format/type-check、1151逻辑测试通过；首轮组件并发运行两项超时，保留失败记录，串行复验30文件/195测试全部通过（145.88s），随后生产构建通过（6.60s）。未改前端源码或放宽测试期限。日志`/tmp/personalos-p8-frontend-components-serial.log`、`/tmp/personalos-p8-frontend-build.log`。最后源码的integration vet/build也通过。

## T800a 独立镜像与角色边界

Go和原前端Web候选镜像均实际构建成功。第一次Go构建的Debian HTTP源经本机代理返回EOF，切HTTPS并保留签名验证后成功；没有关闭TLS或包签名。Dockerignore以源码/原SQL白名单排除.env、密钥、缓存和测试材料。Go运行UID10001、只读根、无capability且no-new-privileges；镜像包含Poppler22.12.0及发行版版权信息。

`python3 scripts/migration/run_go_postgres_tests.py --package app --tls --run '^TestP8' --p8-image personal-ai-os-backend-go:candidate`：重跑的3个P8顶层测试race通过（3.039s），随后combined/api/index/review/delivery五种实际镜像角色全部通过。覆盖真实verify-full数据库连接、管理health/metrics、未知管理路径403、API登录Cookie/CSRF、业务不暴露监控、Worker不暴露业务、readyz503及SIGTERM退出0。无应用宿主端口、无持久卷，所有新建容器已清理。首轮smoke脚本错误把空403正文的前导换行strip后丢失状态分隔，已改为显式HTTP_STATUS标记；并非后端启动/鉴权失败。保留两轮日志`/tmp/personalos-p8-container-smoke.log`、`/tmp/personalos-p8-container-smoke-retry.log`。

原生Compose配置4测试通过：缺凭据拒绝、combined非root/私有端口/原SQL、distributed角色/独立池、prod候选安全Cookie/TLS端口。候选`compose.prod.yml`仍会被二进制prod守卫拒绝，不是生产可用性证明；Caddy实际HTTP/TLS、Flyway镜像及完整Compose运行在后续验证。部署配置和操作边界见`backend-go/deploy/README.md`。首批运行时已提交`a376cc9`，本批镜像/部署定义独立提交。

## T801b 本地生命周期与原格式备份

新增`go_runtime.py`显式环境/强制关闭dotenv、认证/CSRF、私有状态目录和日志、进程启动身份核对、重复启动拒绝、45秒优雅停止不强杀，以及仅含存活Worker的发现文件。`go_backup.py`只复用原备份函数，不调用原settings()，要求显式Go数据库/容器/附件/目录；默认加密，明文须显式选项，默认永久保留。状态不与Java脚本共享。

`python3 scripts/migration/run_go_local_tests.py`在新建TLS库实际启动combined/api/index/review/delivery五种本地二进制角色，重复拒绝、三Worker同时发现、退出后空发现通过；停止全部进程后，原tar/custom pg_dump格式含合成附件的备份、校验及随机隔离库恢复通过；原Push生成器0600和拒绝覆盖通过。临时证书/密钥/备份/库/进程随测试清理，没有接触现有服务或密钥。日志`/tmp/personalos-p8-local-actual-3.log`。

前两轮本地runner分别发现Python3.9没有hashlib.file_digest、Popen已回收子进程导致重复waitpid；已改流式SHA-256并兼容ECHILD，均属于脚本兼容修复。应用用例三轮均通过；最终完整运维链通过后才勾选T801b。监控、告警、评估和切换仍由父项及后续任务追踪。

## T800b 完整combined Compose实测

原生配置检查之外，独立新随机项目实际运行PostgreSQL、官方Flyway、Go和原前端Caddy。Flyway12.4镜像仅amd64，显式Docker仿真；没有更改原42条SQL。verify-full证书含本次服务DNS SAN。库与后端没有发布宿主端口，Web仅随机回环端口。

实测发现并修复：YAML flow-list的tmpfs逗号必须引用；Caddy官方二进制文件capability与cap_drop ALL冲突，镜像构建时移除无特权端口不需要的文件capability；Caddy管理路径必须使用互斥handle，避免被SPA fallback抢先接管。保留非root、只读根、cap_drop ALL及no-new-privileges。测试fixture另修标准Base64模型密钥和Web readiness等待，未放宽HTTP断言。

第8轮完整链通过：原前端/安全头、管理与探针路径404、未登录401、错误登录/CSRF、登录后真实任务持久化、multipart上传与内容下载一致、SPA页面、42条迁移历史、官方Flyway再次零迁移/validate、SIGTERM退出0。日志：/tmp/personalos-p8-compose-actual-8.log；先前失败日志均保留。runner只删除自己新建项目/卷，未操作现有Compose资产。分布式完整栈与prod/TLS仍待验收，不解除prod拒绝和readyz503。

第9轮在相同链上增加Go/Web实际exec id -u均为10001的断言，完整通过，日志/tmp/personalos-p8-compose-actual-9.log。38项迁移脚本单测通过，git diff --check通过。

## T801c 实际监控与知识指标

补齐原仪表盘依赖的知识指标：jobs.attempts使用outcome、rag.results使用status、rag.tokens使用kind；timer导出秒单位count/sum/窗口max，recall与rag.stage按原monitoring配置导出固定桶。召回真实接线query_embedding/opensearch_hybrid/candidate_hydration/postgres_vector_path。计时负值忽略、快照深拷贝，数据库查询在总指标锁之外，单次只读快照最多1秒；失败输出NaN而不是伪造零积压。

`KnowledgeMetricsOracle.java`实际执行固定Java依赖的PrometheusMeterRegistry，记录源码与依赖SHA-256。Go逐项比较同输入的所有实际样本，包括标签、单位和桶；默认复验不重写fixture。定向race通过，日志`/tmp/personalos-p8-knowledge-metrics-2.log`；真实TLS PostgreSQL验证pending/future/running/failed/infinity谓词、最旧age、真实PG召回计时和取消查询NaN，日志`/tmp/personalos-p8-knowledge-metrics-db.log`。

独立Go监控资产保留原20面板；Hikari/JVM改为明确标注的Go连接池使用率/已分配堆，使用新datasource/dashboard UID，不伪造JVM指标。combined与distributed配置分开。Prometheus/Grafana非root、只读根、私有网络、独立卷；Grafana随机回环端口和随机合成密码。

`python3 scripts/migration/run_go_compose_tests.py --monitoring`两轮完整通过；第二轮同时执行promtool config/rules和六条告警的pending/firing/recovery单元测试。真实Prometheus采集UP=1及Go/知识库样本、Grafana健康与20面板provisioning、原Compose业务链全部通过，仅清理本次随机项目与卷。日志`/tmp/personalos-p8-monitoring-actual.log`、`/tmp/personalos-p8-monitoring-actual-2.log`。另独立promtool规则测试通过，39项Python测试通过。

边界：规则测试用合成时间序列，不是实际故障持续五分钟的告警演练；尚无Alertmanager通知投递验收。distributed DNS采集与本地发现容器权限仍待后续验证，不据此勾选T801父项。P6四设施全回归通过且清理，日志`/tmp/personalos-p8-facilities-p6.log`。Redis限流前两轮并发失败保留记录，隔离定向重跑通过；未放宽150ms边界，其余Redis回归继续单独追踪。

## T802a 原前端浏览器与取消差分

监控批次已提交`dfd1eaf`。原suite新一轮80通过/1失败/1跳过/2观察；唯一预算失败在固定Java同现。原E2E取消曾SKIP，实际差分定位并修复legacy客户端取消误存半截助手消息；明确HTTP关闭与上游断连两端均PASS，Go定向真实TLS库race覆盖双流心跳、Run读者断连与恢复（7.312s）。持久Run断连不取消后台任务的策略保持。

真实Chrome验证登录/任务CRUD/聊天SSE/附件/知识后台索引/导出，10页三宽度共30截图无横向溢出或控制台错误，另3张工作流截图。抽看桌面对话、手机对话及知识页。fixture最初不足350字符的索引失败如实保留，修正测试文本后通过。详情与复验命令见[p8-e2e/README.md](p8-e2e/README.md)。T802父项仍开放，不把同基线预算失败或saveMemory跳过当通过。

## T804a 真实设施与隔离备份回归

原先`--backup`直接把合成备份种子写入personalos_go_test，导致P5全包读取到额外的恢复记忆/任务/提醒。现先从刚迁移的空库模板创建personalos_go_backup_source，仅在独立源库填种子/pg_dump，再恢复personalos_go_restore；主业务测试库保持干净。单测断言种子与dump绝不选择主测试库，实际全套重跑通过，未修改业务过滤或断言。

`python3 scripts/migration/run_go_redis_tests.py --tls --backup --package app --package command --package model --package knowledge`：全部退出0，app273.588s、command1.746s、model1.967s、knowledge4.115s；五角色独立OS进程共享会话/预算、CSRF/注销、故障启动清理、SIGTERM与数据库连接归零通过。日志`/tmp/personalos-p8-redis-fixed-backup.log`；首轮备份污染失败保留`/tmp/personalos-p8-redis-remaining.log`。redis/auth早前真实TLS通过；ratelimit隔离重跑32/128并发通过（2.697s），150ms期限不变，增加仅输出deadline/cancel/network布尔值和原因类型的诊断，不输出凭据/原始网络错误。

此前P8真实TLS PostgreSQL全部包与命令进程smoke通过，日志`/tmp/personalos-p8-postgres-all.log`；MinIO实际S3生命周期通过，日志`/tmp/personalos-p8-s3.log`；P6四设施回归通过，日志`/tmp/personalos-p8-facilities-p6.log`。所有本次容器清理，仅合成数据。性能/生产召回质量/真实用户备份和完整切换仍未验收。

Chrome真实Push复验中，应用端实际加密/VAPID请求被FCM接受，专用Go测试通过（0.64s），但Chrome Service Worker未在15秒内回传收到通知。runner按预期失败，日志`/tmp/personalos-p8-browser-push.log`，不能把FCM接受当最终浏览器送达，也未延长期限凑绿。T802通知子项保持开放。

## T800c 分布式Compose与四角色采集

`python3 scripts/migration/run_go_compose_tests.py --monitoring --distributed`第三轮完整通过，日志`/tmp/personalos-p8-distributed-actual-3.log`。新建Redis TLS仅私有网络、无宿主端口，API共享会话在进程重启后保持有效；实际Cookie为SESSION、HttpOnly、SameSite=Lax。index/review/delivery真实业务端点404，四角色UID10001与管理health通过；Prometheus真实发现四个UP=1目标，六告警规则和20面板provisioning、原Caddy业务链及四角色SIGTERM退出0全部通过。

第二轮可读日志确认首轮实际缺陷：API共享会话模式明确拒绝Servlet专属Cookie覆盖；基础Compose冗余设置http-only/same-site使API退出。移除两项，保留共享/本地默认HttpOnly/Lax并在真实登录响应断言。没有绕过配置验证器。日志按服务单独有界保留，避免Grafana输出挤掉故障原因。首两轮失败记录保留。

固定Grafana13.2.2镜像的defaults.ini确认默认preinstall/自动更新开启。候选显式设置GF_PLUGINS_PREINSTALL_DISABLED=true及GF_PLUGINS_PREINSTALL_AUTO_UPDATE=false，实际日志无Installing/Updating plugin；避免未固定的插件下载及只读bundled目录更新失败。只检查升级开关不足以禁用该行为。测试项目/卷全部清理，生产TLS/prod拒绝与readyz503边界保持。

## T801d 本地Worker的容器监控发现

新增显式公开发现导出：与私有状态目录分离，拒绝符号链接/祖先或后代重叠/非当前用户或错误权限，0755目录与0644原子JSON仅有地址/role。私有目录0700、PID/日志/原发现0600保持；每次启停/targets须传相同导出参数，异常退出后用targets刷新。默认原生监控走回环，Docker Desktop可显式使用host.docker.internal；不宣称Linux同样可达宿主回环。

`python3 scripts/migration/run_go_local_tests.py --monitoring`实际通过：新TLS PostgreSQL下P8 race测试5.211s，五种本地角色启停与重复拒绝、三个并行Worker、UID65534只读Prometheus挂载公开file_sd并真实采集三个UP=1且role一致。停止后发现清空，原格式备份/隔离恢复/附件与Push密钥权限验证通过，仅清理本次资源。日志`/tmp/personalos-p8-local-monitor.log`。六项runtime脚本单测覆盖最小导出/权限/陈旧清空与危险路径拒绝。

## T803a 确认事务补验

审读固定Java ConfirmationTransactionTest八声明后发现，已有Go内存失败状态断言与真实库并发成功不足以证明失败标记独立提交。新增TestPostgresP8ConfirmationTransactionOutcomes：处理器运行中通过第二个池读取已提交CONFIRMED及resolved_at；业务错误/普通错误最终FAILED、缺失目标CANCELLED且无执行、成功影响3行、重复终态令牌均不再调用处理器；独立过期SQL压到过去后抛错仍保留EXPIRED与解决时间。只用本轮合成ID，没有全表清理。

`python3 scripts/migration/run_go_postgres_tests.py --package confirmation --tls --run '^TestPostgresP8'`真实TLS库race通过（1.269s），日志`/tmp/personalos-p8-confirmation-transaction.log`。原八声明分别由同名success/business/runtime/missing子用例及末尾expiry断言覆盖；原Java和生产确认算法未修改。其余声明/来源职责继续核查，T803父项未完成。
