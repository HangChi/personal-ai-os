# Go 开发与贡献

Go候选与Java基线共存，固定来源为`cb5d694d62fa93742b5789b97273e76d931ce335`。本阶段修改范围是`backend-go/`、`scripts/migration/`、`specs/033-go-rewrite/`和独立Go工作流；原Java、前端业务代码、42条SQL和原部署资产保持不变。阶段记录及代码按可审查的小批次提交，不把阶段完成等同于发布批准。

## 工具与验证

模块最低Go1.27.0，本地验证使用1.27.1。race需要C工具链；PDF需要Poppler的`pdfinfo`/`pdftotext`。基础测试不需要应用凭据：

```bash
cd backend-go
go mod download
go mod verify
go test -race -p 1 -count=1 -timeout=20m ./...
go vet -tags=integration ./...
go build ./...
```

真实设施测试从仓库根目录调用下列脚本。它们创建随机名称/回环端口/合成凭据的临时资源，并在finally只清理自身资源；禁止把既有服务地址或用户备份替换进夹具。

```bash
python3 -m unittest discover -s scripts/migration -p 'test_*.py'
python3 scripts/migration/go_inventory.py check
python3 scripts/migration/go_config_disposition.py
python3 scripts/migration/run_go_postgres_tests.py --tls
python3 scripts/migration/run_go_redis_tests.py --tls --backup
python3 scripts/migration/run_go_s3_tests.py
python3 scripts/migration/compare_go_p6.py
python3 scripts/migration/run_go_demo_seed_tests.py
python3 scripts/migration/run_go_eval_tests.py --fixture
```

设施runner默认复用本机既有离线缓存。Linux/CI必须显式设置绝对路径`GO_TEST_GOCACHE`与`GO_TEST_GOPATH`，先用对应`GOCACHE`/`GOPATH`运行`go mod download`；子进程固定`GOPROXY=off`、`GOTOOLCHAIN=local`。Java Flyway oracle需要JDK21及原Maven依赖缓存，先在backend执行`./mvnw -B -DskipTests -Dapp.dotenv.enabled=false package`。不打印环境、Compose展开结果或带凭据的进程参数。

独立[Go CI](../../.github/workflows/go.yml)执行全包race、integration vet、build、Python脚本/不可变基线检查和真实TLS task/confirmation/P8回归，保留失败日志。它不替代原Java/前端CI、真实浏览器、四设施故障、切换演练和人工发布审查；尚未经远端GitHub运行的配置不能声称远端通过。

演示种子验证复用固定基线`scripts/dev/seed-demo-data.py`的七组数据配方，只替换HTTP/SQL边界。runner自行创建随机TLS库和认证Go进程，校验容器标签/名称/回环端口及业务表为空，禁止原main/reset，没有现有目标参数；用完自动停止并清理。真实回读任务状态/标签、全天/重复日程、记忆置顶、Markdown文档/向量、会话消息、31天合成用量和审计结果。原脚本默认清空现有业务表，不能直接用来验证Go，也不能把本测试当用户库/生产模型/浏览器验收。

评估工具适配需已有固定Java基线构建产物，用于同一新建TLS库的Java/Go上传参数差分。`--fixture`仅使用26份明确合成的重复标题材料和确定性词袋模型，运行原129条查询与50次pure-vector噪声请求；它验证脚本接线，不验证原文章或真实模型质量。适配器禁止原重建main/delete_all、现有目标参数和任意文件上传，所有命中须属于本次清单。噪声工具的0建议值只是合成模型观测，不修改原0.007判平阈值或默认0.3检索阈值。

`python3 scripts/migration/run_go_eval_tests.py --corpus-dir /absolute/provided-corpus`只读检查26个明确`juejin-<post_id>.md`文件的长度/UTF-8/链接与SHA；不提供目录时退出2/BLOCKED，不隐式搜索/tmp。即使文件齐全也只记FILES_PRESENT_NOT_AUTHENTICATED，不保证文章来源或真实模型质量，不自动运行采集/线上评估。真实验收应另外准备原全文、授权的模型环境和Java/Go质量对照，继续保留发布门禁。

用户明确授权后可采集固定原清单的掘金公开正文，再用独立模式验证实际上传/切分/嵌入/落库/检索。采集器只创建新私有目录，不覆盖原语料；默认6篇，最多26篇，每篇间隔3秒，HTTPS验证/大小/时间均有界，遇无效正文立即停止。全文只保留在仓库外供本地测试，禁止提交或传播；来源、抓取时间、页面标题与哈希写入 manifest。命令中的目录必须是新的绝对路径且父目录真实存在：

```bash
python3 scripts/migration/fetch_go_juejin_corpus.py --limit 6 --output-dir /private/tmp/personalos-juejin-new-corpus
python3 scripts/migration/run_go_eval_tests.py --downloaded-corpus-dir /private/tmp/personalos-juejin-new-corpus
```

