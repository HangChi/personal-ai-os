# P8 全量验收与运维进度

2026-11-04：P8实施中，T800–T806及T808父项尚未收口，T807文档已完成。固定Java基线继续为`cb5d694d62fa93742b5789b97273e76d931ce335`。五个发布门禁未verified；frontend_e2e已开始并保留未通过记录，所有清单条目不自动升级verified。现有Java部署与SQL保留；没有自动推送、合并、生产部署或删除Java目录。

## 当前未完成项与进入条件

本机隔离验证只覆盖表中已经具备的条件；缺少真实输入、交付结果或人工审查的项目继续开放。不得用合成数据、原样哈希或与Java相同的失败将发布门禁改为verified。

2026-11-04最新：26篇公开正文已补齐，原129条查询已用获准真实模型在Java/Go完整执行，零缺材料排除；每端241向量、两次传统问答/引用、50次噪声请求，以及完整库下一道BRIN多阶段探针均执行完毕。质量复核仍未批准：两端各四条漏召回，严格与原判平指标不一致，另128题多阶段未跑。下文16篇/84题、22篇/111题章节是历史阶段，旧报告保持原样；公开正文缺失已不再是当前阻塞。

新增同输入定位对照已完成：在同一份新隔离索引和129个真实查询向量下，两端所有标题顺序/严格/判平名次一致，索引不变；该轮共同五miss。它只隔离该次检索实现，Go向量为精确回放而非重新调用上游，不推翻前次独立调用的四miss/指标差异，也不批准事实质量或完整多阶段。

| 项目 | 当前证据与下一步所需条件 |
| --- | --- |
| T800 生产入口 | 私有CA/续期/重启已通过；还需受控公网域名、ACME/DNS挑战与实际续期证据，以及明确生产部署批准。prod拒绝/readyz503保留。 |
| T801 告警通知 | 独立Alertmanager和本地HTTP接收端触发/重试/恢复已验证；真实渠道还需显式提供测试收件配置、发送授权与最终接收确认。六条规则单测不等于六种持续真实故障。 |
| T801/T804 原评估 | 已完成26篇/129原查询双端真实模型全量执行，每端241向量输入核对、两次传统问答/引用及50噪声请求；R@5同96.90%、各四条漏召回，严格MRR Go0.901421/Java0.900775，原判平MRR Go0.907623/Java0.908915。完整库BRIN单题多阶段通过，另128题未跑。旧无证据负例CLARIFY未满足严格INSUFFICIENT仍FAIL。历史快照身份、人工事实审查、完整多阶段/Agent/后台审查及指标差异未验收，不解除质量门禁。 |
| T802 浏览器验收 | 原E2E两端全84项同为81通过/1预算失败/1 saveMemory跳过/1工具超限观察；独立预算边界确认长当前输入本地拒绝、旧历史可裁剪且不删库、恢复可继续，原case仍FAIL。仍需原跳过/观察及预算契约审查，真实Push未送达，保留原30秒/15秒期限。 |
| T804/T805 存量与切换 | 合成Java备份→Go→Java回退已通过；还需明确授权的真实备份副本及密钥/附件/S3恢复材料、目标负载与容量标准、待办及外部副作用清单。只在新隔离目标恢复，不能直接操作当前服务或重放未知TOOL。 |
| T803/T806/T808 证据与批准 | 全清单处置已记录；五release_gates仍需逐项人工核验和用户验收。Java归档须在验收后另作明确决定；不自动推送、合并、生产部署或删除原实现。 |

## T801o 同一索引与真实查询向量的完整检索定位

`eb592a0`先提交独立工具和五项回放边界测试；此前定向红测为模块未实现，绿测五项通过，完整132项Python测试通过（4.482s，`/tmp/personalos-p8-python-with-paired.log`）。`run_go_paired_eval_tests.py`只接受完整26篇原清单，在本次新随机TLS库/原42迁移/认证CSRF环境中由固定Java一次真实上传，241向量及预期Java格式输入SHA核验；Java执行129原查询并记录真实base64 float32响应，随后停机连接归零，Go读取同一索引并依序回放相同模型/输入/维度的向量。未知输入/顺序/编码/路由均拒绝，不向上游补取或重试。

实际首轮退出0，报告[`p8-eval-paired-20261104-233557.json`](p8-eval-paired-20261104-233557.json)、日志`/tmp/personalos-p8-real-paired-1.log`，`d3aa181`及时提交报告。两端129题全部标题顺序、严格与原判平名次一致，所有delta为0；文档/向量/Flyway历史全行哈希前后相同，两次停机连接归零，本次容器清理。实际164 embedding/0 chat、287632输入字符、147378 total tokens，零失败/重试；129次Go查询向量全部来自原真实回放，Go零新上游调用，费用上限仍600请求/12chat/100万字符/1024输出token/90秒。

本轮两端R@5同96.124%、严格MRR同0.893669、原判平MRR同0.895349，共同五miss：watch回调/flush、死循环协程抢占、跨服务长事务、DP方程验证、同IP哈希副作用。前次独立调用四miss及严格/判平差异仍按原报告保留；不同索引/上游调用可能影响结果，但本轮不证明所有既有差异都来自噪声，也不以同样漏召回当质量合格。补充对照不是Go重新上传/独立调用、传统问答/噪声/完整多阶段/Agent或事实批准，不改原算法/标注/0.007判平/0.3阈值，不升级父项/五门禁。

## T801n 完整当前语料的真实模型双端检索与多阶段单题

完整双端报告[`p8-eval-real-20261104-232203.json`](p8-eval-real-20261104-232203.json)实际退出0，日志`/tmp/personalos-p8-real-full-corpus-1.log`。每端新随机TLS空库/原42迁移/认证CSRF、26篇实际上传及241条1024维向量，真实Java格式预期模型输入SHA、字节/UTF-16/分块顺序/元数据核验通过；不访问用户库。原129条查询全执行、零排除，每端两次传统知识问答和精确持久引用、50次pure-vector噪声请求完整完成。使用同一获准`qwen3.8-flash`/`qwen3.7-text-embedding-flash`，原算法/标注/阈值均未改。

| 本轮指标 | Go | 固定Java |
| --- | --- | --- |
| 原查询数/漏召回 | 129 / 4 | 129 / 4 |
| R@1 / R@3 / R@5 | 85.27% / 95.35% / 96.90% | 85.27% / 94.57% / 96.90% |
| 严格MRR | 0.901421 | 0.900775 |
| 原0.007判平后MRR | 0.907623 | 0.908915 |
| 本机搜索p50/p95（ms） | 138.90 / 169.26 | 144.59 / 176.70 |

新质量复核[`p8-real-model-review-20261104-232203.json`](p8-real-model-review-20261104-232203.json)明确`FULL_CURRENT_CORPUS_EXECUTED_QUALITY_NOT_APPROVED`。严格MRR Go高0.000646，但原判平MRR Go低0.001292，不能用任一方向掩盖另一方向，也不重跑挑选更好样本。三条严格名次差保留，独立上游调用和随机文档身份可能影响结果，但本轮不能证明全部差异都是噪声。旧子集的严格退化及全部失败报告保持原样，不和新全量拼成一次通过。

