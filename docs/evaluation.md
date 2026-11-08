# 验证与评测指南

工程回归、检索排序、答案质量、Agent 行为和生产发布各自回答不同问题。Java/Go 应使用同一题集、配置、知识快照和明确版本比较；旧报告不因代码更新而成为当前认证。入口：[开发指南](development.md)、[双版本差异](backend-parity.md)、[报告索引](reports/README.md)。

## 选择验证层次

| 层次 | 能证明什么 | 不能证明什么 | 入口 |
| --- | --- | --- | --- |
| 单元/集成回归 | 状态、事务、协议、预算、防重、确认、降级 | 真实模型语义质量 | Java Maven、Go race/设施 runner、前端六项检查 |
| 确定性 mock/fixture | 接线、持久化、SSE 与固定规则 | 嵌入阈值或模型工具选择准确率 | [开发 mock](development.md)、Go eval `--fixture` |
| 基础检索 | 可接受文档排序、R@K、MRR、多样性 | 正确分块、证据充分、答案正确 | `retrieval-eval.py`、双端隔离 retrieval 模式 |
| 多阶段 RAG | 状态、阶段、降级、来源/引用与调用数 | 所有陈述真实或普遍质量 | project/multistage/conflict/no-evidence 模式 |
| Agent | 工具选择、真实写入、确认、闲聊、长历史 | 任意任务泛化或生产容量 | `agent-eval.py`、双端 original agent 模式 |
| RAGAS | 基于固定裁判/参考的陈述与上下文指标 | 人工盲审、无偏裁判、生产批准 | `ragas-compare.py` |
| 运维/发布 | TLS、恢复、故障、浏览器、未知副作用 | 单元测试可以替代人工验收 | [Go P8](../specs/033-go-rewrite/p8-completion.md)、[运维](operations.md) |

## 无模型的工程检查

基础命令见[开发指南](development.md)。评测器自身也有离线测试：

```bash
python3 -m unittest discover -s scripts/eval -p 'test_*.py'
python3 -m unittest discover -s scripts/migration -p 'test_*.py'
python3 scripts/docs/check_docs.py
python3 scripts/docs/generate_routes.py --check
```

设施 runner 创建自己的临时数据库/Redis/S3，不读取已有服务配置；详细准备见 [Go 开发](../backend-go/docs/development.md)。全包测试、单个测试和 CI 实际运行必须分别报告，不用“已配置 CI”代替“CI 通过”。

## 基础检索质量

[queries.py](../scripts/eval/queries.py)保留 129 题的原标注与可接受来源。`retrieval-eval.py` 输出 R@1/3/5、严格 MRR、分组和文档多样性；`noise-floor.py` 观察分数漂移。RRF 排序不与 cosine 单调时，不能按原向量分数随意判平。历史 0.007 判平约定只是当轮评测口径，不能直接作为产品阈值。

`rebuild-corpus.py` 会先删除目标知识库全部文档；`seed-demo-data.py` 也可能重置业务表。两者只能对明确可丢弃的隔离库使用。不要照抄旧报告中的默认地址执行。文档数量一致不等于语料字节/文章来源一致；采集需有来源、时间、哈希、访问许可，不绕过登录/验证码/保护。

Go 的 `run_go_eval_tests.py --fixture` 是合成材料接线；真实对照使用 `run_go_real_eval_tests.py`，同输入向量对照使用 `run_go_paired_eval_tests.py`。前者包含独立模型/索引波动，后者重放同向量仅隔离检索实现，不能混成同一质量结论。具体有界配方与历次失败保留在 [Go 验证记录](../backend-go/docs/validation-history.md)。

## 多阶段 RAG 与引用

评测至少同时核查：

1. 原问题、参考来源和语料快照先固定，不在看见回答后改标注。
2. 基础候选与最终块分别记录；文档 Hit@5 不保证选中包含精确答案的分块。
3. READY/INSUFFICIENT/CONFLICT/CLARIFY/UNVERIFIED 与 `answered`、warning、阶段状态一致。
4. 引用能映射真实持久块及正确序号；响应回退携带 sources 不等于正文显式引用正确。
5. 上游调用、模型名、输入格式、重排配置、缓存/后台审查和预算真实记录；不同路径不能一律要求四次调用。
6. 不足/冲突/期间不明应拒答或澄清，不凭安全拒答就认定严格分类断言通过。

`scripts/eval/rag-live-eval.py`、`rag-live-conflicts.py`、`rag-live-ingestion.py` 各有固定项目/政策场景及环境假设，先审阅目标和清理逻辑；双端隔离包装在 `scripts/migration/run_go_*`。单题通过不代表完整 129 题多阶段或个人资料验收。既有四阶段通过但状态分类失败等结果必须保留。

