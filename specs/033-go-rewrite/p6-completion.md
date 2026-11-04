# P6 知识、RAG 与后台运行验收

状态：2026-11-04 已完成 T600–T609。以下保留各批次当时的验证记录；最终结论见文末。P7/P8 与五个发布门禁继续开放。

## 任务验收矩阵

| 任务 | 已交付职责 | 主要证据（相对 backend-go/internal/business/knowledge） |
| --- | --- | --- |
| T600 | 文件名/格式/大小/短文、文本/PDF/DOCX/图片、解析许可 | reader.go、reader_test.go、gateway_test.go；backend-go/THIRD_PARTY_NOTICES.md |
| T601 | 结构切分、原向量schema/索引、文档租约及原子替换/删除 | splitter_test.go、store_integration_test.go、schema_integration_test.go |
| T602 | 持久索引队列、幂等/容量/续租/清理、最终提交隔离 | jobs.go、store_integration_test.go、process_integration_test.go |
| T603 | 向量/关键词、IDF/RRF、阈值/转义、可选外部候选 | retrieval_integration_test.go、oracle_integration_test.go、facilities_integration_test.go |
| T604 | 投影Outbox/重建/版本、持久确认通知/重连/轮询 | search_integration_test.go、facilities_integration_test.go |
| T605 | 共用RAG、预算/重排/证据、保守问答与聊天引用 | rag_test.go、gateway_test.go、qa.go；backend-go/internal/app/foundation_p6_integration_test.go |
| T606 | 空间/来源/版本、增量review、原文核验/人工决议与失效 | governance_integration_test.go、review.go |
| T607 | 独立候选缓存、TTL/指纹/generation、故障回源 | cache_integration_test.go；backend-go/internal/app/foundation_p6_integration_test.go |
| T608 | 真实四设施、双API/五角色、失联/失效/乱序与索引强杀恢复 | facilities_integration_test.go、process_integration_test.go；backend-go/internal/app/foundation_p6_integration_test.go |
| T609 | 既有语料适配、Java契约、召回/引用/延迟/队列与断言台账 | scripts/migration/compare_go_p6.py、p6-eval-results.json、p6-test-mapping.md |

知识职责已交付 57 个主源码、24 个测试类/115 个声明、19 个HTTP路由、1 个工具和31个运行钩子，共247项记录为 implemented，附实际Go实现/断言。另两个知识模块主源码跨模块搜索/导出贡献器仍为 not_started，分别归 P7/T703、T704；没有把 P6 完成等同于全量 verified。20 项 P6 配置调用处置为 wired，78 项总台账默认比较通过。

## 第一批：文件解析与结构切分

提交：`849c625`。

新增 knowledge Reader 与 Markdown 切分器。UTF-8 严格校验、BOM、300 UTF-16 文件名/保扩展名、目录移除、大小/短文拒收、图片 MIME/无文本/上游错误均覆盖。DOCX 使用标准库 archive/zip 与 encoding/xml，读取正文段落和表格单元格，保留顺序；拒绝 ZIP bomb、超深 XML 与损坏输入。PDF 使用独立 Poppler pdfinfo/pdftotext，校验完整页数（最多 500 页），不截掉第 501 页；10 秒 context/进程退出和 64 MiB 输出上限，不构造 shell。无文本、加密和损坏错误保留分类。

Markdown 沿用原标题栈、代码围栏、120 字符章节前缀、段落累积、70%自然断点、重叠和 1.5 倍短块合并界限；另保证硬切不会制造半个 Unicode 码点。

实际执行：knowledge race 测试通过（6 个测试，含真实现场生成 PDF）；首批 25 个 Go 包全量 race 和固定基线 check 通过。模型调用装配、数据库写入、任务队列与真实 Java 差分待后续批次。

## 第二批：向量原子写入与持久索引任务

Store 使用既有Pool/Coordinator，保留原schema、vector(1024)、JSON元数据和数据库触发器。所有模型调用在事务外完成；替换/删除共用document租约，最终写事务检查数据库所有者。索引任务保留20个排队上限、摘要幂等、60秒租约/10秒续租、3次重试、payload清理、7日保留；结果与文档/向量在同一事务提交，并同时检查任务token与文档租约。

