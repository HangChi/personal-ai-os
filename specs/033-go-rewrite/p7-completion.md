# P7 MCP、全局搜索与数据交换验收

2026-11-04：T700–T705 全部完成，Go 全量串行 race、P7 真实协议/数据库和固定 Java 差分通过，逐项台账与测试映射已提交。固定 Java 对照提交为 `cb5d694d62fa93742b5789b97273e76d931ce335`。Java 首轮全量有 3 项 MCP 启动失败，随后独立复验全部通过；不将首轮全量记为全绿。P8 和五个发布门禁保持开放。

## 任务与证据

| 任务 | 已实现职责 | 主要证据 |
| --- | --- | --- |
| T700 | 外部stdio/Streamable HTTP、配置验证/禁用、命名/冲突/schema、状态/降级/重连/关闭 | `backend-go/internal/business/mcp/{registry,naming,config,http}.go`、`registry_test.go`、`platform/config/mcp_runtime_test.go` |
| T701 | 原名只读清单AUTO、默认CONFIRM、服务端摘要、共享Agent预算/审计/幂等、确认一次消费 | `business/agent/mcp.go`、`mcp_test.go`、`business/mcp/confirmation.go` |
| T702 | 独立个人MCP开关、认证、精确CSRF例外、只导出四个只读工具且无递归 | `business/mcp/personal.go`、`personal_test.go`、`platform/auth/mcp_test.go`、真实库四工具协议调用 |
| T703 | 任务/对话/日程/知识/记忆搜索、转义/排序/限额/补充去重/模块故障隔离 | `business/search/`、`internal/app/foundation_p7_integration_test.go` |
| T704 | 200条分页流式JSON、单REPEATABLE READ快照、20MiB/空库/版本、原子向量与来源恢复 | `business/dataexport/`、`dataimport/`、`exchange/`、五模块`export.go`、`knowledge/import.go` |
| T705 | 真实stdio与HTTP、真实TLS PostgreSQL/pgvector往返/并发/回滚、固定Java差分与逐声明映射 | `scripts/migration/java_p7_fixture.py`、`P7Oracle.java`、`p7-test-mapping.md` |

## 分批提交

1. `eb0fc87`：官方 Go MCP SDK、客户端注册表、真实stdio/HTTP测试、许可。
2. `39ed122`：Agent默认确认/幂等审计、独立个人MCP服务与认证边界。
3. `aa52bb7`：五模块搜索贡献器与全局聚合。
4. `5df00b0`：流式快照导出、空库原子导入、实际数据库往返/失败回滚/并发快照。
5. `a51612f`：固定Java源码差分、Jackson严格UUID绑定及外部工具预算拒绝回归。
6. `d95d34f`：真实HTTP对话/MCP/持久确认链与完整记忆、任务、来源恢复边界。
7. `267801e`：150项实现台账、78项配置处置与15类/72声明逐项映射及验收边界。

最终任务勾选与回归记录另作收口提交；各批实现与测试已及时独立提交。

## 协议与配置

客户端使用 `github.com/modelcontextprotocol/go-sdk v1.8.0`，协议版本显式保持 Java 基线的 `2025-06-18`。stdio通过参数数组启动，不执行shell；只继承必要的PATH/HOME等并加入显式配置环境变量，不继承整个应用环境，stderr不转储。握手/列举/调用有期限，工具目录有分页/循环/数量上限，响应8MiB。超时关闭等待子进程回收，关闭期间重连不能复活注册表；旧连接的迟到失败不能关闭新会话。

保留 `mcp.enabled` 和 `mcp.servers.<name>.{command,args,env,auto-tools,connect-timeout}` 原配置绑定。新增 `mcp.http-servers.<name>.{endpoint,headers,auto-tools,connect-timeout}`；stdio/HTTP重名拒绝。HTTP不重定向、不透明重放；配置中的请求头实际送达，状态/日志不显示端点、命令、环境变量或凭据。无效配置启动拒绝，单服务连接失败降级，禁用不连接/展开未消费占位符。

`spring.ai.mcp.server.{enabled,name,version,streamable-http.mcp-endpoint}` 独立控制对外服务。默认false/`personal-ai-os`/`0.1.0`/`/mcp`；只允许该精确路径豁免CSRF，认证仍必需，重连与导入不豁免。只暴露 `personal_list_tasks`、`personal_search_knowledge`、`personal_list_calendar`、`personal_list_memories`，readOnly/idempotent为true，destructive/openWorld为false。知识工具调用基础混合检索，不递归进入Agent或高级问答工具链。

`--foundation --p7` 显式包含P2–P6；默认离线诊断及已有生产拒绝、回环监听、readyz503守卫继续保留。

## 导入导出边界

导出根对象直接包含application/version/formatVersion/exportedAt、五模块数组与同源counts，不包API信封，不含embedding/文件二进制；响应为JSON附件。所有顶层与嵌套分页共享单个只读REPEATABLE READ事务，每批最多200条。测试在第一批写出后修改全部任务并新增记录，剩余批次仍保持原快照。

导入最大20MiB；Content-Length超限在读正文前拒绝，无长度请求最多读取20MiB+1。精确JSON字段名、未知键忽略、标量转换及Jackson UUID格式通过固定Java差分；不将Go大小写宽松匹配当作兼容。SQL完整性错误仅依据SQLSTATE 23分类409，不从错误文本猜测。

