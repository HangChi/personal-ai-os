# P7 MCP、全局搜索与数据交换验收

2026-11-04：T700–T705 的实现和定向验证已交付；全量回归与最终台账正在收口。固定 Java 对照提交为 `cb5d694d62fa93742b5789b97273e76d931ce335`。P8 和五个发布门禁保持开放。

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

后续 Java 差分/边界回归及最终台账单独提交，不将所有工作攒成一次提交。

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
- Java定向77测试零失败/错误/跳过，JaCoCo通过。
- 固定源码14个文件重新编译，Java夹具包含9个命名、5策略、4冲突、6摘要、8LIKE模式、7搜索、4个人工具元数据、35导入场景；Go逐项相等。
- 原测试映射15类/72声明，其中71属于MCP/搜索/交换，1个为P6移交的治理备份恢复声明。其余5个治理声明继续保留P6映射。
- P7台账150项：61个源/测试文件、6条HTTP路由、4个工具、7个运行钩子、72个测试声明。全部记implemented，未新增verified；四处独立个人MCP配置调用处置为wired，78处总配置台账通过。迁移脚本22项单测验证非P7条目及五个发布门禁保持不变。
- 并行全量回归曾遇临时数据库启动超时与Java MCP子进程目录连接超时；最终串行复验结果在下文补充，不将超时当作业务成功。

最终命令、实际结果和台账统计待全量运行结束后补齐。
