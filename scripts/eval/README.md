# 评测脚本入口

当前方法与运行前提见[评测指南](../../docs/evaluation.md)，双端隔离包装与历史证据见[Go 验证记录](../../backend-go/docs/validation-history.md)。本目录不是默认对现有实例运行的测试套件。

| 脚本/数据 | 作用与范围 |
| --- | --- |
| `agent-eval.py` | 工具选择、真实落库、人工确认、闲聊和可选长历史；会调用模型并创建测试数据 |
| `queries.py`、`retrieval-eval.py` | 原 129 题可接受来源与基础排序指标，依赖匹配的完整语料 |
| `noise-floor.py` | 重复请求分数漂移，不是完整业务性能 |
| `rebuild-corpus.py` | **先删除目标知识库全部文档**；仅可丢弃隔离库 |
| `fetch-corpus-*.py/js`、`to-markdown.py` | 旧语料采集/转换；只用于允许访问的公开材料，不绕过登录/验证码/保护 |
| `rag-live-eval.py`、`rag-live-cases.json` | 固定项目文档的基础/增强对照；依赖既有本机设施与配置 |
| `rag-live-conflicts.py`、`rag-live-ingestion.py` | 合成政策冲突/澄清与后台审查；先核查其隔离状态和目标 |
| `ragas-compare.py`、`ragas-comparison-cases.json` | 同知识快照双端采集、RAGAS 裁判、离线汇总 |
| `fetch-juejin-scale.py` | 只采公开掘金正文；遇访问限制立即停止，正文与抓取回执放私有缓存 |
| `knowledge-scale.py` | 冻结官方/博客正文并经后台任务追加到目标数量；检查原文档哈希、分块、来源与投影 |
| `knowledge-scale-eval.py`、`knowledge-scale-cases.json` | 同候选池 RRF / 重排模型消融；文档 P/R、排序、回答、引用、延迟、RAGAS 与配对区间 |
| `requirements-ragas.txt` | 裁判依赖，建议专用虚拟环境 |
| `requirements-knowledge-scale.txt` | 语料采集/转换加 RAGAS 的依赖版本 |
| `test_*.py` | 评测器离线回归，不请求真实模型 |

```bash
python3 -m unittest discover -s scripts/eval -p 'test_*.py'
```

真实模型命令、认证、外部费用、调用上限、清理和报告口径见[评测指南](../../docs/evaluation.md)。首次先检查每个脚本的参数和环境假设；不是所有脚本都可只换一个 URL 就安全使用。原种子/重建脚本会删除数据，优先用 `scripts/migration/run_go_*` 创建临时资源的包装。

历史调参表已迁到[2026-10-26 基线](../../docs/reports/retrieval-baseline-2026-10-26.md)，不能与新语料/模型结果混算。较新的双端实测见[2026-11-06 RAG 报告](../../docs/rag-evaluation-20261106.md)。资料、题集、失败、补评和版本范围必须随报告保留；旧 PASS 不自动认证当前代码。

## 1000 文档追加与重排消融

先运行各脚本的 help 查看来源归档参数。prepare 冻结正文及哈希，import 可用原目录恢复；不会清空或替换已有文档。采集器因访问限制停止后，不重启它以尝试绕过限制。原文仅供本地使用，不提交到仓库。

完成入库并核验总数后，在独立目录冻结题集，再复制到临时数据库和搜索索引评测。示例中模型请求会产生费用：

冻结快照前应暂时暂停会修改来源状态的后台资料审查，并在结束后恢复原设置；否则快照一致性检查会拒绝继续。collect 自身不会改现有实例的配置。发生预检失败时先检查清理状态；仅在尚无任何候选或回答且已完成清理时，允许归档旧元数据后重做预检。一旦产生测评数据，禁止换快照、模型、产物或题集后续跑。

~~~bash
.cache/ragas-venv/bin/python scripts/eval/knowledge-scale.py import \
  --output .cache/knowledge-1000-20261107 --inflight 6
.cache/ragas-venv/bin/python scripts/eval/knowledge-scale-eval.py prepare \
  --output .cache/knowledge-1000-eval-20261108