实际执行：5个真实TLS PostgreSQL集成测试与6个单测全通过race，涵盖16路抢占、24路API提交、失效重领/最终fencing、失败不破坏旧代和完整删除。HTTP/角色装配及Java全部来源断言尚在后续批次推进。

## 第三批：混合召回与候选缓存

新增向量阈值、英文标识符与中文候选补召回、按全量命中数计算 IDF、加权 RRF、文档去重和 LIKE 字面量转义。可选外部搜索仅返回候选 ID，正文重新查询 PostgreSQL；投影积压或外部失败退回本地召回。Redis 候选缓存使用专用客户端、namespace/config/query 哈希、20 秒 TTL、256 KiB 限制与数据库 generation；事务/关闭时绕过，故障直接执行召回。

实际执行：独立 TLS PostgreSQL/pgvector + TLS Redis，重放42原迁移后17项知识包测试全部通过 race。覆盖中文低相似度补召回、八文档窗口统计先于截断、失效外部ID、缓存隔离/TTL/来源变更/并发失效/关闭和Redis故障。发现并修复真实 PostgreSQL 的 ESCAPE 字面量错误。完整模块装配与端到端验收仍待后续批次。

## 第四批：搜索投影与持久唤醒

OpenSearch 使用有界 REST、CJK/HNSW 映射和现有维度验证，external_gte 版本写入、删除及冲突幂等。投影沿用原数据库 Outbox，短事务领取、60秒失效恢复、revision/token 条件确认及指数退避；重建只入队。RabbitMQ 队列持久化、消息 TTL/上限、persistent 发布与 publisher confirm；通知只唤醒执行器，数据库任务仍独立领取和轮询，信号按 revision 条件延迟重发。

实际执行 `python3 scripts/migration/run_go_p6_tests.py`：四个独立 tmpfs 容器，PostgreSQL/Redis 使用 TLS，RabbitMQ/OpenSearch 使用随机回环端口且固定本地镜像摘要。真实 OpenSearch 验证权威回表/旧版本拒覆盖/删除；真实 RabbitMQ 验证确认发布、重复/非法通知不消费业务任务、消费者重连。投影新 revision 不被旧确认删除、失败退避、重建、信号旧确认不推迟新 revision 也通过 race。首次测试容器启动配置已修复，成功后全部临时容器清理；API/五角色装配仍在下一批。

## 第五批：RAG、来源治理与增量审查

共用 KnowledgeSearchPort 与多阶段管线加入查询改写、候选 RRF、来源多样性、结构化/可选 Cohere 和 DashScope 重排、逐条证据核验及总预算。CONFLICT/CLARIFY/UNVERIFIED 均保留证据并阻止确定作答；topK=1 不能丢掉冲突一方。问答引用由服务端按实际编号生成，过滤越界/重复，未标注时返回实际上下文来源。

来源治理保留空间、元数据、生效期、revision、人工冲突处理与审计。冲突保存再次回数据库核对 revision 与连续摘录；已保存分歧不因模型漏报消失。增量 review 角色逐片段领取，60秒租约、revision/token/源锁最终 fencing，失败三次暂停、人工重排和来源变更恢复。

实际执行知识包单测与独立 TLS PostgreSQL race 全通过，新增覆盖 READY/CONFLICT/CLARIFY/UNVERIFIED、不同有效期澄清、编号引用、两种重排协议非法排序拒收、元数据/决策/audit/stale、伪造摘录拒收、16路review竞争/过期重领/旧revision拒提交、PARTIAL→CHECKED_CANDIDATES和失败上限。HTTP、聊天与五角色装配仍待下一批；不把当前管线单测冒充真实外部模型质量评测。

## 第六批：显式模块装配、HTTP 与聊天

`--foundation --p6` 显式启用 P2–P6。知识模块保留五角色边界，索引/审查使用共享模型预算与 API 预留；Rabbit 消息只唤醒，各角色独立轮询 PostgreSQL。候选缓存必须独立客户端，资源由调用方拥有；可选设施关闭时不连接。退出取消后台任务、排空辅助模型调用并等待任务退出，再关闭数据库。

