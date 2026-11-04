# P8 原 E2E 与真实浏览器记录

所有运行都由 `scripts/migration/run_go_e2e_tests.py` 新建TLS PostgreSQL/pgvector库、重放原42迁移、启动合成模型和独立认证进程。没有改原 `scripts/e2e/e2e_suite.py` 的断言。原前端生产构建使用Vite独立preview代理；禁用项目配置和.env读取，浏览器只允许本次回环origin。Java显式 `SERVER_ADDRESS=127.0.0.1`、禁用dotenv。

## 原 E2E

最后Go运行：[go/e2e-20261104-202743.json](go/e2e-20261104-202743.json)，81 PASS / 1 FAIL / 1 SKIP / 1 NOTE / 0 ERROR，共84项。固定Java全84项：[java/e2e-20261104-202748.json](java/e2e-20261104-202748.json)，相同计数与结果。首轮及中间失败报告保留，不能按最新结果覆盖历史。

唯一FAIL是G4的小预算2048抬下限后，首轮约1900字输入被安全预算拒绝（SSE业务码41301）。相同模型fixture在固定Java基线执行G0–G4，`java/e2e-20261104-164859.json`为39 PASS / 同一项1 FAIL。两端均在第0轮拒绝；没有降低安全估算、改原断言或把失败转PASS。这是原测试与当前基线安全预算的冲突，T802父项和frontend_e2e门禁仍开放，接受例外或更改预算契约须明确审查。

剩余SKIP是saveMemory观察项：原case在没有写入或有工具写入的两个分支都会抛SkipCase，不能靠调整fixture把它变成PASS。最新fixture只对完整显式“请（长期）记住：内容”请求调用真实工具；原case两端均观察到一条MANUAL写入后仍SKIP。独立补验证据见下。剩余NOTE是工具超限项，两端都实际返回错误终帧，未改为正常done。fixture只根据实际收到的历史/资料/附件与工具返回选择响应，单测使用不同值并验证缺上下文不能回答；不读数据库或期望报告。词袋嵌入与模拟usage不是生产模型语义/tokenizer验收。RAG关闭只走legacy路径，多阶段RAG见P6四设施回归。

### 显式记忆与关闭自动捕获

独立`--mode memory`在Java/Go新TLS空库运行通过，报告[go/explicit-memory.json](go/explicit-memory.json)、[java/explicit-memory.json](java/explicit-memory.json)。普通事实对话完成且等待原观察窗口后零记忆写入；同一合成事实两轮显式saveMemory实际done、两条TOOL/SUCCESS审计、内容与MANUAL来源精确一致、仅一条记忆且ID不变、四消息持久化；本次会话/消息/记忆清理归零。日志`/tmp/personalos-p8-explicit-memory-{go,java}.log`。

模型夹具新增单测拒绝普通事实、记数字请求、引用中的指令、空白内容；有工具回执后不重复调用。原suite的SKIP不被改写或过滤；这条补验只证明受控协议和真实存储/审计行为，不证明真实模型总能正确选择工具。

### 上下文拒绝、历史裁剪与恢复

独立`--mode context`直接调用未改原G4函数，两端新会话1811字符首条输入均41301、无done/delta且零上游请求；精确USER与Java中断标记持久化，原case仍FAIL。正常预算先经六轮HTTP对话创建历史，再设小预算发短问题，两端均完成一次实际模型调用；Go/Java只送2/1条用户消息，均为完整当前输入和有序后缀。14条数据库消息及此前12条记录保持不变；恢复预算后原拒绝会话可继续，原两条记录未变。

报告[Go](go/context-boundary-20261104-225738.json)、[Java](java/context-boundary-20261104-225900.json)独立PASS且清理归零，各8次合成模型请求。观察器只记有界32次的角色/数量/内容SHA，不写原始提示或工具参数。保留上游历史条数差异，不宣称逐字上下文等价；这项不能将原E2E FAIL改为PASS或替代预算契约审查。

### 失败隔离与真实计划工具