两端共同未召回原可接受文档的四题是watch回调/flush配置、跨服务长事务模式、DP转移方程验证、同IP哈希副作用。实际目标正文分别含watch/flush、saga/事务、初始化/遍历/验证/推导、ip_hash/哈希/NAT字面词；这只排除对应词完全缺失，不能证明事实/标注已人工验收，也不将同Java漏召回当合格。未删题、换答案文档或降低0.3阈值凑通过。实际426 embedding/5 chat、575874输入字符、296584 total tokens，无上游失败或重试；两端停机连接归零并清理本次容器。`ccd2619`及时提交报告和独立复核。

完整库另跑原BRIN单题多阶段，报告[`p8-eval-real-20261104-232433.json`](p8-eval-real-20261104-232433.json)、日志`/tmp/personalos-p8-real-full-multistage-1.log`，实际退出0。每端仍重新上传26篇/241向量并核验预期嵌入SHA；均READY/四阶段OK/零warning、各四次实际前台chat和一条精确持久引用。只完成1题，报告明确另128题材料齐备但未执行、零缺材料；不拿单题代替129题检索或完整RAG。隔离进程后台审查关闭，不改变应用默认值。实际70 embedding/9 chat、651850输入字符、327369 total tokens，无上游失败或重试，本次进程/容器清理。

`44b8a2a`及时提交多阶段报告及“严格不低仍必须保留判平退化”的回归，完整127项Python测试通过（4.000s，`/tmp/personalos-p8-python-full-corpus-final.log`）。本批无生产Go修改；公开材料与129题完整执行子项已完成，历史身份/人工事实/完整多阶段与Agent/后台审查、无证据严格契约、真实Push/备份/公网入口/发布批准仍开放，T801/T804/P8及五门禁不升级。

## T801m 完整26篇公开正文与可复现准备工具

最后四篇直接抓取在第一篇仍无有效正文（`/tmp/personalos-p8-juejin-final-four-1.log`，退出2/BLOCKED），没有循环重试；正常浏览器逐篇核对原URL/canonical、标题及实际`article-viewer`后均能显示全文。只读导出容器和页面title/canonical，原浏览器资料、登录和验证码均未触碰，本次临时标签已关闭。四篇正文字符为18626/6101/7312/9014，共41053；原提取器复用且SHA不变，渲染DOM哈希范围明确标注，不冒称原始HTTP响应。

`prepare_go_juejin_corpus.py import-rendered`离线验证显式来源/时间/哈希并创建四篇新清单；`merge`验证先前22篇和新四篇身份不重叠，原字节/哈希/采集时间保留，按固定原顺序输出新目录后完整回读。最终26篇/210264正文字符、129条原查询全部可运行、零排除；目录`/private/tmp/personalos-p8-rendered-QR4ALY/full-corpus`，完整清单SHA及四篇来源见[`p8-corpus-complete-20261104-231937.json`](p8-corpus-complete-20261104-231937.json)。旧采集目录和报告不改，全文仍不入Git。

`9923e58`及时提交准备工具、四项身份/哈希/链接/重复/顺序测试及覆盖报告修正；完整126项Python测试通过（4.435s，`/tmp/personalos-p8-python-full-corpus-tooling.log`）。真实runner不再写死16篇，质量复核严格核对文档/查询/排除数，完整当前语料与子集分开标记；复核输出使用新时间戳且拒绝覆盖旧文件。材料完整不等于历史快照身份、事实质量、多阶段RAG或发布验收通过，父项/五门禁保持开放。

## 历史公开正文补取：22篇材料、111条原查询

原缺10篇来自固定清单后十项：Python并发两篇、TypeScript两篇、分布式系统两篇、动态规划两篇、Nginx两篇。上轮只在第17篇抓取失败就停止，后九篇未尝试；此前“缺10篇”只代表本地材料未齐，不代表十篇均不可获取，也不代表原查询/答案文档标注缺失。

本轮没有覆盖旧目录、重试已成功样本或绕过保护：仅从第17篇开始一次正常HTTPS采集，每篇保持3秒间隔和原解析器/长度/来源/哈希校验。成功取得Python并发选型7271字符、Python异步陷阱4793字符、TypeScript泛型3475字符、类型体操16394字符、CAP/Raft4445字符、分布式系统6672字符，共43050个正文字符。在第23篇`7591207451778875418`（动态规划五部曲实战）无有效正文后立即停止，实际退出2/PARTIAL；后面的动态规划基础`7472359618326052901`、Nginx手册`7672711130193936424`、Nginx优化`7677521340606300170`尚未尝试，不能标记成已确认不可访问。

原16篇和新6篇分别由现有`load_corpus`检查，确认ID不重叠，保留原字节/哈希/采集时间并按固定26篇顺序复制到新0700目录，文件0600，再由原loader回读验证。合并目录`/private/var/folders/l9/n04rjfm95zdfj4w0v8jsjz480000gn/T/personalos-p8-juejin-merged-1c23_7zs`，原两份manifest不改。原查询/可接受答案列表不变；只有全部答案材料齐备才计入可运行范围，故可覆盖题数84→111、缺材料题数45→18。这是材料预检，不是新增27题已跑过或真实质量已通过。

采集日志`/tmp/personalos-p8-juejin-missing-1.log`，只含来源/计数/哈希的新增记录见[`p8-corpus-supplement-20261104-231255.json`](p8-corpus-supplement-20261104-231255.json)。正文/回答不上Git，旧16篇模型报告保留。历史正文快照身份仍未认证；新抓取可以补齐当前测试语料，但不证明与2026-10-25/26原评估时的正文逐字相同。原查询及标注本来就在仓库，不能把这项同一性证据说成“标注文件缺失”。真实备份、通知/域名配置和人工发布验收是另类材料，不能从公开文章推导。

## T801l 真实无证据负例工具与失败取证

显式`--mode no-evidence`在同一获准真实聊天/嵌入模型下运行补充问题“量子引力理论能否解释黑洞信息悖论的最终答案是什么？”。该问题不是原标注，报告明确原查询执行0条、84条材料齐备题未执行、45条缺材料；不跑原检索/噪声套件，不生成质量比较。仍使用每端新随机TLS库、原42迁移及认证API、16篇/142条1024维向量与真实Java格式嵌入输入SHA核验；仅隔离进程关闭后台审查，上游调用限制不变。

Go[`p8-eval-real-20261104-230130.json`](p8-eval-real-20261104-230130.json)实际退出1，默认both在Go失败后停止；随后只运行Java一次，报告[`p8-eval-real-20261104-230340.json`](p8-eval-real-20261104-230340.json)也实际退出1。两端均CLARIFY、answered=false、四阶段全OK、零warning、恰好三次前台chat，没有调用最终回答模型；均无引用或命中。严格预期仍为INSUFFICIENT/固定无内容答复，不因看到CLARIFY而放宽或重跑Go。相同安全澄清行为不证明负例已验收，也不足以认定为业务缺陷；空证据与澄清分类契约仍需审查。

