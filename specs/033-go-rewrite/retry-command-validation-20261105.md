# 重试安全与快捷指令增量验证（2026-11-05）

源码锚点：f6e724ffcc25b84f53c004d2393533698febe241。原冻结 oracle 仍为
cb5d694d62fa93742b5789b97273e76d931ce335，原 inventory、声明清单与 42 个 SQL 迁移不变。
这是用户明确要求的双端业务增量，登记不等于迁移完成或发布批准。

以下离线结果保留首次验证的历史；Docker 启动后的补跑与新增修复见末尾跟进记录。

## 已提交实现

| 提交 | 双端行为 |
| --- | --- |
| 7f1a421 | MCP AUTO 也经过持久工具所有权；同一轮、同工具、规范化后的相同参数仅执行一次。嵌套对象排序，数组保持顺序。 |
| 0175122 | 自动调用和人工确认均向 MCP 远端传递稳定的 personal-ai-os/idempotency-key；保留原确认标识。响应丢失显示结果未知，禁止以更换键的方式盲目重试。 |
| 58bae4e | Java / Go 审计清理均保留 TOOL 的 IN_PROGRESS 和 FAILED 所有权，不再按普通日志周期删除。成功回执仍遵守现有策略。 |
| 8d91dda | 两端实现相同 allowlist 的 GET /chat/tools 与 POST /chat/sessions/{id}/tools；UUID 请求键绑定会话、工具和参数，复用原确认回执，沿用工具审计、执行预算与会话租约。 |
| 24d59b9 | 修正 Java 幂等服务过时注释，说明 TOOL 未知结果不按超时回收、不能靠换键重试。 |
| f6e724f | 发送框 / 候选菜单、常用任务/日程/知识库指令、通用 /tool、结果卡片；请求前持久化当前标签页的摘要与 UUID，刷新后沿用。 |

共享的 agent 工具注册、MCP 回调、chat module 和 ChatView 已归原检索/引用候选组，
该组源码锚点推进到上述提交并显式更新 5 个共享文件。原搜索/引用验收限制继续保留。
其余 37 个新增或变更文件单列 retry-safety-and-chat-commands 组，不重复登记。

## 实际执行结果

- Java：108 个隔离测试类、829 个测试通过，0 失败、0 错误、0 跳过。
  选择器显式排除 IntegrationTest、SpringBootTest、Testcontainers 和数据库切片类；
  使用 -Djacoco.skip=true 运行选择的单元套件，不代表完整集成或覆盖率门禁通过。
  首轮较宽选择 836 个测试中只有 OpenSearchGatewayIntegrationTest 因 Docker 不可用报错；
  该失败保留在本地日志，未通过修改断言或关闭发布门禁消除。
- Go：go test -race -count=1 ./...、go build ./...、go vet ./... 均退出 0。
  环境依赖集成测试按其原有前置条件执行/跳过，不将包级通过解释为真实数据库验证。
- 前端：35 个组件测试文件、242 个测试全部通过；1162 个逻辑测试全部通过。
  type-check、lint、Prettier 检查与生产构建通过。
- 本地 Chromium + mock API：候选键盘选择、Tab 仅补全、Enter 执行、帮助、工具清单、
  创建任务、响应丢失后的草稿保留、刷新后相同 UUID、删除待人工确认均通过。
  1280 / 768 / 375px 三种宽度无水平溢出，菜单和输入区保持在视口内。
  初次窄屏测试发现菜单挤出输入区，已改为在输入框上方浮出，并复测通过。
  浏览器无 pageerror，未调用真实后端、模型、搜索、MCP 或开发数据库。

针对性回归包括：并发重复提交、参数顺序、键冲突、真正的新操作、已产生副作用后丢失响应、
确认编号保持、租约占用/失效、附件保留、中文输入法、相似指令精确匹配、存储不可用时不发送、
成功后清理存储失败保留原身份，以及迟到响应归属原会话。

迁移完整性：工作区 Python 181 个测试通过（包含接手时已有的未提交测试）；
增量登记后再次执行 inventory 相关 14 个测试通过。普通 inventory、78 项配置处置、
P8 原测试映射和 P8 文件职责派生检查均通过。保留工作区原有进度 WIP，仅更新本轮相关的
41 个原文件进度条目和 9 个职责条目，并单独暂存本轮差异；五个 release_gates 未改变。
4 组、116 个候选文件均绑定不可变源码，check-complete 按预期因未验证增量和原发布门禁失败。

仅暂存树导出的独立快照复核：原有未提交源码、测试与进度 WIP 不进入快照；不携带本机 .env、
target 或 node_modules。四项普通迁移检查再次通过，Java -DskipTests package 成功，
已提交迁移 Python 175 个测试通过；Go agent / chat / mcp / idempotency / maintenance
五包竞态测试与全包构建再次通过。工作区 181 与快照 175 的差额来自保留的原未提交测试，
不将它们冒充本轮已提交实现。

本地明细保留于 .cache/commands-session-20261105/；日志是本次执行记录，不是迁移发布证明。

## 边界与未完成验证

- 首轮 Docker daemon 不可用；该阻碍已解除，真实数据库补跑结果见下文。
- MCP 远端必须实现上述元数据的去重语义，才能提供端到端保证；任意远端并不自动恰好执行一次。
  未知结果保留执行所有权，提示先核对；不得将新会话、新参数或新标识当作安全重试。
- 前端身份保留限于同一标签页及刷新；跨标签页、浏览器重启不是本轮保证范围。
  不持久化原始工具参数，仅存摘要和 UUID。主动清理成功回执会缩短历史去重范围，默认永久保留。
