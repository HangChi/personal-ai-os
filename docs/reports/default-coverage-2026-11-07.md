# 2026-11-07 Go 默认集合覆盖率扩展

本轮新增 11 个 Go 默认测试文件、38 个测试入口，不修改业务源码、覆盖率排除项或质量阈值。默认全量语句覆盖率由 **89.1462% 提高至 91.8744%**，提高 **2.7282 个百分点**；分母仍为 20,380 条，新增命中 556 条，没有丢失原有命中。Java 同步执行完整回归。

## 被测快照与结果

- 被测修订：7f39e3bf56215e161556f89e658f2e4ba9fffd5b。
- 源码 SHA-256：bbfcbdc40c2cd9b568d53ae537913ef59ba220aafeb9ea6ab4097c4f12d55386。
- 时间：2026-11-07 23:27–23:34，Asia/Shanghai；五个阶段使用相同修订和源码指纹。
- 原始证据：忽略提交的 .cache/default-coverage-expanded-20261107/，包括五份 stage.json、JUnit、JaCoCo XML、Go 日志、31 份 profile 和 summary.json。汇总日志位于 .cache/default-expanded-summary.log。
- 对比基线：[上一轮五维门禁报告](quality-gates-2026-11-07.md)。本轮全量重跑，没有复用上一轮 profile。

| 指标 | 上轮 | 本轮 | 本轮计数 |
| --- | ---: | ---: | ---: |
| Go 默认语句 | 89.1462% | **91.8744%** | 18,724 / 20,380 |
| Go 完整集合语句 | 95.0855% | **95.4579%** | 19,482 / 20,409 |
| Java 行 | 95.3379% | **95.3379%** | 10,920 / 11,454 |
| Java 分支 | 95.0888% | **95.1183%** | 6,430 / 6,760 |
| Go 分支 | 未测量 | **未测量** | not_measured |

默认新增命中的部分路径原来已由集成集合覆盖，因此完整集合净增 76 条，而非再加 556 条。完整集合中集成标签额外纳入的 29 条支持语句继续计入分母。Java 本轮没有代码或测试变更，分支计数按新生成报告记录，不将其变化归因于新增 Go 测试。

## 默认集合补充内容

| 范围 | 实际检查 | 测试 |
| --- | --- | --- |
| Agent 工具执行循环 | 真实协议返回工具调用，经 Registry 与持久幂等服务执行，回执传回下一次模型请求；缺失调用 ID 补齐；网页与知识引用去重、统一编号、落库早于 done | [工具循环](../../backend-go/internal/business/chat/engine_tool_loop_contract_test.go) |
| 计划与人工交互 | 活跃计划拦截提前完成声明，只纠正一次后安全让出；提示词刷新、读取失败不报成功；askUser 新问题、已答重放与畸形选项拒绝 | [工具循环](../../backend-go/internal/business/chat/engine_tool_loop_contract_test.go)、[提示词与交互](../../backend-go/internal/business/chat/agent_prompt_contract_test.go) |
| 聊天 HTTP 与后台工作 | Legacy SSE 的持久化顺序、断开取消，快捷工具两层幂等凭据与租约校验，上传回读及大小/空文件检查；用量与完成钩子不继承客户端取消，单个可选钩子失败不阻断后续钩子 | [HTTP 执行](../../backend-go/internal/business/chat/default_http_execution_test.go)、[租约与记账](../../backend-go/internal/business/chat/lease_usage_contract_test.go) |
| RAG 文档替换与删除 | 先嵌入再原子替换，删除限定文档并返回块数；逐个存储边界注入失败，检查回滚、失效租约和释放顺序 | [文档变更](../../backend-go/internal/business/knowledge/document_mutation_contract_test.go) |
| 长期记忆与检索 | 相关性排序、UTF-16 截断、命中刷新；自动沉淀不使用工具、限制事实数量、逐条保存失败隔离；五个搜索模块接线与错误传播 | [记忆](../../backend-go/internal/business/memory/prompt_capture_contract_test.go)、[搜索注册](../../backend-go/internal/business/search/registry_contract_test.go) |
| 导出、服务与连接 | 四模块导出字段、标签与分页，写出失败回滚；业务/管理监听、重复启动拒绝、关闭后释放资源；AMQP 未完成握手取消、失败重试及日志脱敏 | [导出](../../backend-go/internal/business/chat/export_modules_contract_test.go)、[服务生命周期](../../backend-go/internal/app/serve_lifecycle_contract_test.go)、[索引信号](../../backend-go/internal/business/knowledge/signal_connection_test.go)、[提醒广播](../../backend-go/internal/business/calendar/broadcast_boundary_test.go) |