Go旧报告未记录新增的计数字段，另从原私有回答只读核对引用/命中均0、回答90字符、澄清68字符，原响应SHA为`730404aa66e5682a7dc4452afe43d8b0ed09e72c243b6ea94d6c2ff9e8c93a9e`；不回填历史报告。Java新报告只记录安全计数与哈希（回答145字符/澄清123字符、引用/命中均0），不提交正文。两次报告独立保留，不能拼成一次双端通过。

日志`/tmp/personalos-p8-real-no-evidence-1.log`与`/tmp/personalos-p8-real-no-evidence-java-1.log`；Go/Java分别使用102132/104135 total tokens，无上游失败或重试，本次应用停止并清理容器。`92a3404`及时提交工具/严格拒绝CLARIFY等回归/质量复核拒绝负例报告及两份FAIL证据；完整120项Python测试通过（5.268s，`/tmp/personalos-p8-python-negative-final.log`）。T801l仅完成工具与诊断，负例验收本身未通过，T801/T804、P8与五发布门禁保持开放。

## T802f 当前输入拒绝、历史裁剪与恢复

独立`run_go_e2e_tests.py --mode context`在Java/Go各新TLS空库、原42迁移及认证API运行，使用合成模型，不读取真实模型配置。直接执行未改的原G4函数：新会话第一条1811字符当前输入均收到41301，零done/delta、零上游调用；数据库保留精确USER和原Java中断标记，原case仍FAIL，未通过增加预算或切掉用户文本凑通过。

再以正常预算经六轮实际HTTP对话创建12条持久消息，然后设2048（两端生效下限同6729），发送短当前问题。两端均一次上游调用完成；Go保留2条用户消息、Java保留1条，两端均为有序后缀、完整保留当前输入。全部14条消息及原12条记录逐项不变，证明请求窗口裁剪不删除历史；这一差异不被描述成模型输入逐字等价。恢复32768后，先前拒绝会话均可完成下一轮，原USER及中断记录不变，共4条消息。

报告[Go](p8-e2e/go/context-boundary-20261104-225738.json)、[Java](p8-e2e/java/context-boundary-20261104-225900.json)均PASS，仅指独立边界探针；每端共8次合成模型请求，本次业务产物清理归零，应用停止且容器已清理。日志`/tmp/personalos-p8-context-boundary-go-2.log`、`/tmp/personalos-p8-context-boundary-java-1.log`。首轮Go探针误认为失败不落中断标记而失败，保留`/tmp/personalos-p8-context-boundary-go-1.log`，按原finisher行为修正验证器，没有改业务代码。

`e2a1766`及时提交脚本/三个边界与哈希观察单测/两端报告。119项迁移Python测试通过（7.916s，`/tmp/personalos-p8-python-context.log`）。原G4实际发送当前长输入，与“仅裁旧历史”的安全契约存在冲突；本项定位冲突和补验恢复，不替原suite批准例外，T802及五门禁仍开放。

## T801k 真实多阶段RAG单问题双端探针

独立真实模型runner新增显式`--mode multistage`，每端重新创建随机TLS空库并上传同16篇正文，142条1024维向量及Java格式预期嵌入输入SHA逐项核验。只运行原问题“BRIN 索引适合什么场景”，不把单题报告送入84题检索质量比较：每端明确记录1题已执行、84题材料齐备、其中83题未执行，另45题缺材料；不执行该模式之外的50次噪声请求。

首次报告[`p8-eval-real-20261104-224257.json`](p8-eval-real-20261104-224257.json)实际退出1：Go四阶段均OK，但观察到5次聊天调用，未满足恰好4次的断言。原因是启用RAG时默认后台入库审查也使用同一模型网关；未把多出的调用当通过或上游失败。现仅在隔离子进程显式关闭`APP_KNOWLEDGE_RAG_INGESTION_REVIEW_ENABLED`，用于分离本项前台调用计数；不改应用默认值，不声称后台入库审查通过。

第二轮[`p8-eval-real-20261104-224539.json`](p8-eval-real-20261104-224539.json)实际退出0，日志`/tmp/personalos-p8-real-rag-2.log`。Go/Java各一次问答均READY、无warning，recall/rewrite/rerank/evidence四阶段均OK，各恰好4次实际前台chat调用。回答的显式标记、可接受原文标题及引用内容与持久分块SHA精确相符；回答正文/引用只在私有本地证据目录。Go/Java四阶段耗时分别为158/680/2115/5468ms与160/935/2667/4069ms，仅为该次本机观察。

本轮共46次embedding/9次chat（含预检），433806输入字符；提供商报告212720 prompt、1592 completion、214312 total tokens，无上游失败、不重试。两端应用均停止、数据库连接归零并清理本次容器。`33ce74a`及时提交runner/测试及成功和失败报告；后加的报告字段`background_ingestion_review_enabled=false`不回填改写已生成证据，第二轮进程已实际使用关闭配置。

T801k仅完成这个固定问题的流程/调用数/引用验证；尚未验证其余83题多阶段流程、完整事实质量、澄清/冲突等真实模型场景或后台审查。`review_go_real_eval.py`明确拒绝multistage报告作为完整检索质量证据。T801/T804、P8及五发布门禁保持开放。

## T801j 获准真实模型双端子集验收

按用户“用配置的真实模型继续验收”的明确授权，只从根目录显式`.env`读取模型白名单键，同名进程变量优先；不加载其数据库、认证或通知设置。实际模型为DashScope兼容端点上的`qwen3.8-flash`及`qwen3.7-text-embedding-flash`，thinking=false。真实密钥仅在验收/Node持久传输进程内存，以stdin传递，不放argv、子应用环境或文件；子应用仅持随机回环代理令牌。90秒上游超时、600请求/12聊天/100万输入字符/每次1024输出token上限，不重试、不跟随重定向、证书验证保留。报告不记录密钥、全文或回答正文；回答/引用仅存新0700本地目录。

独立`run_go_real_eval_tests.py`沿用原四评估文件固定SHA及原算法。两端各自新随机TLS PostgreSQL/pgvector、原42迁移/重复validate、认证Cookie/CSRF；嵌入及候选缓存关闭，传统知识问答与直接hybrid召回，未启用多阶段RAG。16篇覆盖84条原查询，45条缺完整答案材料而排除，没有削减答案列表。每端142条1024维向量，字节/UTF-16长度/分块顺序/元数据逐项核对；Java格式预期哈希由固定Spring AI 2.0.1真实依赖运行生成，仅以哈希与本端成功模型请求回执比较，引用正文另与持久分块精确核验。

真实调用暴露并修复两项Go缺陷：31分块长文一次嵌入被提供商400拒绝，改为保守16分块且任一批失败不产生部分写；OpenAI知识文档嵌入漏掉Java默认EMBED元数据，现按固定四键HashMap顺序及`{content}`替换语义构造模型输入。只在实际OpenAI提供商及其缓存包装中启用，Ollama/普通字符串查询/原文存储和引用不变，不自动重建现有向量或访问用户库。Java SDK的`/embeddings`和base64是验收代理兼容问题，代理现严格允许已知路由并原格式转发，base64仅在内存按little-endian float32验证，未改Java基线或替它拆分批次。

