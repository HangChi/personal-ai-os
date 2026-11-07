# Java / Go 共用测试夹具

`agent-rag/` 保存两版后端共用的 Agent 与 RAG 行为契约，共 164 个用例。所有内容均为合成数据；模型输出直接由测试桩返回，不调用真实模型。夹具描述输入与预期结果，业务校验仍由各版实际服务执行。

| 文件 | 用例数 | 验证内容 |
| --- | ---: | --- |
| `citation-audit.json` | 45 | 断言结构、证据编号、引文锚定、重复断言、支持状态与上限 |
| `evidence-review.json` | 40 | 证据完整性、相关性分级、冲突引文及来源有效期 |
| `restored-conflict-scope.json` | 32 | 恢复冲突的范围判定、严格类型校验、原始证据保留 |
| `plan-transitions.json` | 28 | 计划状态迁移、终态保护、幂等、完成凭据 |
| `questions.json` | 19 | 结构化提问、选项长度、重复标签、执行状态约束 |

Java 消费者位于 `backend/src/test/java/com/personalos/` 下的 `knowledge/rag`、`agent/plan` 和 `agent/interaction`；Go 消费者位于 `backend-go/internal/business/` 下的 `knowledge` 和 `chat`。各版另有针对本地类型与工具回执存储的测试，不计入上述共享用例数。新增或调整共享契约时，应同步验证两版，不能只修改其中一版的预期。

## 业务链路回归

除上述共享 JSON 外，扩展测试直接执行本地业务服务，覆盖运行恢复、工具执行、摘要、检索降级和附件边界，不把重复输入数量当作新增覆盖率。

| 范围 | Java 测试 | Go 测试 |
| --- | --- | --- |
| Agent 调度与恢复 | `ChatRunServiceTest` | 既有执行/恢复集成测试、`engine_contract_test.go` |
| 任务工具与危险操作 | 既有任务工具/人工确认测试 | `task_execution_test.go`：真实任务与确认服务、确认前无写入、重复确认无重放 |
| RAG 检索与治理 | `RagPipelineBoundaryTest`、`KnowledgeRetrievalServiceTest`、`KnowledgeGovernanceBoundaryTest`、`RagResultContractTest` | 共享证据契约、`engine_contract_test.go`：证据保护与网页回退 |
| 摘要并发保护 | 既有摘要服务测试 | `summary_contract_test.go`：序号/消息身份校验，旧摘要不得覆盖新状态 |
| 附件 | `ChatAttachmentJanitorTest` | `attachments_contract_test.go`：远程存储、取消清理、格式错误与读取竞态 |
| 运行配置 | `ProductionSecurityValidatorTest`、`ModelConnectionTestServiceTest` | `write_contract_test.go`：保存、激活、热切换及初始化失败 |

Go 聊天引擎测试使用真实业务服务、上下文预算器和模型协议客户端，替换事务存储与 HTTP 传输；它们不证明 PostgreSQL 的锁和事务行为。数据库恢复、确认和计划契约仍须运行下述隔离集成测试。两版按各自实现组织测试，不要求测试文件一一对应。

## 运行

从仓库根目录执行 Java 全量测试与覆盖率门禁：

```sh
(cd backend && ./mvnw -B verify)
```

Go 默认测试包含引文、证据审查以及纯输入校验；计划与提问的完整数据库契约属于 `integration` 测试：

```sh
(cd backend-go && go test -race -count=1 ./...)
python3 scripts/migration/run_go_postgres_tests.py --tls --package chat
```

集成测试脚本需要 Docker，创建独立、无持久卷的 PostgreSQL/pgvector TLS 容器并执行迁移，结束后清理本次容器；不读取 `.env`，不连接已有业务库。离线运行可用 `GO_TEST_GOCACHE` 和 `GO_TEST_GOPATH` 指定已有 Go 依赖缓存。

## 边界与覆盖率口径

引文审计和恢复冲突理由的字符限制按 Unicode code point 校验；计划回执和结构化提问的长度按 UTF-16 单元计算，与 Java 字符串语义一致。表情等补充平面字符可用于区分两种长度，不应互换断言。

Java 的 JaCoCo 分别报告行覆盖率与分支覆盖率；Go 原生覆盖率报告语句覆盖率。比较增益时应使用全新覆盖数据和相同命令、插桩范围与测试集合；Go 默认测试与带 `integration` 标签的结果应分别报告，不能合并后直接与默认测试基线比较。