- 未进行真实模型/搜索/MCP 验收，也未完成两个真实后端的浏览器全流程对照。
- 本轮不修改原工作区既有的 P8 推送、上下文、运维评估 WIP，不将它们计作新功能的发布证据。
- 增量保持 in_progress；五项 release_gates 不变，check-complete 仍应拒绝迁移完成声明。

## Docker 启动后跟进（2026-11-05）

验证源码：22ba4d3f1227b9d1f6d8cd7e3e876273bbc6f732。首次从 63c85a4 分别导出 Java、
Go 的独立 Git 快照，随后同步本次已提交修复；逐字节核对 Java 621 个、Go 477 个文件均与上述
源码提交一致。未复制开发 .env，未连接或修改开发数据库。Go 使用无持久卷、随机回环端口、
TLS verify-full 的独立 pgvector PostgreSQL，真实 Java Flyway 执行原 42 个迁移；结束后清理所建容器。

发现并修复：

- Java 首轮完整 verify 共 1170 项，仅旧 ChatKnowledgeIntegrationTest 断言失败。
  生产实现已正确发送 warning、delta、done；旧用例仍期待 error。断言改为同时要求 warning 的
  KNOWLEDGE_RETRIEVAL_FAILED 代码、警告先于正文及 done、无 error，继续检查降级轮引用为零。
- 新增真实 HTTP 回归暴露缺少 Idempotency-Key 时 Java 返回 500、Go 返回 400。
  Java 全局异常处理补齐必需请求头缺失的 400 信封，仍拒绝执行工具。
- 9dd87e7 提交上述修复及双端直接工具集成测试；22ba4d3 提交 Go 五种日程规则的实际数据库往返，
  与 Java 已有回归对齐，同时校验 recurrenceUntil 和 reminderLeadMinutes。

Java 最终执行 ./mvnw -B clean verify 退出 0，159 个测试类、1173 项测试全部通过，
0 失败、0 错误、0 跳过，耗时 5 分 27 秒。使用 clean 删除旧执行数据，未跳过 JaCoCo、
未降低覆盖率门禁：行覆盖 9106 / 10642 = 85.57%，分支覆盖 3898 / 5633 = 69.20%，
满足原 83% / 65% 阈值，完成 JAR 打包。包括真实 PostgreSQL、OpenSearch、S3 测试服务等
既有集成套件。DirectToolIntegrationTest 的 3 项及 ChatKnowledgeIntegrationTest 的 9 项
均通过；OperationRecordRetentionIntegrationTest 的 1 项和 DataImportIntegrationTest 的 15 项
也已实际执行。新增直接工具用例断言聊天模型调用为零。

仍观察到既有整套测试退出诊断：Surefire 在 System.exit(0) 后等待 30 秒再终止 fork JVM，
并生成线程转储；部分已停容器对应的缓存 Spring 上下文也有连接池关闭连接告警。
Maven 最终退出 0、测试报告与覆盖率通过，所建容器已清理；这不表示测试资源退出过程完全干净。
线程转储与原始日志保留，资源关闭顺序仍需单独排查，本次未延长退出超时来掩盖该现象。

Go 实际执行：

| 命令 / 范围 | 结果 |
| --- | --- |
| run_go_postgres_tests.py --package idempotency --package confirmation --tls | 7 + 8 个顶层测试通过；包含真实跨连接占位/重放、确认 CAS 和事务分支 |
| --package app --tls --run '^(TestP8Retention\|TestP7MCP\|TestP7StreamingExportImportRoundTripPostgres\|TestFoundationP2ConfirmationAndCrossConnectionIdempotency)' | 6 个测试通过；真实保留策略、MCP 本地上游、12 路并发确认只执行一次、导入导出 |
| --package app --tls --run '^TestDirectTool' | 3 个测试通过，实际 HTTP + 注册内建工具 + PostgreSQL，模型请求数为零 |
| --package app --tls --run '^TestCalendarImportEveryRecurrenceRoundTripPostgres$' | 1 个顶层测试及 NONE / DAILY / WEEKLY / MONTHLY / WEEKDAYS 五个子用例通过 |

上述 Go 命令均启用 -race -count=1。首次所有权补跑因离线缓存路径未包含 pgx 而未进入用例，
随后使用 runner 已支持的 GO_TEST_GOPATH / GO_TEST_GOCACHE 指向现有缓存重跑通过。
新增指令测试首次引用了仓库未依赖的 UUID 包，改用现有 agent.UUID 后通过，未添加依赖或放开联网。
失败日志保留，不将准备失败计作已执行测试。

双端快捷操作均验证：同键（包括大写 UUID）复用回执；改参数/会话冲突；新键为新操作；
缺键 400、不存在会话 404；删除前不执行，重放复用确认编号，批准后才删除，重复确认 409；
删除后重放旧创建回执不能重建任务；会话被占用时拒绝且不抢占工具所有权，释放后同键可执行。

迁移完整性在工作区及仅含已提交源码和本次范围更新的独立快照中均通过：原 1093 文件、
78 项配置处置、P8 原测试映射和文件职责派生检查。独立快照使用专用 Git 工作树/索引环境，
未包含原有未提交源码、测试和进度 WIP；inventory / scope / P8 inventory 的 19 个测试通过。
首次快照调用未设置专用 Git 环境，基线提取因此失败；修正执行环境后重新通过，未改检查器。
本次仅合入自身 44 个原文件进度条目、1 个职责条目及增量元数据，其余 WIP 保留。
4 组、120 个候选文件绑定不可变源码，状态仍为 in_progress；check-complete 继续拒绝
尚未验证的范围、原声明和五项 release_gates。原 inventory 哈希、SQL 和发布门禁不变。

完整日志、源码核对和独立快照检查保留在 .cache/docker-validation-20261105-35da426f/。
真实远端 MCP 去重支持、完整双后端浏览器流程及原五项发布验收仍开放；Docker 测试通过不等于发布批准。