文档、任务、检索、问答、空间、来源、冲突与可选搜索管理均进入既有认证/CSRF/限流/媒体链。聊天和只读 searchKnowledge 工具共用 KnowledgeSearchPort；保守状态发送 citations/rag/delta/done、保存消息与引用，并跳过确定回答和自动记忆。

实际执行 `python3 scripts/migration/run_go_postgres_tests.py --package app --tls --run '^TestFoundationP6'`，两项真实 TLS PostgreSQL/race 装配测试通过，覆盖 API 不抢索引工作、独立 index 无 Rabbit 轮询、双 API、worker 关闭业务入口、CRUD/分页/参数校验、认证/CSRF/治理与保守聊天引用。完整四设施角色联动与 Java 差分仍在最后验收批次。

## 第七批：固定 Java 契约差分

提交：`35bf3f7`。

`java_p6_fixture.py` 从固定提交 `cb5d694d62fa93742b5789b97273e76d931ce335` 提取八个 Java 源文件并记录 SHA-256，以真实 Java 服务生成文件名、152 条查询关键词、15 组结构切分、四种引用编号与只读工具 schema 的契约。152 条包含既有历史查询和当前 live corpus 的问题；Java 与 Go 逐项相等。工具注册消费 Spring ToolCallbacks 生成的原始描述/参数 schema，避免手写元数据漂移。

Java 召回另有 18 组现有 `rag-live-cases.json` 的仓库原文摘录；基线服务使用受控的向量与关键词通道，Go 使用真实 pgvector/SQL，结果对照留在最终评估报告。本批不把受控分数解释成模型语义质量，也不把 Java mock 通道耗时与 Go 数据库耗时当作端到端性能对比。

复验：`python3 scripts/migration/java_p6_fixture.py`、`cd backend-go && go test -race ./internal/business/knowledge ./internal/business/agent`。生成器需要已有 Maven 编译输出和 Surefire classpath；不启动生产服务，不读取应用密钥。

## 第八批：完整设施与崩溃回归

提交：`3944d29`。

`python3 scripts/migration/compare_go_p6.py` 已通过。它复用既有 129 条历史查询做关键词契约，并从 `rag-live-cases.json` 取 18 组固定仓库原文，运行真实 TLS PostgreSQL/pgvector、TLS Redis、RabbitMQ 和 OpenSearch。机器可读结果见 `p6-eval-results.json`，包含输入哈希、逐项通过的 Go 测试、18/18 召回与引用相等、受控来源 R@1/引用 precision、逐查询 SQL 耗时，以及索引/审查/投影队列收敛时间。Java 通道的耗时单列在 `p6-java-eval.json`，两种耗时口径不同。

双 API 与独立 index/review/delivery 实际共享持久任务；候选缓存命中后关闭该专用客户端仍可回源 PostgreSQL。combined 在不可连接的 RabbitMQ 下仍轮询完成；关闭 OpenSearch/reranker/candidate cache 时不解析其占位端点或凭据。补齐 `app.knowledge.jobs.enabled`，关闭调度仍可接收任务，再由独立 index 领取。

索引子进程在真实嵌入阶段被 SIGKILL，确认没有文档/向量部分提交、任务负载仍在；仅前移测试库中的 60/120 秒租约到期时间，再启动全新 OS 进程完成一次持久提交。旧 token 拒写另有多连接断言。review 与 projection 的过期/revision/token 对照通过数据库控制边界，不冒充所有角色都经历 OS 强杀。

增加治理扩展查询失败时的 UNVERIFIED/保留双方证据/不再批准模型结论、查询改写救回、非法重排回退，以及真实 EXPLAIN 使用 `idx_vector_store_document_id`、事务内删除该索引后 Seq Scan 的反证。去索引事务最终回滚，保留原迁移结构。原 Java 的 `wordStartPattern` 没有用于生产批量查询；Go 保留原实际 ILIKE 子串查询与转义，不新增未使用的正则 API。