最终双端报告[`p8-eval-real-20261104-222729.json`](p8-eval-real-20261104-222729.json)实际退出0，日志`/tmp/personalos-p8-real-eval-10.log`；PASS仅指该子集执行完成。质量复核见[`p8-real-model-review.json`](p8-real-model-review.json)，其结果明确`SUBSET_EXECUTED_QUALITY_NOT_APPROVED`，严格退化不因判平一致被覆盖。

| 指标 | Go | 固定Java |
| --- | --- | --- |
| 原查询数/漏召回 | 84 / 0 | 84 / 0 |
| R@1 / R@3 / R@5 | 89.29% / 98.81% / 100% | 90.48% / 98.81% / 100% |
| 严格MRR | 0.937500 | 0.943452 |
| 原0.007判平后MRR | 0.946429 | 0.946429 |
| 真实知识问答/持久引用核验 | 2 / 2通过 | 2 / 2通过 |
| 独立pure-vector噪声请求 | 50 | 50 |
| 本机搜索p50/p95（ms） | 147.07 / 174.38 | 150.52 / 191.21 |

唯一严格排序差为“容器删掉之后里面的数据去哪了”：Go正确文档第2、Java第1，两端原算法tied_rank均1。只记观察，不能证明差异全部由噪声导致；原0.007判平与默认0.3相似度阈值未改。最终实际312次embedding/5次chat，提供商用量180186 prompt、780 completion、180966 total tokens，无上游失败；用量不等于金额承诺。两个应用进程均停止、连接归零、本次容器已清理。

首五轮失败报告和原Go子集结果、后续Java路径/base64/原始哈希假设失败均保留；Java单端完整通过报告`p8-eval-real-20261104-222104.json`保留，不把不同轮次拼成同轮通过。`90c593a`提交真实runner/传输及前五报告，`6372dcc`提交Java适配/格式oracle，`537ab84`提交Go元数据修复及完整双端报告；此前`a2f6d16`和`8424572`分别为有界代理与保守分批。

最终Go全包integration串行race退出0（app93.648s，`/tmp/personalos-p8-go-embedding-format-race.log`），knowledge/model定向race及真实TLS知识原子替换/维度/租约定向回归通过（`/tmp/personalos-p8-embedding-format-tls.log`，1.588s），integration vet通过。原合成26文档/129查询/50噪声适配器按新格式核验再次退出0（`/tmp/personalos-p8-eval-tooling-embedding-format.log`），仍不作为真实模型质量。原Java/前端/42 SQL/评估算法未改，T801/T804父项与全部五门禁保持开放；缺10篇/45查询、历史标注认证、人工事实质量、多阶段RAG与完整发布验收继续追踪。

## T801i 掘金公开正文与隔离检索测试

用户明确要求从掘金获取文档用于测试后，新增 `fetch_go_juejin_corpus.py`，仅访问固定原清单的HTTPS公开URL，保持证书验证、不跟随重定向、8MiB/25秒限制、每篇间隔3秒。只创建新0700目录与0600正文文件；校验原提取器SHA后复用其正文解析，不调用原fetch/main/清库入口。默认网络通路成功，先前直连TLS失败不代表站点不可达。首次抓取取得16篇、共126161个提取正文字符；第17篇`7651932218912522280`无满足条件的正文，立即停止并记录PARTIAL，没有继续探测其余页面或绕过保护。

全文和独立manifest保存在 `/private/tmp/personalos-p8-juejin.Lu4LAU/corpus/`，不提交Git。公开来源URL、实际页面标题、固定评估标注名、UTC抓取时间、页面/正文/文件SHA和字节数随报告留存；新抓取文本不等于历史26篇快照/标注已认证。抓取器提交 `62f6ddc`，六项单测覆盖错误页/短正文/不匹配canonical/坏UTF8、网络与重定向拒绝、失败即停、篡改/链接/重复清单和原提取器漂移。

新增显式 `run_go_eval_tests.py --downloaded-corpus-dir <目录>`。加载前逐文件核对身份/大小/SHA；仅在所有可接受答案文档均已取得时保留原查询，不修改答案列表或按命中结果删题。16篇覆盖原129条中的84条（13 term/19 natural/52 cross），其余45条明确不执行。独立随机TLS库、原Flyway42/重复验证、认证Cookie/CSRF和真实上传API；16文档142条1024维向量，sizeBytes/UTF-16 charCount/分块计数/连续index/标题/文件名逐项一致，每个数据库分块SHA均对应实际模型嵌入输入。验证只返回哈希/统计，不输出或提交正文。

两轮实际退出0并清理本次进程/容器。第二轮报告[`p8-eval-juejin-20261104-212152.json`](p8-eval-juejin-20261104-212152.json)，首轮[`p8-eval-juejin-20261104-211847.json`](p8-eval-juejin-20261104-211847.json)保留；日志 `/tmp/personalos-p8-eval-juejin-{1,2}.log`。84次hybrid请求与另一显式pure-vector进程的50次噪声请求完整执行；本地确定性词袋嵌入下R@1=57.1%、R@5=76.2%、MRR=0.655、20条未中，两轮相同。没有按结果调阈值或放宽0.007判平；分数不代表真实模型语义质量，工具链PASS不代表召回质量验收。T801/T804/五门禁保持开放。

## T802e 显式记忆工具补验

新增独立`--mode memory`，Java/Go两端新TLS空库首轮均退出0。普通事实对话在关闭自动记忆时实际完成并等待原1秒观察窗口，零记忆写入；两轮明确saveMemory各有SSE done与TOOL/SUCCESS审计，仅一条精确内容/MANUAL来源/稳定ID的记忆、四消息，最后会话/消息/记忆清理归零。报告`p8-e2e/{go,java}/explicit-memory.json`，日志`/tmp/personalos-p8-explicit-memory-{go,java}.log`。夹具仅匹配完整显式前缀，普通事实/记数字/引用/空白内容无调用，有回执后不重复。

全84项又在两端复跑，Go `e2e-20261104-202743.json`、Java `e2e-20261104-202748.json`同为81 PASS/1 FAIL/1 SKIP/1 NOTE，均实际退出1；原SKIP现在观察到一条MANUAL实际写入仍保留，G4失败和工具超限观察也未放宽。完整84项Python测试通过（`/tmp/personalos-p8-python-memory.log`）。未改业务源码或原断言；模型选择质量、真实Push、外部输入与五门禁继续开放。

## T802d Push有界面对照

`chrome_p5_web_push.mjs --headed`只移除headless，仍使用本次随机profile和原期限；不接触已有浏览器资料。两轮实际订阅/CDP控制成功、Go在新TLS库发送/FCM接受，但15秒没有真实Push。报告`p8-e2e/push-go-headed.json`与`p8-e2e/push-go-headed-normalized.json`均DELIVERY/FAIL；只见控制消息1次push，零worker/notification错误、零GCM接收/解密失败记录，白名单归一化后连接仍OTHER，不足以定位根因。四项Node测试与语法检查通过；全部本次进程/库/profile已清理。T802及五门禁继续开放，不能把“有界面成功订阅”当完整送达。