原G4小预算case的32768恢复语句位于首轮断言之后，失败时无法执行。迁移runner仅包装该case，在finally调用相同认证API恢复32768；不改原函数/断言/结果。五项单测覆盖成功/原失败/恢复失败/双失败/目标不唯一；恢复错误会导致runner失败且不掩盖原异常。真实两端全suite复跑仍记录G4 FAIL。

夹具现在从显式createPlan请求解析编号步骤；只对受支持的“任务查询→优先级统计→建议”三步计划，依据实际成功的计划和任务工具回执逐步start/complete。统计来自实际返回任务，明确最多100项查询范围；未知计划/失败回执不会伪造完成。Go此前只建计划时正确yield，首轮[go/e2e-20261104-201735.json](go/e2e-20261104-201735.json)保留80/1/1/2；Java首轮[java/e2e-20261104-201753.json](java/e2e-20261104-201753.json)只检查存在即可通过，不能单独当完整执行证据。补齐夹具推进后两端同为81/1/1/1。

新增独立`--mode plan`以两HIGH/一LOW合成任务验证：真实SSE done、三步COMPLETED/DONE及顺序时间戳、findTasks回执、准确优先级统计、单条助手消息持久化和清理归零。该测试独立于原suite统计，报告为`go/plan-lifecycle.json`、`java/plan-lifecycle.json`。首轮探针仅清理阶段因缺少原runner的started_at失败，保留两端日志；初始化后第二轮验证结果见P8进度。原suite与模型语义质量门禁仍不宣称通过。

## 客户端取消差分

旧suite曾因Go有助手消息而SKIP，不能据此声称模型已完整回答。进一步真实断连发现：Java的客户端取消不保存助手消息，Go此前误走上游异常的中断保存。现只为legacy客户端绑定执行携带取消context，取消时不保存半截助手；上游错误/超时及持久Run仍保留各自恢复策略。

`go/cancellation.json`与`java/cancellation.json`均PASS：读取首个实际delta后明确关闭HTTP response与connection，合成模型实际观察上游断连，数据库仅1条USER、0条ASSISTANT，未收到done。Go真实TLS库race测试`TestPostgresP8LegacyCancellationStopsUpstreamWithoutPartialMessage`及原双流心跳/Run读者断连/恢复测试通过（7.312s）。原suite取消项在修复后也PASS。

## 浏览器

`browser-go.json`记录实际Chrome/原前端登录与CSRF、任务创建/编辑/完成/明确删除、持久聊天SSE、UI附件上传/实际上下文回答/字节一致下载、UI知识后台索引/列表和JSON导出。10页面×1280/768/375三宽度均非空、零页面横向溢出、零控制台/运行时/HTTP5xx错误。保留33张本机临时截图，人工抽看桌面对话、手机对话与知识页。

知识上传首两轮失败是fixture内容少于原350字符下限，使用828字符合成文本后通过；没有改下限或生产解析器。浏览器报告不代表所有按钮或图表视觉细节都已人工验收。Push通知另由Chrome专用runner跟踪。

复验：

```bash
python3 scripts/migration/run_go_e2e_tests.py
python3 scripts/migration/run_go_e2e_tests.py --backend java --groups G0 G1 G2 G3 G4
python3 scripts/migration/run_go_e2e_tests.py --mode cancel
python3 scripts/migration/run_go_e2e_tests.py --backend java --mode cancel
python3 scripts/migration/run_go_e2e_tests.py --mode browser
python3 scripts/migration/run_go_e2e_tests.py --mode plan
python3 scripts/migration/run_go_e2e_tests.py --backend java --mode plan
python3 scripts/migration/run_go_e2e_tests.py --mode memory
python3 scripts/migration/run_go_e2e_tests.py --backend java --mode memory
python3 scripts/migration/run_go_e2e_tests.py --mode context
python3 scripts/migration/run_go_e2e_tests.py --backend java --mode context
```

原suite存在已记录失败时runner退出非零；不掩盖门禁结果。所有fixture只清理本次创建的进程/数据库。

## Push 的控制测试与真实失败