.cache/ragas-venv/bin/python scripts/eval/knowledge-scale-eval.py collect \
  --output .cache/knowledge-1000-eval-20261108 --java-jar /absolute/path/backend.jar
.cache/ragas-venv/bin/python scripts/eval/knowledge-scale-eval.py judge \
  --output .cache/knowledge-1000-eval-20261108 --concurrency 4
.cache/ragas-venv/bin/python scripts/eval/knowledge-scale-eval.py summarize \
  --output .cache/knowledge-1000-eval-20261108
~~~

collect 只在本机隔离实例注册评测入口，复用业务召回与问答提示词；不会把入口装入产品 JAR。两组共享一次召回的最多 40 个分块候选，只改变排序，再按相同多样性规则选择最多 5 个上下文。语义题与标题定位题分别统计。完整 RAG 的改写、证据精审、冲突治理和拒答守卫不属于该消融，不能用关闭 reranker 开关充当无重排组：现有 pipeline 在无交叉编码器时仍会调用聊天模型重排。

P/R 的相关性标签是已知支持文档，非穷尽人工判断；按文档去重后使用固定 k 分母。RAGAS 对语义正例计算 Faithfulness、FactualCorrectness F1、ContextPrecision、ContextRecall、AnswerRelevancy（strictness=3，使用当前嵌入模型）；报告有效样本和失败。无答案题单独统计文本拒答，不能当生产守卫状态通过。JSONL 支持中断恢复，保留原失败及成本记录；完成采集后才运行裁判，避免影响延迟。

精度的 k 按任务选择，不固定为 5。本题集以 P@1 表达首位已知支持来源命中，辅以 R@5、MRR/nDCG；它们不等于答案正确率。由于多数题只标一篇来源，P@3/P@5 仅作为不完整标签下的诊断项；不通过调整分母把它们改成另一种“精度”。若要测前五篇的实际相关比例，需要独立标注两组的合并候选并区分未判定文档。上下文最多 5 个分块与排序前 5 篇文档也不是同一单位。

## 新增技术资料与干扰语料

knowledge-adversarial-corpus.py 使用独立私有目录。discover-juejin-sitemap.py 从公开站点地图补充入口，再采完整正文和真实来源；采集上限应高于入库配额，为非技术文章、重复来源和不可索引正文留出筛选余量。已有索引卡指向的文章也按已有来源排除。分类是模型对标题、正文前 2500 字和后 1000 字的筛选，不能称为人工审核；正文自身完整保存。

~~~bash
.cache/ragas-venv/bin/python scripts/eval/discover-juejin-sitemap.py \
  --output .cache/NEW-RUN/seeds.json --sample 4000
.cache/ragas-venv/bin/python scripts/eval/fetch-juejin-scale.py \
  --output .cache/NEW-RUN/juejin --seeds .cache/NEW-RUN/seeds.json \
  --limit 1650 --pause 1.5
.cache/ragas-venv/bin/python scripts/eval/knowledge-adversarial-corpus.py noise --output .cache/NEW-RUN
.cache/ragas-venv/bin/python scripts/eval/knowledge-adversarial-corpus.py classify --output .cache/NEW-RUN
~~~

noise 从公开古典文学整理语料中选出 20 篇独立完整作品；不是把一本书随意切成 20 个文档。另选 30 篇真实技术近似资料，实际相关性仍须逐题判断，不能把整个“易混淆”分组全标成负例。基准角色仅留在私有清单；产品来源元数据使用正常技术/文学范围。

冻结前用当前构建的类和依赖编译运行 java/KnowledgeCorpusCheck.java，复用产品分块器和嵌入批处理器，不请求模型。检查参数须与产品分块配置相同，本次使用 1200/350/0。把 JSON 行和诊断保存为输出目录的 chunk-preflight.log；冻结器会要求使用哈希匹配且本地批处理验证通过的正文。过长代码块仍保留在原始缓存，不能截断后冒充完整文档入库。

默认 freeze 配额为 1000 篇新技术正文、30 篇技术近似资料、20 篇文学作品；目标总数由现有文档数自动加算。可以用 technical-count/hard-count/literature-count 和 batch 分批执行，后续批次会排除已入库来源。import 仍使用 knowledge-scale.py。只有所有批次核验成功后才 merge；merge 强制核验总配额 1000/30/20 和原文档、分块内容、计数及搜索投影。