部分生产文件在默认集合中的语句覆盖率变化如下；这些是文件级指标，不是整个模块的覆盖率：

| 生产文件 | 上轮默认 | 本轮默认 | 新增命中语句 |
| --- | ---: | ---: | ---: |
| chat/stream.go | 81.7352% | 94.5205% | 56 |
| chat/module_http.go | 75.1606% | 89.9358% | 69 |
| knowledge/store.go | 74.6193% | 95.4315% | 41 |
| memory/prompt.go | 59.7222% | 98.6111% | 28 |
| search/contributors.go | 69.7674% | 95.3488% | 22 |
| app/serve.go | 24.6377% | 85.5072% | 42 |

## 完整验收

- Go 默认：35 包、6,906 项测试/子测试通过，比上轮增加 136 项，0 失败；仅辅助子进程入口 TestP7StdioHelper 默认跳过 1 项。全量 race、带 integration 标签的 vet 和 build 通过。
- Java：247 类、1,755 项测试，0 失败、0 错误、0 跳过；Maven verify 与行/分支 95% 原生门禁通过；JaCoCo 为 1 个全新 session。
- PostgreSQL/TLS：18 包、备份恢复、固定基线与真实基础进程 smoke 通过。
- Redis/TLS：7 包、PostgreSQL 组合与真实基础进程 smoke 通过。
- P6：RabbitMQ / OpenSearch / TLS 三包组合通过。
- 五维门禁：Java 行/分支及 Go 完整语句均 ≥95%；13 / 13 关键场景在指定阶段通过，182 个双版本共享用例通过；Go 分支继续明确未测量。
- 变更覆盖比较基准：09303cb1e720d6fc232b95474126131ca10dd81b。没有业务可执行单元变更，两版变更覆盖显示 not_applicable，不填成 100%。
- 覆盖率证据校验器 25 项回归、迁移脚本 185 项回归通过。文档、路由与来源登记在报告提交前后分别校验。

模型请求全部使用合成传输，未调用收费模型。隔离 runner 创建自己的临时设施并已清理，没有连接或修改原业务库，也不读取真实 .env。

## 复现与边界

~~~bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
GO_TEST_GOPATH=/Users/songhangchi/go \
GO_TEST_GOCACHE=/Users/songhangchi/Library/Caches/go-build \
python3 scripts/coverage/run_suite.py \
  --stage all --output .cache/default-coverage-fresh --base 09303cb
~~~

以上是本轮 macOS 的依赖路径示例，其他环境按实际 Java 21 与 Go 缓存调整。输出目录必须是新目录，不能覆盖或拼接旧证据。五阶段也可独立运行，之后执行 stage check；测量期间不得修改源码或提交。

SQL 边界替身验证实际生产控制流、参数和提交/回滚，不模拟数据库约束、锁或隔离；AMQP 本地连接用例不模拟成功发布/消费。完整数据库、Redis、RabbitMQ 与 OpenSearch 集合保留独立验收。默认仍有 1,656 条语句未命中，尚未达到 95%，后续可继续补充默认控制流和故障场景，不能将 95.4579% 的完整集合结果写成默认结果。

本报告固定记录上述测试修订；随后仅更新文档入口、测试说明与迁移来源登记，不将文档提交冒充另一次覆盖率运行。状态登记保持 in_progress，不升级迁移发布门禁。本地验收不代表 GitHub 工作流已运行；本轮没有 push。