采集不完整时退出2/PARTIAL，已保存且通过哈希检查的正文仍可用于第二条命令；不得为凑数跳过错误页或绕过站点保护。独立模式只运行答案文档全部齐备的原查询，缺失查询明确计数，绝不缩减答案列表；使用真实TLS库/API及本地确定性词袋模型，不需要用户模型密钥。它验证正文进入嵌入请求及向量存储，不证明真实模型语义质量或历史语料身份。下载模式把安全元数据/结果写入新的`p8-eval-juejin-<时间>.json`，保留原合成报告。2026-11-04实际16篇/142向量/84查询与50次噪声请求的两轮结果见[P8记录](../../specs/033-go-rewrite/p8-completion.md)。

正常浏览器可显示全文而直接HTTP无法取得正文时，可从实际已呈现的`article-viewer`只读导出HTML和来源收据；不得绕过验证码/登录/保护，不读取既有浏览器资料。导出文件`rendered-<post_id>.html`应保留观察到的title/canonical及正文容器，收据`receipt-<post_id>.json`记录post_id/url/canonical/title/captured_at/path/html_sha256，`acquisition_method`为`normal-browser-rendered-article-viewer`。这是渲染后DOM，不得冒称原始HTTP响应或历史快照。

```bash
python3 scripts/migration/prepare_go_juejin_corpus.py import-rendered \
  --input-dir /absolute/private-browser-export \
  --post-id 7591207451778875418 7472359618326052901 7672711130193936424 7677521340606300170 \
  --output-dir /absolute/new-imported-corpus
python3 scripts/migration/prepare_go_juejin_corpus.py merge \
  --corpus-dir /absolute/previous-acquisition \
  --corpus-dir /absolute/new-imported-corpus \
  --output-dir /absolute/new-full-corpus
```

两命令均离线，只读显式来源、创建新0700目录/0600文件；校验原提取器SHA、URL/canonical、文件和收据哈希，拒绝重复/未知ID/链接路径/标题漂移。合并保留原字节和时间、按原清单排序，再经原loader回读；覆盖不足仍返回2/PARTIAL。2026-11-04最后四篇已取得，26篇/210264正文字符完整，129条原查询材料齐备、零排除；来源和哈希见`p8-corpus-complete-20261104-231937.json`。先前16篇/84题、22篇/111题都是保留的历史范围，不代表新全量模型验收自动通过。

## 获准真实模型的隔离验收

只有用户明确授权后才运行此模式；指定模型配置文件，只读取聊天/嵌入provider、OpenAI兼容端点/密钥/模型名和thinking开关，系统中相同模型键优先，绝不加载文件内数据库、认证或通知配置。当前入口要求显式配置的两个provider均为openai、HTTPS且URL无凭据/查询；不猜模型名或回退模拟结果。

```bash
python3 scripts/migration/run_go_real_eval_tests.py \
  --model-env-file /absolute/project/.env \
  --downloaded-corpus-dir /absolute/downloaded-corpus
```

预检真实1024维嵌入与非空聊天后，Java和Go各使用新随机TLS数据库、原42迁移及认证API，执行完整答案覆盖的原查询、两次传统知识问答引用核验和50次纯向量噪声采样。嵌入缓存关闭，引用内容必须对应数据库真实分块SHA；不会修改原标注、判平阈值或默认相似度门槛。长文嵌入保守按16块分批，任一批失败不得进入最终写事务。

默认`--mode retrieval`；另加`--mode multistage`只验证固定原问题“BRIN 索引适合什么场景”的完整前台流程。两端均重新入库并核验模型输入，要求READY、recall/rewrite/rerank/evidence全OK、零warning、恰好四次前台chat调用及精确持久引用；隔离进程显式关闭后台入库审查，避免污染计数，不改变应用默认值。该模式不跑直接检索/噪声套件，不生成检索比较；报告明确1题执行、83道材料齐备题未执行和45题缺材料。2026-11-04双端通过见`p8-eval-real-20261104-224539.json`；首轮多调用失败保留，完整多阶段质量和后台审查仍未验收。

完整26篇材料另已跑过双端129条原查询、各两次传统问答/持久引用及50次噪声请求（`p8-eval-real-20261104-232203.json`），241向量/真实Java格式预期输入SHA核验通过。R@5同96.90%、各四条miss，严格MRR Go0.901421/Java0.900775，原判平MRR Go0.907623/Java0.908915；独立新复核保持质量未批准，旧子集结果不覆盖。完整库BRIN多阶段报告`p8-eval-real-20261104-232433.json`各四阶段/四次调用/引用通过，明确1题执行、128题未执行、零缺材料；上述83/45只属于旧16篇范围。所有模式按传入清单实际覆盖计数，不能用材料已齐或单题通过代替完整RAG/事实审查。

