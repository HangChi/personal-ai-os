# P8 原 E2E 与真实浏览器记录

所有运行都由 `scripts/migration/run_go_e2e_tests.py` 新建TLS PostgreSQL/pgvector库、重放原42迁移、启动合成模型和独立认证进程。没有改原 `scripts/e2e/e2e_suite.py` 的断言。原前端生产构建使用Vite独立preview代理；禁用项目配置和.env读取，浏览器只允许本次回环origin。Java显式 `SERVER_ADDRESS=127.0.0.1`、禁用dotenv。

## 原 E2E

最后Go运行：`go/e2e-20261104-171712.json`，80 PASS / 1 FAIL / 1 SKIP / 2 NOTE / 0 ERROR，共84项。首轮及中间失败报告保留，不能按最新结果覆盖历史。

唯一FAIL是G4的小预算2048抬下限后，首轮约1900字输入被安全预算拒绝（SSE业务码41301）。相同模型fixture在固定Java基线执行G0–G4，`java/e2e-20261104-164859.json`为39 PASS / 同一项1 FAIL。两端均在第0轮拒绝；没有降低安全估算、改原断言或把失败转PASS。这是原测试与当前基线安全预算的冲突，T802父项和frontend_e2e门禁仍开放，接受例外或更改预算契约须明确审查。

剩余SKIP是saveMemory：fixture没有调用该工具，未实际验证此条原E2E；P5的真实工具/自动记忆测试另有证据。两个NOTE为原suite的观察项。fixture只根据实际收到的历史/资料/附件与工具返回选择响应，单测使用不同值并验证缺上下文不能回答；不读数据库或期望报告。词袋嵌入与模拟usage不是生产模型语义/tokenizer验收。RAG关闭只走legacy路径，多阶段RAG见P6四设施回归。

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
```

原suite存在已记录失败时runner退出非零；不掩盖门禁结果。所有fixture只清理本次创建的进程/数据库。
