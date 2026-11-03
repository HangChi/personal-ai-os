# P4 首批：会话存储与独立 HTTP 模块（2026-11-04）

固定 Java 基线：`cb5d694d62fa93742b5789b97273e76d931ce335`。本批完成 T400a，T400 父项及 T401–T409 仍开放。代码在 `backend-go/internal/business/chat`；没有把新路由挂进生产 Foundation/CLI，也没有开放 Go 生产运行或解除 `readyz` 的 503。

## 已交付

- 复用原 `chat_session`、`chat_message`、`chat_message_citation` 和全部原迁移。会话创建/查询/搜索/改名/模型选择/删除、最新消息分页和向前游标、rewind、引用快照及 Markdown 下载提供独立服务和九条存储路由。
- 写入在同一事务连接内锁定会话、分配连续序号、追加消息并更新活动时间；不裁剪持久历史。改名和模型选择保留活动时间。读取使用只读 repeatable-read，消息和引用来自同一快照。
- 保留 Java 空白、UTF-16 长度、验证顺序、`null`/空数组、分页参数与通配符转义。无 limit 时保持完整历史并忽略 beforeSeq。引用每 512 条消息批量读取，覆盖超出两个批次的边界并保留引用顺序。
- rewind 从指定序号起删除；摘要覆盖到该序号时同时失效。引用依赖原外键级联；引用快照不依赖原知识文档仍然存在。序号在 rewind 后可复用。
- Markdown 保留显式时区、标题/消息顺序、附件名、引用来源（不含引用正文）、空会话提示、未闭合代码块和 Java 文件名编码。
- 附件快照在删除前读取，清理端口在事务提交且连接归还后调用。没有装配清理端口时，带附件历史的删除/rewind 明确失败且不修改记录。这是迁移期间的安全边界，真实文件生命周期尚未实现。

## 原行为对照与验证

`scripts/migration/java_chat_storage_fixture.py` 编译固定 Git 基线的 22 个原 Java 源文件，调用实际 MVC 绑定、服务归一化、附件快照反序列化和 Markdown 导出，记录源文件 SHA-256。默认模式重新执行并比较已提交夹具，只有 `--write` 才重生成。不启动原应用，不加载应用配置，不连接现有服务。

场景 | 数量 | 覆盖边界
--- | ---: | ---
MVC/参数绑定 | 138 | 创建/改名/模型选择、缺失/null/错误正文、字段大小写/标量转换、Unicode/十六进制/符号/溢出参数、验证先后
Markdown | 22 | 三个时区、附件/引用、空会话、非法文件名、UTF-16 截断与 URL 编码
附件快照 | 22 | 缺失/空值、损坏 JSON、数字/字符串大小、越界、字段大小写
查询归一化 | 12 | 分页默认/上限/溢出、空白、Unicode 小写、`%`/`_`/反斜杠转义
首条标题 | 12 | 默认与自定义标题、已有消息、空白折叠、30 UTF-16 单元截断
合计 | 206 | 只代表本表列出的差分范围

真实数据库测试使用 runner 新建的回环随机端口 TLS PostgreSQL/pgvector；原 Java Flyway 重放 42 迁移并 validate。六组存储测试覆盖搜索/分页/活动时间、两个独立连接池的 24 路双消息并发、NUL 写入回滚、引用部分失败回滚及跨服务读取、1025 条消息的引用批次边界、rewind 摘要/提交后清理/数据库触发失败回滚，以及全部九条 HTTP 存储路由。

应用组合测试只使用现有 `Business` 端口接入临时测试服务器：未登录读取 401、缺 CSRF 写入 403、登录后创建与读取成功；index/review/delivery 均不调用业务 Handler 并返回空 404；readyz 仍返回 503。该测试不代表生产模块已接线。

具体实际命令和结果记录于 [validation.md](validation.md)。

## 原测试职责映射

- `ChatSessionServiceTest`：创建/默认标题、长度/ID、首消息标题、改名与模型选择由 Go 单测、原 Java 差分及真实库测试共同覆盖。
- `ChatMessagePaginationIntegrationTest`：完整历史、最新切片、向前游标、头部余量、非法 limit 与未知会话由真实库/HTTP 测试和差分覆盖。
- `ChatMarkdownExporterTest`：八个原测试职责由固定原导出器夹具覆盖。
- `ChatMessageServiceRewindTest`：快照提取、无附件、损坏快照及清理时序已覆盖；真实附件文件删除未实现，不能将该类全部职责记为完成。
- `JpaChatMemoryTest`：只移植追加/活动时间/空批次/TOOL 持久化职责；历史上下文窗口、字符预算、角色映射、clear 等完整 Memory 接口仍待 T401。
- `ChatSessionController` 只迁移本批九条存储路由；stream/context/context-budget 等仍开放，未声称完整 Servlet OPTIONS/全部内容协商已逐项差分。

进度台账只增加 `implemented`/`in_progress`，不批量提升为 `verified`，不把附件服务、JpaChatMemory、完整控制器及原测试类的部分覆盖冒充全量验收。

## 后续顺序

先补完整 T400 的应用/CLI 接线和附件生命周期协作边界，再执行 T401 上下文窗口/摘要/预算及 T402–T403 工具/计划，随后完成旧 SSE、持久 runs、恢复、交互队列和附件模块及端到端用量/流验收。T408 本地/S3 文件读写与孤儿清理仍需真实实现和失败测试。不得以本批独立模块替换生产入口或宣称 P4 完成。
