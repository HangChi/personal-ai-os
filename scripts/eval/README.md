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
| `requirements-ragas.txt` | 裁判依赖，建议专用虚拟环境 |
| `test_*.py` | 评测器离线回归，不请求真实模型 |

```bash
python3 -m unittest discover -s scripts/eval -p 'test_*.py'
```

真实模型命令、认证、外部费用、调用上限、清理和报告口径见[评测指南](../../docs/evaluation.md)。首次先检查每个脚本的参数和环境假设；不是所有脚本都可只换一个 URL 就安全使用。原种子/重建脚本会删除数据，优先用 `scripts/migration/run_go_*` 创建临时资源的包装。

历史调参表已迁到[2026-10-26 基线](../../docs/reports/retrieval-baseline-2026-10-26.md)，不能与新语料/模型结果混算。较新的双端实测见[2026-11-06 RAG 报告](../../docs/rag-evaluation-20261106.md)。资料、题集、失败、补评和版本范围必须随报告保留；旧 PASS 不自动认证当前代码。