若冻结后发现个别正文无法索引，先检查任务终态与实际持久化状态。reconcile 仅在离线检查明确失败、原文哈希匹配且目标文档不存在时创建新的派生批次；保留原计划、失败任务和原始正文，绝不删除成功文档。未导入正文不计入新增数，并由后续批次补足数量。

## 定制问答、证据相关性与完整生产流程

knowledge-adversarial-eval.py prepare 冻结 60 题：24 技术、12 近似资料混淆、4 多来源、8 无答案、4 缺少适用条件、4 文学对照和4 历史回归。新参考答案逐事实附原文锚点，再由独立模型检查；模型标签不能称为人工金标。技术问题按主题分层抽样，报告必须记录实际抽样母集，不能声称代表全部文档。问答及原文锚点不提交到仓库。

可以在首个已验证批次上先生成题目，并指定 expected-documents 为最终总数。此时禁止采集回答；追加完成后必须 bind，核对来源 ID、原文哈希、无答案实体和最终总数。bind 不改问题、参考答案或来源标签。此方法缩短等待，但题目抽样母集仍是生成时已完成的批次。

~~~bash
.cache/ragas-venv/bin/python scripts/eval/knowledge-adversarial-eval.py prepare \
  --output .cache/NEW-RUN/eval --corpus .cache/NEW-RUN/import
.cache/ragas-venv/bin/python scripts/eval/knowledge-scale-eval.py collect \
  --output .cache/NEW-RUN/eval --java-jar /absolute/path/current-backend.jar \
  --include-pipeline --preserve-temperature --context-limit 4
.cache/ragas-venv/bin/python scripts/eval/knowledge-adversarial-eval.py audit \
  --output .cache/NEW-RUN/eval --corpus .cache/NEW-RUN/import --concurrency 4
.cache/ragas-venv/bin/python scripts/eval/knowledge-scale-eval.py judge \
  --output .cache/NEW-RUN/eval --judge-model deepseek-v4.1-flash --trace-judge --concurrency 4
.cache/ragas-venv/bin/python scripts/eval/knowledge-adversarial-eval.py summarize \
  --output .cache/NEW-RUN/eval --corpus .cache/NEW-RUN/import
~~~

context-limit 应与待测服务的实际设置一致，本次是 4；它是上下文上限，不是相关性精度的固定分母。baseline/rerank 共享最多 40 块原问题候选，只改变排序。production 单独调用真实 knowledge/ask 接口，保留改写、候选合并、重排、证据审查、冲突和拒答流程，不能把它解释为只开关重排的消融。三组均保留当前生成温度；候选缓存与嵌入缓存关闭。

audit 合并共享候选与生产流程最终证据，对每个分块独立判断 relevant/irrelevant/uncertain，相关事实必须有匹配原文的锚点。仅格式差异可忽略，标识符下划线、大小写、数字和标点仍保留。精度为已判相关块/已完成判定的选中块，另报判定覆盖率；空上下文不是完美精度。证据召回是冻结参考事实的覆盖率；候选池相关块召回仅限已判候选池，均不是全库穷尽相关文档召回。首位已知来源命中、R@5/MRR 只作额外诊断。

回答审查逐项记录事实、支持原文、引用编号和行为；支持且与问题相关的事实/全部事实为回答事实精度，覆盖参考事实/全部参考事实为事实召回。拒答、澄清、条件化回答、冲突与系统 UNVERIFIED 分开报告。诗作对照题里文学不是噪声，技术题里的文学暴露才计入无关内容指标。

RAGAS 保留 Faithfulness、FactualCorrectness F1、ContextPrecision、ContextRecall、AnswerRelevancy 原值；trace-judge 额外保存事实拆解、NLI 和反向问题，错误与补评历史不覆盖。汇总分别列有效样本、失败、零分及配对区间。它们不是人工答案准确率。完整生产流程可在隔离库写入冲突记录，核心文档/向量/来源/空间及现有实例必须保持不变；采集结束验证并清理临时数据库、索引和进程。