评估原文首次公开源探测仅针对原26篇中的首URL，不保留内容、不绕过保护；`curl --noproxy '*' --proto '=https' --max-time 15`实际TLS握手失败，HTTP000/0bytes，未得到文章。没有继续批量抓取或把“文件存在”当来源验收；真实语料/模型仍需要授权材料。

## T802c 原E2E失败隔离与计划完整执行

原G4的恢复语句被首轮断言跳过，现只在迁移适配层finally执行相同32768恢复；五项定向单测确保原失败不被恢复异常覆盖、恢复失败仍阻止runner成功。原84项断言与基线文件不改。模型夹具从实际请求编号解析计划，仅对受支持的任务查询/统计/建议计划，按成功工具回执启动和完成步骤；失败回执与未知计划不会伪造完成，统计注明本次最多100项返回范围。

两端最终全84项同为81 PASS/1 FAIL/1 SKIP/1 NOTE，G4预算失败、saveMemory原跳过及工具上限error终帧观察保留。Go报告`p8-e2e/go/e2e-20261104-201958.json`，Java报告`p8-e2e/java/e2e-20261104-202003.json`，日志`/tmp/personalos-p8-e2e-plan-{go,java}.log`。首轮只创建计划时Go正确yield，80/1/1/2报告保留；没有为了done修改引擎约束。

独立`--mode plan`两端第二轮退出0，`p8-e2e/{go,java}/plan-lifecycle.json`验证两HIGH/一LOW实际任务、三步COMPLETED/DONE/顺序时间戳、findTasks回执、准确统计、两消息和任务未变、五业务表清理归零。日志`/tmp/personalos-p8-plan-lifecycle-{go,java}-2.log`；首轮只因探针缺少cleanup started_at失败的日志保留，已初始化，未修改原清理器或业务代码。全部83项Python单测通过，日志`/tmp/personalos-p8-python-plan.log`。此批只提升夹具覆盖，不把相同失败、合成计划或原SKIP当完整验收。

## 实施顺序

1. 补齐默认关闭的数据保留策略及生命周期，避免部署遗漏实际运行钩子。
2. 独立Go镜像/Compose/Caddy、五角色/管理端口、非root及显式凭据，先在隔离环境验证。
3. 本地启动停止、备份恢复、Push密钥和监控告警，复用基线资产并记录差异。
4. 全量前端检查与现有E2E接Go；逐页、通知和上传下载在真实浏览器验证。
5. 汇总并审查154类/1119测试声明、全部来源文件和接口证据，逐项补缺。
6. 真实设施全量/故障/恢复、性能与隔离切换演练；证据通过后才核验发布门禁。
7. 更新Go运维/开发说明，保留迁移历史；Java归档须用户验收后另作决定。

## T800d 私有CA HTTPS与证书续期

`run_go_compose_tests.py --https`原生转换当前候选Caddyfile，HTTP路由/监听/安全头保持相同；只在临时JSON叠加内部签发器、90秒leaf与2秒续期检查。全新随机回环端口、Compose项目和证书卷，CA不安装系统信任。后端使用明确monitoring测试profile；prod配置本身仍被守卫拒绝，不通过测试解除。

实际第4轮完整通过：可信链/localhost、未知CA与错误SNI拒绝、HSTS/四安全头、Secure/HttpOnly/Lax Cookie、认证CSRF/任务持久化、管理路径404、原SPA、附件字节相等、原42迁移/重复validate、UID10001/只读/零capability；自动取得不同serial且更晚到期的证书，重启Web后CA和续期leaf保持，已有认证会话可用；SIGTERM与本次资源清理通过。新卷可写，没有通过改root或放开权限修复。日志`/tmp/personalos-p8-https-4.log`。

首轮测试只捕获证书校验错误，但Caddy对未知SNI在发证前拒绝握手；验证器保留未知CA必须是证书校验错误，另明确允许未知SNI的TLS拒绝。第2/3轮证实生产候选真实缺陷：随机发布端口的HTTP入口308到`https://localhost/tasks?fixture=p8`，丢失外部端口。现显式308引用与Compose映射相同的`GO_HTTPS_PORT`，内部8080/8443和ACME自动管理不变。前三轮失败日志保留；没有放宽跳转、认证或续期断言。

第5轮用最终带报告runner再次完整通过（`/tmp/personalos-p8-https-5.log`），清理后生成脱敏[p8-https.json](p8-https.json)。59项迁移Python单测与固定1093文件基线检查通过；五原生Compose配置测试通过。这只是内部CA的回环演练，不验证公网ACME/DNS挑战/公有证书续期或生产容量；T800父项与五门禁继续开放。

## T801f 原评估工具与上传参数差分

`go_eval_adapter.py`先验证原queries/rebuild/retrieval/noise四文件固定SHA再加载，保留CORPUS/QUERIES/evaluate/summarize/noise指标算法，仅替换上传/检索IO。禁止原重建main/delete_all/固定目标；仅本次新随机verify-full库、空表、认证Cookie/CSRF与内存材料清单，返回命中须属于本次文档且标题/ID一致、无重复/非法分数。未提供原26篇全文目录时，预检明确退出2/BLOCKED，不扫描/tmp；即使指定目录文件齐全，也不宣称来源与语义质量已验收。

首轮合成工具链因上传title未生效失败，保留`/tmp/personalos-p8-eval-tooling-1.log`。进一步启动固定Java真实Servlet/认证API、同一新TLS库后再切Go，10场景差分首轮真实发现Go忽略URL title、丢重复值和form/query合并次序，日志`/tmp/personalos-p8-upload-binding-1.log`。Java实际顺序为form在前、query在后；String多值以逗号拼接，空值/中文/加号/百分号/字面逗号保留。Go现按该行为绑定title，同时修复共享读取器丢URL documentId；14个定向Go子场景含target/非法UUID及原payload不变，先红后绿证据保留。knowledge/http两包race通过，日志`/tmp/personalos-p8-upload-params-green.log`；Java/Go实际10标题场景一致见[p8-upload-binding.json](p8-upload-binding.json)。

第2/3轮完整工具链通过，最终`/tmp/personalos-p8-eval-tooling-3.log`：26份重复标题合成材料，经原129查询（24 term/22 natural/83 cross）产生129次实际hybrid请求，再停止进程，用明确关闭hybrid的第二Go进程执行原noise工具10查询×5次。没有过滤null样本来让噪声通过，而是单独pure-vector协议；未改0.3默认相似阈值或0.007判平阈值。Java及两个Go进程停止、应用连接归零，本次容器清理。报告[p8-eval-tooling.json](p8-eval-tooling.json)的合成未命中91/129、MRR0.270与零噪声只作工具观测，不是原26篇文章/真实模型质量或调参依据。

同一缺口使先前演示验证只看文档计数/向量而漏掉标题。现适配器在上传响应和完整列表回读都核对原配方filename/title；新增拒绝错标题单测。`/tmp/personalos-p8-demo-title-1.log`用最终Go重跑七组配方完整通过并清理，四篇标题精确一致，其他数据仍通过。原演示脚本不改，旧失败证据保留；15个在线评估文件、T801父项与五发布门禁仍开放。

