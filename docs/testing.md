# Java / Go 测试与质量门禁

本项目用五个维度验收两版后端。相同的是业务要求；语言工具的统计口径分别报告。覆盖率达标不等于真实模型效果、性能或生产发布已经验收。

## 指标与阈值

| 维度 | Java | Go | 验收条件 |
| --- | --- | --- | --- |
| 代码执行覆盖 | JaCoCo 行覆盖率 | 官方语句覆盖率 | 完整测试集合各自 ≥95%，按原始计数校验 |
| 逻辑分支覆盖 | JaCoCo 分支覆盖率 ≥95% | 原生 profile 不提供分支统计 | Go 固定显示 not_measured，不以语句覆盖率代替 |
| 新增、修改代码 | Git 差异中的可执行行 | 差异触及的插桩 block 内语句 | 分别 ≥95%；没有可执行单元变更时显示不适用 |
| 关键业务场景 | 同一份必选场景清单 | 同一份必选场景清单 | 两版对应测试均实际执行并通过，缺失、失败、跳过均不通过 |
| 双版本行为一致性 | 共用输入与预期 | 共用输入与预期 | Agent / RAG 共享契约全部通过 |

Java 行覆盖按 JaCoCo 的可执行行统计，有指令命中即为已覆盖，分支另行验收。Go 变更覆盖按原生 block 粒度加权统计语句数，不伪装为逐行或分支覆盖。变更源码文件不在报告内时直接失败；只有删除、测试、注释且未触及可执行单元时不添加分母，不填成 100%。

本轮没有建立 Go 分支插桩器，因此没有完成“Go 分支覆盖率 ≥95%”的证明。未来如增加，应独立说明分支定义、编译范围与工具验证；当前 95% 指 Go **语句**覆盖率。

## 固定测试集合

- Java：完整 Maven verify，包含 Testcontainers、单元测试与共享契约，保留 JaCoCo 单次 session 的行/分支计数。
- Go 默认：全包 race 测试，使用全项目 coverpkg、atomic 模式和 count=1，另执行 vet、build。默认覆盖率独立展示。
- Go 完整：默认集合，加全部 18 个 PostgreSQL/TLS 包、7 个 Redis/TLS 包、P6 RabbitMQ/OpenSearch 组合和两个真实基础进程 smoke，共 31 份 profile。完整集合语句覆盖率强制 ≥95%。

集成标签引入的支持包保留在分母。不新增业务排除项，不用旧 profile 或定向测试填补全量结果。各阶段创建本轮独立设施，不读取真实 .env，不连接既有业务库，不调用收费模型。

默认集合也验证生产实现中的跨组件控制流：例如[工具执行循环](../backend-go/internal/business/chat/engine_tool_loop_contract_test.go)运行真实 Engine、Registry、幂等服务和模型协议客户端，只在 SQL 与 HTTP 传输边界注入合成输入；检查工具回执、引用编号、计划纠正与最终持久化，而非仅检查函数被调用。[文档更新与删除](../backend-go/internal/business/knowledge/document_mutation_contract_test.go)逐步注入存储故障，检查回滚和租约释放。

SQL 边界替身不解释 SQL，不能证明 PostgreSQL 的约束、锁和隔离语义；这些仍由隔离数据库集合验证。[AMQP 连接测试](../backend-go/internal/business/knowledge/signal_connection_test.go)只验证未完成握手的取消与失败重试，不模拟成功发布或消费。默认集合覆盖率提升后，完整设施测试和原有门禁仍需运行，两个集合继续分别报告。

2026-11-07 的[默认集合扩展报告](reports/default-coverage-2026-11-07.md)记录 Go 默认语句 91.8744%、完整集合语句 95.4579%，以及同一源码修订下的 Java 回归、关键场景和证据；这些是该次运行结果，不是今后提交自动继承的指标。

## Agent / RAG 必选场景

[机器可读清单](../scripts/coverage/critical-scenarios.json)绑定两版实际测试方法和指定执行阶段。验收器读取新生成的 JUnit XML、Go JSON 和集成 runner 日志，要求以下场景全部通过：

| 范围 | 主要断言 |
| --- | --- |
| 执行预算 | 模型调用上限、连续失败上限、参数修正后继续、失败调用不得原样重放 |
| 工具写入凭据 | 成功读取、待确认、失败均不算成功写入，凭据只属于对应工具 |
| 幂等和人工确认 | 相同调用只执行一次，危险操作确认前不写入，确认不可重复消费 |
| 只总结模式 | 不发起新工具动作和外部检索 |
| 超时、取消和恢复 | 收尾只执行一次，失败/让出遵守落库边界，恢复不重放不确定动作 |
| RAG 切块 | 标题、围栏、自然断点、短块合并、重叠及原文保持 |
| 检索与降级 | 改写失败保留原查询，无证据不生成依据，重排失败有明确降级 |
| 引文与证据 | 编号、原文锚定、冲突来源和有效期 |
| 计划与人机交互 | 状态顺序、终态保护、完成凭据、人工输入和问题幂等 |

场景同时断言结果和模型调用、持久化等副作用。两版测试组织不必一一对应；实际输入和预期需保持共同语义。[共享夹具](../testdata/README.md)包含 182 个 Agent / RAG 契约用例。检索与答案效果另见[评测方法](evaluation.md)，不得用代码覆盖率代替效果评测。

## 本地运行与证据

需要 Java 21、项目指定的 Go、Docker、Poppler 和 Go/Maven 依赖。P6 runner 需要已拉取的 rabbitmq:4.4.0-management 与 opensearchproject/opensearch:3.9.0 镜像。macOS 先设置 JAVA_HOME 指向 Java 21；可用 GO_TEST_GOPATH / GO_TEST_GOCACHE 指定现有 Go 缓存。

~~~bash
python3 -m unittest discover -s scripts/coverage -v
python3 scripts/coverage/run_suite.py --stage all --output .cache/quality-new-run
~~~

也可对同一输出根目录依次运行 java、default、postgres、redis、p6 五个 stage，再汇总：

~~~bash
python3 scripts/coverage/run_suite.py --stage check --output .cache/quality-new-run --base <明确的基准提交>
~~~

每个阶段目录拒绝复用；失败后换新目录重跑相应阶段，不手工补写成功标记。汇总校验成功状态、必需 profile、源码指纹、Git 修订及证据 SHA-256；源码中途变化、缺阶段或证据改动都失败。summary.json 分别给出完整/默认指标、变更指标和逐场景结果。

## CI

[Backend Quality](../.github/workflows/coverage.yml)在后端、共用夹具或检查脚本变更时运行五个完整阶段再验收。PR 使用与目标分支的 merge-base，普通 push 使用上一提交对应的 merge-base；首次分支 push 或手动运行没有基准时只执行完整门禁和关键场景。

Java 原有 [CI](../.github/workflows/ci.yml)也保留行/分支 95% 门禁。Go 候选发布保护独立执行；这里不提升迁移状态或生产 release gates。是否强制阻止合并还取决于仓库分支保护是否将新 quality-gate 列为必需检查。本地运行不代表 GitHub 已运行。
