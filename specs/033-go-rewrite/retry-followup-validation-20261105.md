# 测试退出、真实 MCP 与双端快捷操作跟进（2026-11-05）

用户要求逐项修复上轮保留的三项问题。源码锚点与精确结果见
`retry-followup-results-20261105.json`。本轮未连接开发数据库、现有文件目录或收费模型；
不替代原五项发布门禁。

## Java 退出顺序

- `c81c68c`：43 个由测试类持有 Testcontainers 的 Spring 集成测试继承公共清理基类。
  在容器扩展停止数据库前，通过 Spring TestContext 正常关闭并移出缓存的应用上下文；
  不是直接关闭连接池，也未调大 Surefire 超时。
- `c0b97ea`：全量检查进一步发现提醒 SSE 在 Tomcat 等待活动请求结束之后，才由
  `@PreDestroy` 完成，仍会耗尽 30 秒优雅退出预算。提醒推送实现 `SmartLifecycle`，
  在 HTTP 优雅停机阶段之前结束已有流；销毁回调可重复调用。
- 生命周期回归使用真实 PostgreSQL、Hikari 和随机端口 Tomcat，刻意保留一条已收到
  快照的 SSE 连接直到 Spring 关闭；断言上下文和池先于数据库关闭、关闭耗时小于 5 秒。
  单元测试同时检查停止回调和重复清理。定向 33 项全部通过。
- 最终在独立源码快照执行 `./mvnw -B clean verify`：160 个测试类、1175 项，
  0 失败、0 错误、0 跳过；3 分 7 秒完成。行覆盖 9114 / 10650 = 85.58%，
  分支覆盖 3899 / 5637 = 69.17%，原 83% / 65% 门槛未变。
  日志没有强制 fork 退出、优雅关闭超时、关闭中断或已停数据库连接验证告警。
- Go 已通过自身流 context 取消和 Foundation 关闭链路释放连接，本轮无需修改 Go
  生产实现；两端验收应用也均在限时内正常终止。

## 已安装外部 MCP 的实际副作用验证

使用本机已经安装的正式 `@modelcontextprotocol/server-filesystem` 2.1.0，
不是自制 MCP 成功响应桩。将其允许目录限定到新建临时 sandbox；透明 stdio 代理只在
真正成功的回复返回后丢弃一次回复并结束连接。每次工具调用仅记录工具名、参数摘要和操作标识。

Java / Go 各自使用新的无卷 PostgreSQL TLS verify-full 容器，真实 Flyway 执行原
42 项迁移；通过认证、CSRF 和真实 HTTP 工具入口验证：

1. AUTO `edit_file` 成功后同标识重放，不再增加文件内容；新标识确实再次修改，
   证明测试能识别重复副作用。
2. 外部编辑已提交再丢回复：结果未知，同标识返回 409；重连、应用重启后仍不重发，
   文件中只有一次新增。
3. CONFIRM `move_file` 批准前文件不动；重复请求复用同一确认；批准后真实移动，
   重复批准 409。
4. 人工确认的外部移动已提交再丢回复：返回未知结果，确认持久记录为 FAILED；
   重复批准、重连、应用重启后均不再次调用外部工具。
5. 每端外部工具调用总数严格为 5；所有调用均收到非空
   `personal-ai-os/idempotency-key`，人工确认标识为 `confirmation:<id>`。
   重启后成功回执仍能重放。

该验证证明本系统不会自动重发已经持有执行权的未知操作。它不意味着任意远端会实现
该元数据的去重语义；报告明确 `remote_dedup_claimed=false`。新标识、改变参数或
新会话仍可能代表新的操作，不能用来盲目重试未知结果。

## 双后端真实浏览器快捷操作

使用同一份未修改的前端生产构建、Chromium、Vite 反向代理，分别连接真实 Java 与 Go
应用。未模拟 API 返回；两处故障注入均先等待后端真正完成 HTTP 请求，再丢弃响应。

- 实际登录与 CSRF、斜杠候选菜单、方向键与 Tab 补全、帮助和 `/new`。
- `/task`、`/tasks`、`/done`，通过真实 API 回读任务数量和 DONE 状态。
- 创建响应丢失后保留当前草稿；刷新、重新输入相同命令，复用 UUID、重放回执且仅一条任务。
- 删除确认响应丢失后刷新重试，仍只有同一条待确认；拒绝后任务存在。
- 新的删除申请由用户批准后才删除；重复批准 409；旧创建回执不能重建已删除任务。
- `/calendar`、`/reminders`、通用 `/tool` 和真实 `/tools` 目录。
- 1280 / 768 / 375px 三宽度截图与菜单视口检查通过，窄屏截图人工复核。
- 每端恰好两次预期网络错误，分别对应主动丢弃的创建/删除确认响应；无其它控制台错误、
  pageerror 或 5xx；普通聊天模型请求为零。

这补齐的是快捷指令与确认操作的双端浏览器验收，不冒充原迁移全部聊天、模型、搜索、
推送及上下文 E2E 已完成。

## 可重复执行

先按已有脚本准备离线 Go 缓存并构建前端；Java 使用本轮 `clean verify` 的 JAR。
显式传入已安装的 filesystem 服务入口，脚本不会下载或使用开发 `.env`：

~~~sh
GO_TEST_GOPATH="$(go env GOPATH)" GO_TEST_GOCACHE="$(go env GOCACHE)" \
  python3 scripts/migration/run_retry_command_acceptance.py \
  --backend go --filesystem-server /absolute/installed/server-filesystem/dist/index.js \
  --output /absolute/isolated-report-directory --browser

# 同样的命令，将 backend 改为 java 并加入：
# --java-jar /absolute/verified/personal-ai-os-backend-0.1.0-SNAPSHOT.jar
~~~

原始日志、失败尝试与截图保留在 `.cache/retry-followup-20261105-054e965a/`。
准备过程曾修正 runner 的 Go 显式启动参数、不支持的 management address、
macOS `/var` 符号链接路径和浏览器 cancel 路径；未为通过测试修改业务断言或放宽门禁。
最终 Java backend、Go backend 和 frontend 快照逐字节对照源码提交，摘要记入结果 JSON。
