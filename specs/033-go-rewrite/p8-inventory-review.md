# P8 全清单职责处置

固定Java基线：`cb5d694d62fa93742b5789b97273e76d931ce335`。本记录区分代码职责移植、原样保留与尚未通过的真实验收，不能据清单条数宣布等价或发布完成。

逐文件SHA-256、处置类型与状态见[p8-inventory-disposition.json](p8-inventory-disposition.json)，原声明逐项测试见[p8-test-mapping.md](p8-test-mapping.md)。前阶段实现/差分证据保留；新增源代码职责不是按目录统一通过，而是在`go_p8_inventory_progress.py`显式列出原文件、Go实现、测试与架构对应说明。任何未映射Java代码或运行钩子使审计失败。

## 剩余运行职责

| 原职责 | Go承担方式与证据 |
| --- | --- |
| Spring启动/配置扫描 | 显式CLI/Foundation；原ConfigData/Binder默认与profile对照、各角色构建/资源清理 |
| ContextProperties/窗口注册 | 类型化配置、当前生效档案CHAT窗口查询与nil/异常回落；显式注入Budget，不造Spring注册器 |
| Auth/Security/SharedSession装配 | BCrypt/失败锁定、会话旋转与CSRF；共享Redis/TLS/ACL/固定Cookie策略、跨OS与故障恢复 |
| 独立Monitoring链 | 管理监听真实health/prometheus，应用/环境路径拒绝，业务监听不泄露指标 |
| ContextTask/MDC/Reactor钩子 | 有界1/2工作单元+32队列、满丢弃；context只传requestId，执行器独立生命周期，不模仿线程本地容器 |
| Dotenv/生产校验/限流 | 显式文件优先级及测试禁用、原安全校验；限流参数/平滑复充/数据库时钟/Redis TIME与150ms，不回退 |
| 确认DTO/状态/仓储/Token | 原动作枚举与DTO、一次令牌、独立事务CAS、失败/取消/过期真实双池观察 |
| 幂等DTO/结果/审计仓储 | 固定摘要、持久占位/重放、审计过滤在分页前、R-053未知TOOL保护 |
| PlainText/Texts/JSON | 实际Poppler、媒体/扩展名与截断；Java strip/UTF-16/错误文案；稳定JSON和Java摘要真实回放 |
| Worker/RequestId/数据库关闭钩子 | 依赖前角色隔离、context/响应/日志一致、停止续约及仅释放本实例租约 |
| 任务DTO/枚举/载荷 | 原字段绑定/省略、完成/重开不变量、分页/全局统计、标签批取与确认前无写 |
| Java测试辅助类型 | 确定向量/脚本模型/HTTP客户端用httptest与显式依赖；Instants职责由迁移比较器承担；package-info按领域依赖保留 |

剩余两路由（错误入口与公开业务health）和14个装配/配置/停止钩子逐项补入运行证据。所有104路由、27工具、95钩子、1119测试声明均有implemented/verified处置；这不是把发布门禁改为verified。

## 原样保留的资产

| 范围 | 处置及实际边界 |
| --- | --- |
| 42条SQL | 逐文件SHA冻结；真实Flyway锁竞争/validate/零重复、空库/合成升级/Java→Go→Java回退；Go只验证历史 |
| 原Java构建/资源 | Maven/Java继续可用，1137测试及JaCoCo通过；Go模块/镜像/配置与候选CI并行，原配置不改 |
| 前端源码/测试/构建 | SHA不变；lint/format/type-check/1151逻辑/串行195组件/build、真实Chrome10页三宽度及流程；真实Push另列未通过 |
| 原Compose/Caddy/监控 | 保留Java资产；Go另建combined/distributed、Flyway/TLS数据库、非root/只读与Caddy；Prometheus/Grafana/六告警规则/本地发现真实验证 |
| 本地运维/备份/Push密钥/演示配方 | Go独立状态和显式目标；原归档格式与新随机库恢复/回退、0600/拒绝覆盖实测。演示七组配方在新随机TLS空库/认证Go真实回读通过，禁止原main/reset，不操作用户运行数据 |
| 业务规格/历史报告/仓库模板 | 原文与SHA保留；当前Go操作及差异在新增docs/specs中，不把旧Java历史报告当Go实测 |

## 保持开放的处置

23个文件仍为in_progress：3个生产配置、4个真实Push相关源码/测试、原E2E入口，以及15个原在线语料/模型评估集合文件。原演示配方隔离适配已完成，证据见[p8-demo-seed.json](p8-demo-seed.json)。数量只帮助定位，不能替代逐项说明。

- 候选Caddy已在新随机项目用私有CA实际验证HTTPS/自动续期/重启保留，并修复跳转丢失外部端口，见[p8-https.json](p8-https.json)。公网ACME/DNS/真实生产证书续期及发布批准仍无实测材料；prod拒绝、readyz503继续保持。
- 真实FCM接受后15秒内无Service Worker push事件；synthetic CDP控制通过只说明本地通知链，不能升级真实投递状态。
- 原演示脚本main固定连接现有容器、默认清空全部业务表，仍禁止直接运行。Go适配仅加载原七组配方并替换IO边界；原main/reset从未调用，已验证隔离回读不等于对用户库执行授权。
- 原E2E最新保持81 PASS/1与固定Java同FAIL/1原case SKIP/1 NOTE，不修改原断言凑绿。
- 原在线评估含外部采集、全库重建与真实模型；现26篇公开正文已完整取得并执行129原查询，历史快照身份/人工事实/完整多阶段与Agent/后台审查仍开放。18组受控召回或只读/纯单测不能解除整个集合的在线评估职责。