先只读检查原10张受保护表，嵌入在事务外完成，再用同一写事务锁表并重新检查空库；业务行、标签、替代链、文档、向量、来源与空间恢复全部原子提交。两次并发导入只成功一次，嵌入期间普通写入会令第二次检查拒绝。导入保留ID/历史时间/顺序/previousStatus/记忆状态，知识向量按导出的原分块重新生成，空间按名称重建，来源版本/范围/有效期/生命周期按原约束恢复。

按原Java边界不恢复消息自增ID、引用快照、附件文件、会话摘要/边界、派生提醒；PDF/Word/图片原件须另行恢复或重新上传。该功能不是P8生产备份升级/切换演练。

## 验证记录

- P7真实库初次三组通过，补充快照/约束冲突后四组再次通过；最终六组全部通过race。数据规模405任务/标签、2会话/406消息、206块与向量、日程和记忆替代链/置顶/17次命中/最后使用时间、任务DONE/previousStatus；原42迁移真实Flyway竞争及validate通过。
- 最终真实HTTP对话链验证模型请求里出现MCP工具，AUTO结果与CONFIRM状态回到下一轮上下文；外部写确认前零调用，重连后12个并发HTTP确认只远程执行一次，真实数据库保留两条对应工具审计。
- Java定向77测试零失败/错误/跳过，JaCoCo通过。原Java全量执行1137测试，其中3个MCP降级启动用例未在期限内连接子进程工具目录，其余1134项通过；停止并发全量负载后，该类3项单独verify全部通过，JaCoCo/BUILD SUCCESS。未修改Java源码或降低门禁，未把组合结果描述为首轮全量成功。
- 固定源码14个文件重新编译，Java夹具包含9个命名、5策略、4冲突、6摘要、8LIKE模式、7搜索、4个人工具元数据、35导入场景；Go逐项相等。
- 原测试映射15类/72声明，其中71属于MCP/搜索/交换，1个为P6移交的治理备份恢复声明。其余5个治理声明继续保留P6映射。
- P7台账150项：61个源/测试文件、6条HTTP路由、4个工具、7个运行钩子、72个测试声明。全部记implemented，未新增verified；四处独立个人MCP配置调用处置为wired，78处总配置台账通过。迁移脚本22项单测验证非P7条目及五个发布门禁保持不变。
- 首轮并行Go全量race的app基础Servlet对照出现固定5秒登录超时，其余包通过。最终以GOMAXPROCS=4和`-p 1`串行全包复验，全部通过，app为244.716s；没有修改基础测试期限或生产行为。较早临时数据库启动超时发生在业务测试前，最终六组真实库测试另行全部通过。

### 最终命令与结果

以下命令从仓库根目录执行；Java命令在`backend/`，前端命令在`frontend/`。Go串行选项用于避免并发重型认证/Java测试争用资源，不跳过测试。

| 命令 | 实际结果 |
| --- | --- |
| `GOMAXPROCS=4 go -C backend-go test -race -p 1 -count=1 -timeout=20m ./...` | 全包通过，退出0 |
| `go -C backend-go vet -tags=integration ./...` | 通过 |
| `go -C backend-go build -tags=integration ./...` | 通过 |
| `go -C backend-go mod verify` | all modules verified |
| `python3 scripts/migration/run_go_postgres_tests.py --package app --tls --run '^TestP7'` | 6个顶层测试全部通过race，31.965s，临时容器清理 |
| `python3 scripts/migration/java_p7_fixture.py` | 固定14个Java源码重新编译、夹具比较通过 |
| P7 Java定向 `verify` | 77测试，0失败/错误/跳过，JaCoCo通过 |
| `./mvnw -o -B -Dspring.test.context.cache.maxSize=2 verify` | 1137测试，3失败/0错误/0跳过；首轮全量失败如实保留 |
| `./mvnw -o -B -Dspring.test.context.cache.maxSize=2 -Dtest=McpDegradedStartupIntegrationTest verify` | 3测试全部通过，JaCoCo/BUILD SUCCESS |
| `npm run type-check`、`npm run build` | 通过，前端源码未改 |
| `python3 scripts/migration/go_p7_progress.py --write` | 150项implemented、15类/72声明映射，其他阶段/发布门禁不变 |
| `python3 scripts/migration/go_config_disposition.py` | 78项通过，无遗漏/未知动态键 |
| `python3 -m unittest discover -s scripts/migration -p 'test_*.py'` | 22项通过 |
| `python3 scripts/migration/go_inventory.py check` | 固定1093文件/104路由/27工具/95钩子/1119原测试声明通过 |
| `python3 scripts/migration/go_inventory.py check-complete` | 预期退出1：2438项未verified，五个release_gates均not_started |
| `git diff --check` | 通过 |

最终日志：`/tmp/personalos-p7-go-serial.log`、`/tmp/personalos-p7-postgres-final.log`、`/tmp/personalos-p7-oracle-recheck.log`、`/tmp/personalos-p7-contracts-final.log`、`/tmp/personalos-p7-java-targeted.log`、`/tmp/personalos-p7-java-all.log`、`/tmp/personalos-p7-java-retry.log`、`/tmp/personalos-p7-frontend-types.log`、`/tmp/personalos-p7-frontend-build.log`、`/tmp/personalos-p7-complete-gate.log`。首轮Go失败日志为`/tmp/personalos-p7-go-final.log`；临时日志不替代入库的断言、夹具与逐声明映射。

本阶段只操作专用模拟器和runner创建的临时数据库，不读取既有凭据或连接用户外部MCP/模型。Java、前端业务源码、SQL和部署基线保持不变。未推送、合并、部署或开始P8；prod拒绝、回环监听及readyz503继续保留。