2026-11-05新增[push-go-handshake-20261105.json](push-go-handshake-20261105.json)进一步定位：保留30秒订阅/3秒控制/15秒送达期限，原白名单新增Chromium实际握手/退避/等待网络状态与发送前快照。实际CONNECTING→WAITING_FOR_BACKOFF，真实Go TLS库投递被FCM接受，控制成功但仍只有控制产生的1次pushEvents；接收/解密失败记录0、通知/Worker错误0，报告FAIL。5项Node测试通过，未知附加文本仍脱敏为OTHER。补充TCP只读检查mtalk.google.com的5228/5229/5230/443均建连；不证明Chrome的FCM登录/消息通道完成，不把未送达归因于已证实的防火墙或代理问题。全部临时Chrome/profile/密钥及本次库清理，真实送达继续开放。

新增显式`--headed`对照，只去掉headless参数，仍使用本次新随机profile与相同30秒订阅/3秒控制/15秒送达期限，不访问用户Chrome资料。报告新增浏览器模式与三项期限。连接状态只对安全白名单进行大小写/空格/连字符归一化，带额外未知信息仍输出OTHER；四项Node纯单测通过。

两轮有界面对照均成功订阅，本地CDP控制回传/显示成功，Go在本次新TLS库真实投递并被FCM接受（第一轮0.82s、第二轮见日志）；但15秒内仍无真实Push。报告[push-go-headed.json](push-go-headed.json)与[push-go-headed-normalized.json](push-go-headed-normalized.json)都为DELIVERY/FAIL，只有控制消息产生的1次pushEvents；workerErrors/notificationErrors=0，GCM接收/解密失败记录均0，连接状态仍OTHER。诊断不足以确定原因，不能断言headless导致故障。日志`/tmp/personalos-p8-browser-push-headed.log`、`/tmp/personalos-p8-browser-push-headed-normalized.log`。全部本次Chrome/profile/容器已清理；真实送达继续阻止验收。

复验命令：`node scripts/migration/chrome_p5_web_push.mjs --headed --report <新的报告路径>`。默认继续headless，保留既有失败报告；不通过扩大期限或只看平台SENT状态凑通过。

`node scripts/migration/chrome_p5_web_push.mjs --report specs/033-go-rewrite/p8-e2e/push-go.json`两轮诊断仍未通过；第二轮Go实际投递测试0.48s通过、FCM接受，但原15秒期限内无真实push事件。CDP合成push控制消息成功触发同一Worker、回传并显示通知，notificationErrors/workerErrors均0，唯一一次pushEvents来自控制消息。该控制明确绕过FCM/加密，只排查本地Worker与回传路径，不能替代真实送达证据，不能据此断言FCM通道或解密的具体故障原因。

报告只记录布尔/计数，不包含订阅URL、浏览器密钥或VAPID私钥；临时profile/合成密钥/数据库已删除。日志`/tmp/personalos-p8-browser-push-diagnostic.log`与`/tmp/personalos-p8-browser-push-diagnostic-2.log`保留，T802/发布门禁继续开放。

新增`chrome_push_diagnostics.mjs`只采集临时profile的GCM连接状态白名单/记录开关/接收与解密失败行数，不返回设备ID、注册ID、地址、密钥或日志正文。两项Node单测覆盖脱敏和不可用状态，候选CI执行纯单测。runner记录失败阶段、限制CDP命令35秒；原订阅30秒、本地控制3秒和真实送达15秒期限不变。新诊断只在订阅/控制之后开启，避免改变订阅前置条件；Go投递分支改用新TLS数据库，但最新两轮没有执行到该分支，不能记为TLS投递通过。

2026-11-04新增两轮冷profile复验均在PushManager.subscribe的30秒期限失败，尚未创建Go测试数据库或向FCM投递。第一轮曾在订阅前开启GCM记录，取得的连接状态为空、计数为0，不足以定位原因，报告保留为`push-go-gcm-subscription-timeout.json`；调整诊断顺序后仍在订阅阶段超时，`push-go-gcm.json`明确stage=SUBSCRIPTION、platformAccepted=false、realPushDelivered=false。日志`/tmp/personalos-p8-browser-push-gcm-1.log`与`/tmp/personalos-p8-browser-push-gcm-2.log`保留，临时Chrome/profile已清理；不将此轮混同早先“平台接受但未送达”的结果，也不能据此断言防火墙、FCM或解密的具体故障。
