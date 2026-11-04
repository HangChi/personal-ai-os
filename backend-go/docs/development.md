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

## 获准真实模型的隔离验收

只有用户明确授权后才运行此模式；指定模型配置文件，只读取聊天/嵌入provider、OpenAI兼容端点/密钥/模型名和thinking开关，系统中相同模型键优先，绝不加载文件内数据库、认证或通知配置。当前入口要求显式配置的两个provider均为openai、HTTPS且URL无凭据/查询；不猜模型名或回退模拟结果。

```bash
python3 scripts/migration/run_go_real_eval_tests.py \
  --model-env-file /absolute/project/.env \
  --downloaded-corpus-dir /absolute/downloaded-corpus
```

预检真实1024维嵌入与非空聊天后，Java和Go各使用新随机TLS数据库、原42迁移及认证API，执行完整答案覆盖的原查询、两次传统知识问答引用核验和50次纯向量噪声采样。嵌入缓存关闭，引用内容必须对应数据库真实分块SHA；不会修改原标注、判平阈值或默认相似度门槛。长文嵌入保守按16块分批，任一批失败不得进入最终写事务。

默认比较两端；可加`--backend java`或`--backend go`单独定位，但单端报告不生成对照结论。代理只允许精确的`/embeddings`、`/v1/embeddings`及对应chat路径；Java的base64向量保持请求/响应原格式，只在代理中按little-endian float32解码验证1024维/有限/非零，不改写Java基线或拆分其原有批次。原Java超出提供商批次限制时应如实失败。

OpenAI知识文档输入现遵循固定Spring AI 2.0.1默认EMBED格式，包含标题/分块序号/文件名/文档ID；Ollama与普通查询字符串仍保持原行为。两端预期输入均由真实Java依赖oracle逐分块生成SHA，与各自本轮成功的真实嵌入回执比较，数据库原文/引用不变。不对已有库自动重建或重写向量。

完整报告的`result=PASS`仅表示子集执行完成，质量需同时审阅严格与原判平指标。可运行`python3 scripts/migration/review_go_real_eval.py --report specs/033-go-rewrite/p8-eval-real-20261104-222729.json --write`生成独立`p8-real-model-review.json`；不请求模型、不改旧报告，拒绝失败/mock/指标漂移/原标注变更。2026-11-04实测两端R@5=100%，严格MRR Go0.937500/Java0.943452；原0.007判平后同0.946429。复核明确记录严格退化及未批准质量，不能用执行PASS解除门禁。

真实密钥只在独立验收/传输进程内存中，通过标准输入交给支持`--use-env-proxy`的Node运行时，使用内置fetch复用HTTPS连接；不会放入进程参数、子应用环境或文件。子应用仅持随机回环代理令牌。默认上限600请求、12聊天请求、100万输入字符、每次聊天最多1024输出token、上游90秒；不自动重试、不跟随重定向，保留证书验证。此为请求/用量上限，不等于金额承诺，报告记录提供商实际返回的token用量。完整回答和引用仅保存于新0700本地证据目录；仓库报告仅含来源/哈希/统计/原查询排序。每次失败另存，不覆盖旧报告；受控子集通过仍不自动认证历史26篇语料、多阶段RAG、事实正确性或发布门禁。

## 变更规则

从固定Java断言推导Go期望值，不能从Go实现生成自己的期望。原Java测试文件及1119个声明逐项记账；补充真实副作用、事务/查询数、失败/取消与并发证据。跳过、观察项和同Java基线失败都必须保留，不能当成PASS。

依赖固定版本并更新`THIRD_PARTY_NOTICES.md`；新增配置要补来源、默认值、支持/拒绝及脱敏测试。使用实际事务和租约，不通过重试未知写结果掩盖故障。测试先确认可重复的独立数据边界，禁止全库清理。完整完成检查为`go_inventory.py check-complete`，未verified项或任一未通过发布门禁必须使其失败。

架构与接口见[architecture.md](architecture.md)，安全规则见[security.md](security.md)，本地/Compose/监控/备份运维见[部署说明](../deploy/README.md)。