最终66项Python单测、完整knowledge真实TLS库race6.403s、Go integration vet/build、固定基线/78配置/派生映射/diff检查通过；`check-complete`实际退出1，五门禁与23个in_progress保持。最终复验日志见validation.md；本批不重复此前无关全包长回归。

## T801g 上传UUID转换边界

固定Java实际Servlet与Go的下一轮差分发现，带ASCII首尾空白的documentId在Java得到目标不存在404，Go错误返回参数400。首轮失败保留 /tmp/personalos-p8-target-binding-1.log 与 /tmp/personalos-p8-target-params-red.log。共享读取器现先按Java hasText判断空白，再只去除ASCII控制/空白以匹配String.trim；不把UUID两侧Unicode空白、NBSP或NUL错误当作合法输入，保留form优先与首个UUID参数的语义。

20个Go子场景与实际Java/Go的10标题加10UUID场景通过；UUID探针只访问本次新TLS空库中的固定不存在目标，在400/404返回后分别检查knowledge_index_job为0。报告[p8-upload-binding.json](p8-upload-binding.json)记录两个原控制器SHA与零任务断言；实际通过日志 /tmp/personalos-p8-target-binding-2.log，完整原评估工具链第4轮也通过并清理（/tmp/personalos-p8-eval-tooling-4.log）。这仍只验证合成工具与参数绑定。

定向knowledge/http两包race通过（1.746s/1.523s，/tmp/personalos-p8-target-params-green.log），66项Python单测通过（/tmp/personalos-p8-python-final-targets.log），integration vet与固定基线/派生检查通过。当前源码镜像重建成功（/tmp/personalos-p8-image-after-params.log），HTTPS第6轮完整通过并清理（/tmp/personalos-p8-https-6.log），保留私有CA与测试profile边界。

上传修复后的原E2E实际退出1，结果仍80 PASS/1 FAIL/1 SKIP/2 NOTE、无ERROR。唯一FAIL仍为固定Java同现的G4预算用例，saveMemory仍跳过，未放宽断言；业务产物归零并清理独立容器。日志 /tmp/personalos-p8-e2e-after-params.log，完整报告[p8-e2e/go/e2e-20261104-194702.md](p8-e2e/go/e2e-20261104-194702.md)。T801/T802与五发布门禁继续开放，不以回归无新增失败替代通过。

## T801h 本地Alertmanager完整通知链

新增显式 compose.alerting.yml，不改变默认监控或原Java部署。操作者必须提供独立配置路径；Alertmanager非root/只读、无宿主端口、独立持久卷。combined/distributed两份Prometheus变体只增加 alertmanager:9093，采集目标/规则/15秒时钟逐字一致检查通过。示例仅含不可投递的.invalid HTTPS目标，TLS验证与禁止跟随重定向保留；runner只替换为本次私有HTTP接收端，拒绝附加通知目标、子路由或HTTP代理。

实际combined第2轮完整通过：新随机TLS库及空卷、原42迁移/重复validate、原前端/认证/CSRF/上传下载、真实指标/Grafana20面板和六条规则单测仍通过；新服务UID65534/只读/零capability/无宿主端口通过。停止本次backend后实际观察pending，保持原for=2m，实际firing经Alertmanager送达本地接收端；故意首次503未算成功，后续重试200才通过。恢复backend后观察告警inactive，收到相同fingerprint/startsAt且结束晚于开始的resolved；重新登录回读任务持久化，优雅停止及本次项目/卷清理通过。报告[p8-alerting.json](p8-alerting.json)仅在清理后生成，日志 /tmp/personalos-p8-alerting-2.log。

首轮在通知演练前因接收端挂载层级过浅、根路径定位失败而退出，日志 /tmp/personalos-p8-alerting-1.log 保留；已修正挂载并纳入断言，没有改变告警等待或HTTP断言。全部76项迁移Python测试通过（/tmp/personalos-p8-python-alerting-final.log），包括7项接收端/配置隔离单测、7项原生Compose测试及采集变体一致性。此处只验证真实target-down规则与本地协议；其余五条规则仍是时间序列单测，不冒充真实故障或外部通知最终送达。T801父项与五发布门禁保持开放。

distributed首轮也完整退出0，报告[p8-alerting-distributed.json](p8-alerting-distributed.json)，日志 /tmp/personalos-p8-alerting-distributed-1.log。新随机PostgreSQL/Redis TLS、API与三个Worker、四采集目标和API重启后共享会话验证仍通过；实际API停机在原2m规则下pending/firing、本地503重试/匹配resolved、重新登录回读任务和四角色退出0通过，项目与新卷均清理。完整76项Python测试在台账更新后复验通过（/tmp/personalos-p8-python-alerting-post-audit.log）。未新增Go业务修改，不重复此前无关全量长回归；真实收件渠道及发布批准继续开放。

最终1093文件固定基线、78配置处置、两份P8派生映射与diff检查通过；随机演练容器已无残留。check-complete仍实际退出1（/tmp/personalos-p8-check-complete-alerting.log），五门禁均未verified；台账只增加证据，没有自动升级验收状态。

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

## T802b Push失败阶段与隐私诊断

Push runner补充明确stage、35秒CDP命令上限、仅白名单连接状态与日志行数的GCM诊断；两项Node纯单测验证不输出设备/订阅标识/密钥/日志正文以及未知状态脱敏，CI只加入纯单测。订阅30秒/控制3秒/真实送达15秒期限均不变；诊断移到订阅/本地控制之后，Go投递分支改用新TLS库，但最新复验未到该分支，不宣称TLS投递已通过。

新增两轮冷profile都在订阅30秒期限失败，未启动Go数据库或投递FCM。首轮订阅前的GCM记录只得到空连接状态，不能定位原因；调整诊断次序后仍在相同订阅阶段失败。两报告及日志见[p8-e2e/README.md](p8-e2e/README.md)，旧FCM接受/真实送达失败报告保留，所有本次Chrome/profile已清理。不能据此推断防火墙/平台/解密根因，T802与五发布门禁不升级；原saveMemory case两分支均SkipCase的事实也已在E2E说明澄清。

提交前57项Python单测（`/tmp/personalos-p8-python-final-gcm.log`）和2项Node脱敏单测、JS语法、固定基线/两派生映射/workflow YAML/diff检查通过。最终`check-complete`仍实际退出1（`/tmp/personalos-p8-check-complete-with-demo-gcm.log`），2438项均未verified、五发布门禁未验证；当前2415implemented、23in_progress，没有把失败或缺材料的职责自动升级。

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

## T801e 原演示配方隔离适配

新增`run_go_demo_seed_tests.py`，校验原脚本固定SHA后只调用原七个seed函数，不调用main/reset；HTTP通过本次随机Go的登录Cookie/CSRF，SQL通过本次随机TLS容器的stdin。写入前核对disposable标记、verify-full、固定fixture库/用户、容器标签/随机名称/回环端口和全部业务表为空；没有接受既有目标的CLI参数。四项单测覆盖危险目标在执行前拒绝、容器归属/绑定/非空拒绝、七函数且不能reset/越界HTTP，以及SQL目标/错误脱敏。