另加`--mode no-evidence`运行一个补充负例“量子引力理论能否解释黑洞信息悖论的最终答案是什么？”，不是原标注查询。每端仍重新上传16篇并核验142向量；关闭隔离进程后台审查，明确执行原查询0条、84条材料齐备题未执行、45条缺材料，不跑检索/噪声套件或生成质量比较。严格要求answered=false、空引用/命中、固定无内容答复、INSUFFICIENT、四阶段全OK/零warning及恰好三次前台chat，不能调用最终回答模型。

```bash
python3 scripts/migration/run_go_real_eval_tests.py \
  --model-env-file /absolute/project/.env \
  --downloaded-corpus-dir /absolute/downloaded-corpus \
  --mode no-evidence
```

默认首个失败即停止；如需独立Java对照，显式加`--backend java`，不重试Go去寻找通过样本。2026-11-04 Go报告`p8-eval-real-20261104-230130.json`与独立Java报告`p8-eval-real-20261104-230340.json`均FAIL/实际退出1：四阶段均OK、无warning、三次前台chat、answered=false、无引用或命中，但实际为CLARIFY，未满足严格INSUFFICIENT契约。保留失败，不把相同安全澄清行为认定为通过或已证明的业务缺陷；分类语义和事实验收继续开放。后续新增的安全计数/哈希字段不回填旧报告；回答与澄清正文仍仅私有本地留存。

默认比较两端；可加`--backend java`或`--backend go`单独定位，但单端报告不生成对照结论。代理只允许精确的`/embeddings`、`/v1/embeddings`及对应chat路径；Java的base64向量保持请求/响应原格式，只在代理中按little-endian float32解码验证1024维/有限/非零，不改写Java基线或拆分其原有批次。原Java超出提供商批次限制时应如实失败。

OpenAI知识文档输入现遵循固定Spring AI 2.0.1默认EMBED格式，包含标题/分块序号/文件名/文档ID；Ollama与普通查询字符串仍保持原行为。两端预期输入均由真实Java依赖oracle逐分块生成SHA，与各自本轮成功的真实嵌入回执比较，数据库原文/引用不变。不对已有库自动重建或重写向量。

完整报告的`result=PASS`仅表示该轮执行完成，质量需同时审阅严格与原判平指标。可运行`python3 scripts/migration/review_go_real_eval.py --report specs/033-go-rewrite/p8-eval-real-20261104-222729.json --write`生成同时间戳的新`p8-real-model-review-<时间>.json`；也可显式`--output-file`指定规格目录内新文件，已有文件拒绝覆盖。旧`p8-real-model-review.json`保留。不请求模型、不改旧报告，拒绝失败/mock/multistage单题/no-evidence负例/指标或覆盖数漂移/原标注变更。报告按实际文档/原查询数区分子集与完整当前语料，二者都不批准历史身份、事实质量或发布。旧16篇实测两端R@5=100%，严格MRR Go0.937500/Java0.943452；原0.007判平后同0.946429，原严格退化保留。

真实密钥只在独立验收/传输进程内存中，通过标准输入交给支持`--use-env-proxy`的Node运行时，使用内置fetch复用HTTPS连接；不会放入进程参数、子应用环境或文件。子应用仅持随机回环代理令牌。默认上限600请求、12聊天请求、100万输入字符、每次聊天最多1024输出token、上游90秒；不自动重试、不跟随重定向，保留证书验证。此为请求/用量上限，不等于金额承诺，报告记录提供商实际返回的token用量。完整回答和引用仅保存于新0700本地证据目录；仓库报告仅含来源/哈希/统计/原查询排序。每次失败另存，不覆盖旧报告；受控子集通过仍不自动认证历史26篇语料、多阶段RAG、事实正确性或发布门禁。

## 变更规则

从固定Java断言推导Go期望值，不能从Go实现生成自己的期望。原Java测试文件及1119个声明逐项记账；补充真实副作用、事务/查询数、失败/取消与并发证据。跳过、观察项和同Java基线失败都必须保留，不能当成PASS。

依赖固定版本并更新`THIRD_PARTY_NOTICES.md`；新增配置要补来源、默认值、支持/拒绝及脱敏测试。使用实际事务和租约，不通过重试未知写结果掩盖故障。测试先确认可重复的独立数据边界，禁止全库清理。完整完成检查为`go_inventory.py check-complete`，未verified项或任一未通过发布门禁必须使其失败。

架构与接口见[architecture.md](architecture.md)，安全规则见[security.md](security.md)，本地/Compose/监控/备份运维见[部署说明](../deploy/README.md)。