## Agent 真实行为

在明确的专用测试实例、工具权限和模型预算下运行：

```bash
python3 scripts/eval/agent-eval.py --output /absolute/private/agent-eval.json
python3 scripts/eval/agent-eval.py --long-history --output /absolute/private/agent-eval-long.json
```

使用 `AGENT_EVAL_COOKIE` 提供登录 Cookie，不写入报告。评测创建带唯一前缀的会话/任务并清理自身数据，不能限制 Agent 配置中已开放的外部工具能力。知识场景缺嵌入等错误计入失败，不静默跳过。它检查工具选择、闲聊不调工具、破坏性操作先确认和实际落库，并记录成功率、失败/调用数、P50/P95 和输入用量。长历史额外调用模型；输入 token 可能为估算且不包含所有后台/输出成本。

双端原场景由 `run_go_original_eval_tests.py --mode agent` 包装。Go 代理将真实非流式帧转为本地 SSE 的实验不证明上游首字延迟；保留原 Java 预算保护失败与跳过用例，不能挑选绿色结果。边界见[可靠性](agent-reliability.md)。

## RAGAS：采集、裁判、汇总分开

[ragas-compare.py](../scripts/eval/ragas-compare.py)提供 `collect`、`judge`、`summarize`。先阅读脚本：它依赖已有知识快照、语料清单、本机数据库/搜索设施和可读取的模型配置，不是全新环境的一键安装；会把知识及模型配置复制进隔离环境，必须明确允许这些读取与外部请求。不要在未知环境直接运行。

获准并完成前置条件后，指定本次新建的私有输出目录及实际产物：

```bash
python3 scripts/eval/ragas-compare.py collect \
  --output /absolute/private/new-ragas-run \
  --corpus-dir /absolute/private/corpus \
  --java-jar /absolute/build/backend.jar --go-binary /absolute/build/personalos
python3 scripts/eval/ragas-compare.py judge --output /absolute/private/new-ragas-run
python3 scripts/eval/ragas-compare.py summarize --output /absolute/private/new-ragas-run
```

collect 完成后才 judge，避免裁判流量污染响应延迟。裁判依赖版本见 [requirements-ragas.txt](../scripts/eval/requirements-ragas.txt)；可用 `--judge-model` 显式指定不同裁判。记录裁判与生成模型是否相同、参考答案盲审情况、失败/补评及配对区间。Factual Correctness F1 不是答对题数百分比；Faithfulness 高也可能是保守回答、遗漏关键数值。

[2026-11-06 报告](rag-evaluation-20261106.md)记录 40 题 × 双端问答、34 道可回答题的四项 RAGAS 和独立补评。它保留“命中文档却漏选具体块”、同模型裁判偏差和未完成项，不能推导语言普遍优劣。

扩充语料和增加干扰项时，可使用 [knowledge-adversarial-corpus.py](../scripts/eval/knowledge-adversarial-corpus.py) 与 [knowledge-adversarial-eval.py](../scripts/eval/knowledge-adversarial-eval.py)，具体步骤见[运行说明](../scripts/eval/README.md#新增技术资料与干扰语料)。先冻结参考事实和来源锚点，再采集回答；合并两组候选与生产流程最终证据，逐块判断相关、不相关或未判定。选中证据精度使用实际完成判定的选中分块作分母，另报判定覆盖率与空上下文；事实召回衡量冻结参考事实的覆盖，不是全库穷尽相关文档召回。已知来源 P@1/R@5 只作排序诊断。

相近技术资料可能部分支持答案，不能按来源分组一律标负。无关内容也必须实际进入候选池或上下文，才构成相应暴露；没有暴露不能证明抗干扰能力。若引用不属于当前分块，拒绝该裁判标签；补评保留原始失败，不能放宽原文锚点校验来提高覆盖率。

[2026-11-08 后续报告](reports/knowledge-adversarial-2026-11-08.md)在2050文档快照执行主集60题及近邻专项8题，分别呈现重排消融与完整生产流程；保留宏/微证据精度、判定覆盖、事实召回、五项RAGAS、真实干扰曝光、结构补评和覆盖门禁失败，不能从未曝光的文学噪声推断强抗干扰能力。

## 每轮报告的最少信息

提交/构建哈希、时间/时区、Java/Go 配置、语料/题集哈希、样本总数/执行/缺失/跳过、模型与实际嵌入输入、缓存和 Worker、调用/预算/费用边界、每项失败与清理结果。完整回答、个人资料、密钥和原始配置留在私有证据中；仓库保留脱敏哈希/统计/可复验步骤。