原四个检索工具的固定哈希/算法已通过独立Go适配器实测：26份明确合成材料、原129条查询（24 term/22 natural/83 cross）和50次独立pure-vector噪声请求，见[p8-eval-tooling.json](p8-eval-tooling.json)。未调用原重建main/delete_all；新随机TLS库的认证/CSRF、全部命中归属与去重校验、停机连接归零通过。预检未提供全文目录时明确BLOCKED；不扫描默认/tmp、不读取用户模型凭据。合成分数不能证明原文章/真实模型质量，因此四文件与其余在线集合继续in_progress。

用户后续明确授权真实模型验收后，独立白名单读取根`.env`模型配置；实际`qwen3.8-flash`和`qwen3.7-text-embedding-flash`完成Java/Go各新TLS库16篇/142向量/84原查询/2传统知识问答引用及50噪声请求。最终双端报告`p8-eval-real-20261104-222729.json`、质量复核`p8-real-model-review.json`。已修复Go长文分批和OpenAI默认EMBED元数据遗漏，预期模型输入SHA由真实固定Java格式oracle生成；原文/引用/冻结Java保持。两端R@5=100%，严格MRR Go0.937500/Java0.943452，唯一严格名次差在原0.007判平内；判平MRR同0.946429，但严格退化仍独立记录。原工具/算法/阈值不改，缺10篇/45查询、历史认证、人工事实与Agent/多阶段RAG验收不由此替代，15个在线集合继续in_progress。

真实用户备份、外部副作用核对、通知最终送达、生产容量/峰值与用户验收仍是独立门禁。五release_gates状态与证据保持原样，2438项均未自动升级verified；check-complete仍应失败。T803父项保留开放，不能把“审计已记录”误写为“未完成职责已通过”。

后续真实多阶段单题报告`p8-eval-real-20261104-224539.json`在两端各新TLS库完成原BRIN问题：142向量/实际模型输入SHA、READY/四阶段OK/零warning、各四次前台聊天及持久引用均通过。首轮后台入库审查污染调用计数的失败保留；只在隔离子进程关闭该后台审查，未改应用默认值。明确其余83道材料齐备题未执行、45题缺材料；不替代完整多阶段、后台审查或人工事实验收。15个在线集合和五门禁状态不变。

补充真实无证据负例报告`p8-eval-real-20261104-230130.json`（Go）与`p8-eval-real-20261104-230340.json`（独立Java）均FAIL/退出1：均四阶段OK、零warning、三次前台chat、answered=false且无引用/命中，但CLARIFY不满足预设严格INSUFFICIENT。没有重试Go或放宽判断；相同安全澄清不等于负例验收通过，也不足以认定业务缺陷。问题不是原标注，明确原查询执行0条、84条齐备题未执行、45条缺材料；报告不能送入全检索质量比较。新增工具及120项脚本回归只补齐诊断，15个在线集合和五门禁继续开放。

最新材料与执行范围：`p8-corpus-complete-20261104-231937.json`记录26篇/210264字符已齐、129题材料覆盖零排除；最后四篇为正常浏览器实际呈现DOM，经原提取器与来源收据验证，不冒充原始HTTP或历史快照。`p8-eval-real-20261104-232203.json`实际完整执行两端各129原查询/241向量输入核验/两次问答引用/50噪声请求，退出0；R@5同96.90%、各四miss，严格MRR Go0.901421/Java0.900775，判平MRR Go0.907623/Java0.908915，质量复核新文件明确不批准。`p8-eval-real-20261104-232433.json`完整库BRIN单题多阶段通过，另128题未执行、零缺材料。旧“缺10篇/45题”属于当时历史范围，不再作为当前阻塞；15个在线集合及五门禁仍因完整语义/Agent/后台审查等职责开放，未自动升级verified。

补充`p8-eval-paired-20261104-233557.json`使用新TLS库中Java一次真实上传的相同241向量索引和129个真实float32查询向量，Go依序精确回放、不作新上游调用。129题完整标题顺序/严格/判平名次全一致，文档/向量/Flyway全行哈希不变；该轮R@5同96.124%、各五miss。164 embedding/零chat/零失败重试，本次进程和容器清理。该结果只支持同输入检索定位，既有独立调用差异/四miss保持，不冒充完整RAG/事实验收或解除上述15个集合/五门禁。

原合成政策三场景及两来源后台审查现已双端真实模型执行完成：`p8-live-conflicts-{go-20261104-234459,java-20261104-234606}.json`均匹配CONFLICT/CONFLICT/CLARIFY、不回答/双来源，四阶段全OK、每题三chat及持久分块引用核验，各11 embedding/9 chat；原Go缺搜索404失败`p8-live-conflicts-go-20261104-234305.json`保留，补齐本次真实OpenSearch而非改断言。`p8-live-ingestion-review-{go-20261104-234857,java-20261104-235054}.json`各4 embedding/2 chat、零手工重试，在原110秒内两来源CHECKED_CANDIDATES且有OPEN冲突；实际Go1/Java2数量差异保留。仅重启本次候选，停机连接及文档/向量/来源/审查任务清理归零，两临时容器清理。不得继续把这两种原场景说成完全未执行；18条项目知识RAG场景、129条文章剩余128题多阶段、完整Agent/真实存量事实及发布仍开放，15个集合及五门禁不自动升级。
