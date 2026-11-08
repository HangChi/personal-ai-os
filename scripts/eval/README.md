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