`python3 scripts/migration/run_go_demo_seed_tests.py`第二轮完整通过（`/tmp/personalos-p8-demo-seed-2.log`）；原42迁移/Flyway复验与app监听边界race1.626s通过。真实Go回读8任务的状态/描述/优先级/16标签、6全天/重复日程、4同步摄入Markdown文档与4向量、5记忆/2置顶、5会话/10条精确有序消息、31天161条合成ACTUAL用量及9审计的5成功/2待确认/1拒绝/1失败。停止combined后连接归零，临时数据库/文件/进程已清理，脱敏报告见[p8-demo-seed.json](p8-demo-seed.json)。

首轮种子写入已成功，但验证器把知识全量列表误当分页对象而失败；保留`/tmp/personalos-p8-demo-seed-1.log`，按原API修正验证器，并核对导出键calendarEvents，不改生产实现或原配方。候选CI加入同一隔离验证，未执行远端CI。全清单当前2415implemented、23in_progress、零verified，五门禁保持原状；本项不是用户库清空授权、真实token/模型质量或浏览器验收。

本批提交前全部57项迁移Python单测通过（`/tmp/personalos-p8-python-with-demo.log`），固定基线/78配置/派生审计、workflow YAML解析及diff检查通过；没有Go生产源码变更，既有全包race证据保持，未重复无关长回归。

## T803a 确认事务补验

审读固定Java ConfirmationTransactionTest八声明后发现，已有Go内存失败状态断言与真实库并发成功不足以证明失败标记独立提交。新增TestPostgresP8ConfirmationTransactionOutcomes：处理器运行中通过第二个池读取已提交CONFIRMED及resolved_at；业务错误/普通错误最终FAILED、缺失目标CANCELLED且无执行、成功影响3行、重复终态令牌均不再调用处理器；独立过期SQL压到过去后抛错仍保留EXPIRED与解决时间。只用本轮合成ID，没有全表清理。

`python3 scripts/migration/run_go_postgres_tests.py --package confirmation --tls --run '^TestPostgresP8'`真实TLS库race通过（1.269s），日志`/tmp/personalos-p8-confirmation-transaction.log`。原八声明分别由同名success/business/runtime/missing子用例及末尾expiry断言覆盖；原Java和生产确认算法未修改。其余声明/来源职责继续核查，T803父项未完成。

## T803b 任务标签批量读取

核查原ListSearchIntegrationTest.taskTagsAreBatchLoaded发现Go列表与搜索逐任务读取标签，违反原最多三次查询的断言。改为按最多1000个UUID分批读取标签，保留任务顺序、tag_index顺序及非nil空标签；单任务读取不变。真实pgx驱动装饰器计数，不以SQL字符串或内存mock证明查询数。

`python3 scripts/migration/run_go_postgres_tests.py --package task --tls`全部race通过（1.970s），日志`/tmp/personalos-p8-task-batch.log`。新增TestPostgresP8TaskBatchTagsPaginationAndStats验证25条合成任务：20条页恰好三查询、ID批取恰好两查询、每四条翻页无重漏且顺序一致、标签顺序、溢出页只计总数、全局待办统计随完成减一。仅删除本测试创建的ID，临时TLS容器已清理；不据此把原任务全部声明标为verified。

## T803c 任务原断言补验

新增三个真实库用例，逐项补足原任务测试的遗漏：标签持久顺序、PATCH省略保持描述/截止/标签、替换与空数组、标签精确匹配/去重列表/删除级联；未完成优先/截止升序/null在后、批删51个拒绝及重复/缺失ID的实际影响数；TODO与DOING重复完成不覆盖previous_status，重开清完成时间并重新计算下一轮；Schedule/Priority确认前均无写入、确认后准确生效。单测另覆盖nil/空标签非nil列表、恰好20个UTF-16单位/5标签、先去重再限量。

`python3 scripts/migration/run_go_postgres_tests.py --package task --tls`全包race通过（2.556s），日志`/tmp/personalos-p8-task-contracts.log`，所有临时资源已清理。原Java断言未修改，发布门禁保持开放。

## T803d 文本、附件与剩余列表契约

共享Texts原六声明落到Java strip语义、nil/空白、只裁首尾、UTF-16恰好200和超限以及精确错误消息。修复日程service按未裁剪标题判断长度/吞掉NBSP的问题，任务标题和描述复用相同规则；HTTP DTO原始200/2000限制继续保留，service不额外增加Java没有的描述限制。标签错误消息恢复原超长标签/实际唯一数信息。

附件补验text/plain、扩展名回退、空媒体JSON、zip/image不当文本、空白/空字节/坏PDF以及8000 UTF-16上下文截断；实际PDF层提取沿用原真实Poppler测试。新增25会话/25真实文档向量测试验证两页20+5不重漏、total、页码/size上下界和溢出、活跃会话两消息计数、完整列表端点、无命中及文档标题/文件名搜索。

定向单测全部race通过，日志`/tmp/personalos-p8-text-extract-2.log`；真实TLS列表/任务四项通过2.833s/2.344s，日志`/tmp/personalos-p8-text-lists-db.log`。该轮calendar过滤无匹配，未把它当数据库通过；另跑calendar真实TLS全包通过3.043s，日志`/tmp/personalos-p8-calendar-final.log`。最终全包串行race退出0（app215.449s），日志`/tmp/personalos-p8-go-final-serial-2.log`；前一轮启动后才新增reflect依赖导致task编译失败，失败日志保留，未误报通过。所有本次数据库容器清理，发布门禁不升级。

## T803e 冲突对话双来源持久化

原ChatKnowledgeIntegrationTest.conflictingEvidenceBypassesAnswerModelAndPersistsBothSources不能由单引用的旧P6测试替代。新增TestP8ConflictingEvidenceBypassesAnswerAndPersistsBothSources，与原Java一样仅替换检索边界；两篇实际摄入文档、真实HTTP/SSE与PostgreSQL保持原链。断言rag/citations/delta/done、不同说法/适用日期、一次检索、回答模型零调用、USER/ASSISTANT两消息及两条完整有序引用快照相等。既有知识pipeline/governance测试继续承担冲突判断职责，不把本用例当真实模型语义评估。

`python3 scripts/migration/run_go_postgres_tests.py --package app --tls --run '^TestP8(ConflictingEvidence|SessionAndDocument)'`两项race通过2.735s，日志`/tmp/personalos-p8-conflict-final.log`，原42迁移及临时容器清理通过。未更改生产实现或原Java断言，逐声明台账另行记录。

## T803f 剩余144声明逐项映射

[p8-test-mapping.md](p8-test-mapping.md)逐行列出144个剩余声明、具体测试函数/文件和对应断言；与前阶段映射合计1119声明均有implemented/verified处置。25个来源测试文件只有其全部声明已处置才汇总证据。脚本检查固定声明集合、证据函数唯一存在及Python类方法AST；三项脚本测试验证幂等生成、缺失证据/基线漂移拒绝、其他条目/既有verified/五发布门禁不变。仅函数存在不证明测试运行，执行证据仍分别列明。