本轮完整 Java 首次执行 1,137 个用例，0 failure、2 error（OpenSearch 启动超时、Redis 首次共享缓存未命中）；两类随后单独 `verify` 全通过，原 JaCoCo 门禁通过。最终以 `-Dspring.test.context.cache.maxSize=2` 重跑全量，1,137 个测试零失败/错误/跳过，JaCoCo 门禁通过，BUILD SUCCESS；该参数只控制测试上下文缓存，不修改原源码。前端类型检查/构建、Go 全量 race、integration vet/build 与模块校验通过。

## 第九批：稳定契约复验

提交：`6a1415d`。Java Map.of 在不同 JVM 运行时的键顺序不固定；生成器按 JSON 内容判断契约是否一致，等价时保留原文件字节。连续两次独立 JVM 复验通过，最终再完整运行四设施评估，报告输入哈希与文件一致。运行耗时单独记录，不冻结为契约。

## 第十批：工具与台账边界

提交：`3433374`。工具ID不含文件路径，台账更新改由inventory来源路径定位，247项均有证据；回归确认其余阶段和五个门禁状态不变。`TestKnowledgeToolReadOnlyAuditAndGuardedResults` 验证Java工具schema、READY/INSUFFICIENT/CONFLICT/CLARIFY/UNVERIFIED、重复只读调用分别执行并审计、不产生写入/确认；`TestKnowledgeToolValidationFailureAndCancellation` 验证空白/非法topK、默认limit透传、失败脱敏与取消不执行。agent包race与vet通过。

## 最终验证

| 命令或范围 | 实际结果 |
| --- | --- |
| `cd backend-go && go test -race -count=1 -timeout=10m ./...` | 全包通过；最后增加的P6测试另经下述完整设施race执行 |
| `cd backend-go && go test -race -count=1 ./internal/business/agent` | 最后补充的知识工具回归及既有agent测试全部通过；定向vet通过 |
| `cd backend-go && go vet -tags=integration ./... && go build ./... && go mod verify` | 通过 |
| `python3 scripts/migration/compare_go_p6.py` | 44个顶层Go测试通过；18/18召回有序结果与引用相等，受控R@1/引用precision为1，四设施角色回归通过；输入哈希一致 |
| `python3 scripts/migration/java_p6_fixture.py` | 152查询、15切分、18受控召回契约通过 |
| `cd backend && ./mvnw -o -B -Dspring.test.context.cache.maxSize=2 verify` | 1,137测试零失败/错误/跳过，JaCoCo通过 |
| `cd frontend && npm run type-check && npm run build` | 通过，无前端源码修改 |
| `python3 -m unittest discover -s scripts/migration -p 'test_*.py'` | 19测试通过，含无路径工具ID更新及后续阶段/门禁保留 |
| `python3 scripts/migration/go_config_disposition.py` | 78处配置默认比较通过 |
| `python3 scripts/migration/go_inventory.py check` | 固定1093文件/104路由/27工具/95钩子/1119测试声明通过 |
| `python3 scripts/migration/go_inventory.py check-complete` | 预期退出1，2438项未verified、五发布门禁未验收；保持严格拒绝 |

机器结果：[p6-eval-results.json](p6-eval-results.json)、[p6-java-eval.json](p6-java-eval.json)。原断言：[p6-test-mapping.md](p6-test-mapping.md)。本地完整日志：`/tmp/personalos-p6-evaluation-final.log`、`/tmp/personalos-p6-go-race.log`、`/tmp/personalos-p6-java-final.log`、`/tmp/personalos-p6-frontend-types.log`、`/tmp/personalos-p6-frontend-build.log`。

边界：历史129查询的26篇完整文章不在仓库，只复用查询作关键词契约；18组仓库原文和受控通道证明契约，不复现旧模型的语义质量分数，不把Java mock耗时与Go SQL耗时作端到端性能比较。真实OS强杀仅覆盖索引，review/projection通过租约与revision故障边界验证。所有设施为runner创建的临时资源，结束后清理；没有连接生产数据、修改Java/前端/SQL/部署基线、推送、部署或启动P7。回环监听、prod拒绝及readyz503守卫保留。

已按可审查批次提交：`849c625`、`375e964`、`2b7f3b8`、`08fec91`、`a90a702`、`2e2960b`、`35bf3f7`、`3944d29`、`6a1415d`、`3433374`；任务/配置/证据台账与本报告另作收口提交。