新增真实边界测试：无幂等key同内容两次创建不同ID并仓储读回；HTTP状态/优先级/关键词组合过滤；六标签/21单元标签400且无部分行；立即删除后404/40004；空独立库标签为[]；取消后不能确认且处理器零调用、会话/状态/默认Pending过滤、未知确认为ResourceNotFound。首轮错误地断言确认模块错误为通用BusinessError，日志保留；修为原模块Error.Code，不改生产行为。任务HTTP错误码按原40004断言，不误用40400。

最终`python3 scripts/migration/run_go_postgres_tests.py --package task --package confirmation --package app --tls --run '^Test(Postgres|P8)'`完整通过，日志`/tmp/personalos-p8-final-mapping-db.log`，三包真实库race含task/confirmation全套和app全部P8。此前失败日志`/tmp/personalos-p8-final-contract-boundaries.log`保留，修正后同链已复验。Spring Bean缺席与Instants测试辅助职责按架构对应，R-053未知TOOL保留差异明确列出；不按类名或计数批量verified。

## T803g 全清单职责审计

[p8-inventory-review.md](p8-inventory-review.md)与[p8-inventory-disposition.json](p8-inventory-disposition.json)区分源代码职责、原样保留资产和未通过的真实验收。1093个原文件逐项SHA一致；57个基础/测试辅助文件显式映射到Go实现与执行证据，不按Java目录批量通过。补记两路由/14运行钩子；前阶段证据保留，104路由/27工具/95钩子/1119声明都有实现处置。

原42 SQL/Java构建/前端/业务规格/历史报告分别保留：SHA相同不能证明功能通过，实际测试/部署/回退证据另列。24个文件继续in_progress：生产配置3、Push源码/测试4、演示种子1、原E2E1、在线评估集合15。演示脚本固定现有容器且默认清空业务表，未执行；只允许新建资源的授权不涵盖清空用户数据。原Agent评估器三项纯单测通过（`/tmp/personalos-p8-original-eval-unit.log`），不解除在线评估集合需要真实语料/模型的职责。

三项审计脚本测试验证每文件有处置、哈希漂移/未映射Java拒绝、生成幂等、未完成任务保持开放及既有verified/五门禁不变。审计后2414项implemented、24项in_progress、零新增verified；T803父项保持开放，因为记录未完成职责不等于职责已验收。未读取本机.env、未修改原Java/前端/SQL/部署资产，未push/合并/部署。

审计提交前最终验证：全部53项迁移Python单测通过（`/tmp/personalos-p8-python-final.log`），原Agent评估器3项纯单测通过；Go integration vet、build、mod verify退出0。1093文件/104路由/27工具/95钩子/1119声明固定基线、78配置处置、两个P8派生映射检查与`git diff --check`通过。候选workflow经Ruby YAML解析通过，未安装actionlint、未运行远端CI。此前最终全包串行race及真实TLS三包结果保持有效，本批只改审计脚本/记录，未重复执行长回归。

实际运行`go_inventory.py check-complete`退出1（`/tmp/personalos-p8-check-complete-final.log`）：2438项均尚未verified，五发布门禁仍未验证。这是未完成验收的真实结果，未通过批量升级状态消除失败；T806与T808继续开放。

## T805a 候选CI与T807文档

独立`.github/workflows/go.yml`保留原ci.yml，Go分支/相关PR及手动触发，最小只读权限。执行全包串行race、integration vet、build、模块校验、44项Python测试/固定基线/78配置处置检查及新TLS库task/confirmation/P8回归，失败日志作为artifact。没有远端push或工作流运行，不声称GitHub已绿；它也不替代原Java/前端及浏览器/切换/人工门禁。

PostgreSQL/Redis/S3 runner支持GO_TEST_GOCACHE/GO_TEST_GOPATH绝对路径，不继承原应用配置；离线依赖策略保持。两项缓存参数单测、全部44项Python测试通过，Ruby原生YAML解析通过；显式覆盖缓存的真实TLS确认及P8六个顶层测试通过（1.667s/3.435s），日志`/tmp/personalos-p8-ci-tls.log`。未安装actionlint，未把YAML可解析当成远端执行证明。

Go开发贡献、架构/角色/接口与安全说明新增于backend-go/docs，运维继续由deploy/README承载，迁移索引和候选README已消除“P7未开始”等陈旧描述。原根宪法/贡献/README及Java历史不在修改范围；plan.md继续作为技术迁移说明。文档明确prod拒绝/readyz503、真实Push失败、预算同基线失败、saveMemory跳过与真实备份/生产/用户验收限制。

## T804b/T805b 隔离切换、回退及本地对照

`python3 scripts/migration/run_go_cutover_tests.py`第8轮及最终第10轮完整通过，最新日志`/tmp/personalos-p8-cutover-10.log`，脱敏结果见[p8-cutover.json](p8-cutover.json)。固定Java真实认证API创建任务/标签/日程/记忆/知识向量/附件/聊天及AES-GCM档案；停止combined进程后连接归零。原tar/SHA/custom dump及附件恢复到另一随机库，再启动Go，旧Java会话401，重新登录后五模块导出仅规范化时间表示与移除exportedAt后精确相等；附件字节相同，保存的会话模型实际按名解密Java密钥调用合成上游。

Go相同PATCH幂等键只写一次、保留未知TOOL IN_PROGRESS且不改Java密文；停Go并确认连接归零后，原Java在该恢复库重新登录，精确读取Go写入的导出与附件、回放Go幂等结果，42迁移历史不变。所有数据库/附件/进程/备份均为本轮新建且最终清理。不是用户真实备份、外部投递撤销、生产TLS或发布批准，T805父项仍开放。

前7轮失败日志保留：前3轮为HTTP状态预期错误；第4/5轮导出时间戳差异，第6轮为不存在的文档GET路由，第7轮错误假设active档案在启动后自动重应用。最终明确保留原单行启动策略，通过保存的会话模型验证按名密钥读取；没有更改生产启动语义。导出比较未放宽到毫秒、未删除业务时间字段；后续出现差异仍报告字段路径并失败，不把早期失败抹除。

第9轮暴露Python3.9不能解析Go裁去尾零后的五位小数秒；此前纳秒格式也有同类问题。比较辅助函数现在显式补齐/截取微秒并按下一位四舍五入，与原Java Instants测试辅助和PostgreSQL精度一致；新增三项单测覆盖纳秒、跨秒舍入、五位表示、null/一毫秒/业务日期差异及仅忽略exportedAt。第9轮失败日志保留，第10轮用最终比较器完整通过。此职责属于迁移测试辅助，不另造生产时间API。

最新第10轮同一小数据样本三列表（task/session/document）各5次暖机后30次认证回环请求，Java p50为7.776/6.363/4.762ms，Go为1.506/1.936/0.775ms；p95与原始测量范围记录在JSON。暖机后本次进程RSS为Java571120KiB、Go30144KiB。第8轮旧测量留在Git历史。只证明本机单任务/会话/文档、串行轻载观测；Java与Go顺序执行，没有生产吞吐/峰值内存/真实模型语义质量结论，不能以此解除性能或数据发布门禁。
