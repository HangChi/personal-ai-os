# 验证记录

日期：2026-11-03。分支：`go`。固定 Java 基线：`cb5d694d62fa93742b5789b97273e76d931ce335`。

## 已执行：P0 基线工具

```bash
python3 -m unittest discover -s scripts/migration -p 'test_*.py' -v
python3 scripts/migration/go_inventory.py check
```

结果：4 项提取器边界测试通过；基线检查通过。覆盖注释/字符串/文本块不产生假注解、无参数/命名/多路径映射、动态错误路径、紧凑 Controller 与嵌套 record。

实际索引：1,093 个基线文件；Java 主源码 391、测试文件 154、SQL 迁移 42；注解 HTTP 路由 104、内部工具 23、MCP 工具 4、运行/配置钩子 95（含调度 13）、测试声明 1,119；显式环境变量名 145。

实际执行 `check-complete`：按预期返回非零，报告 2,438 个清单项尚未 verified、五项发布门禁未验证，确认未完成的迁移不能被误报为完成。

清单生成读取的是固定 Git blob，不读取本机 .env、备份、缓存或日志。检查会对照当前文件哈希、新增文件、生成文档和进度证据。

## 已执行：P1 基础代码

工具链：`go version go1.27.1 darwin/arm64`。所有 Go 命令使用可写临时缓存，`GOPROXY=off GOTOOLCHAIN=local`，没有下载第三方依赖。

在 backend-go 目录执行：

```bash
go test -race -count=1 ./...
go vet ./...
go build -o /private/tmp/personal-ai-os-go-cb5d694 ./cmd/personalos
```

结果：四个有测试的 Go 包全部通过竞态测试；命令入口编译通过；vet 通过；构建通过。该结果只覆盖已实现的基础契约，不代表 Java 业务完成。

覆盖内容：固定 Java 19 个错误码/文案/HTTP 状态快照、四字段信封/UTC/空 data、序列化失败脱敏、内存分页/SQL 页/空数组/大页码/Java offset 边界、五种角色及非法配置、Java trim/ASCII 请求 ID/32 路并发 context 隔离、诊断 200/503/405/HEAD 和未迁移 API 404、Worker 空 404。

真实进程冒烟脚本尝试申请 `127.0.0.1` 临时端口时，沙箱返回 `PermissionError: [Errno 1] Operation not permitted`；错误发生在启动 Go 子进程之前，因此未启动遗留进程，也没有实际验证监听、HTTP 网络链路和 SIGTERM 退出。处理器行为由 httptest 验证，不能替代进程验收。

## Git 提交状态

- 已提交：`f255fec chore(go): 固定 Java 迁移基线及防遗漏检查`。
- 用户已在本地完成：`b125f7e docs(go): 制定全量兼容迁移规格与分阶段验收计划`、`d59f85b feat(go): 添加基础响应契约与诊断启动入口`；本轮开始时工作区干净，分支仍为 go。
- 此前文档的后续暂存/提交尝试被自动审批服务连续返回 503 阻断，失败的操作未执行。这是审批基础设施故障，不是对修改安全性的否定；日志没有提供具体服务端原因。
- 后续仍按小阶段提交，不绕过 Git 写入审批。未完成的提交不算已保存到提交历史。

## 本轮：P1 配置来源与安全启动子集

已执行 `go test -race -count=1 ./...`、`go vet ./...` 和命令构建；测试通过。新增覆盖 Java .env 字面量解析、重复键顺序、CR/LF/CRLF、Unicode strip 差异、无效 UTF-8、最近文件/三层父目录边界、空值与系统环境覆盖、profile 静态值高于文件、运行角色顺序、生产守卫顺序/中文错误/16–72 UTF-8 字节边界、日志格式化脱敏。入口测试证明 prod 在监听之前被拒绝；这不验证真实监听或认证实现。

前端已实际执行 `npm run type-check` 和 `npm run build`，均通过，前端源码未修改。尚未跑全量 lint/组件/E2E。

Java 已实际执行 `./mvnw -o -B -Dapp.dotenv.enabled=false verify`，失败：汇总 859 tests、0 failures、494 errors；报告包含 Docker 环境不可访问、HTTP 模拟器 Socket Operation not permitted 等沙箱限制。未跳过失败项来声称全绿；真实环境 Java 全量质量门禁仍待恢复环境后重跑。

查询 pgx 可用版本时 DNS 失败，联网审批返回 503，命令未执行；未下载或锁定未经验证的驱动。Docker socket 访问被拒绝，未启动容器、未连接现有数据库。T102/T103 仍未完成。

## 未执行 / 尚未实现

- Java 全量 Maven 回归已尝试但未通过；前端类型与构建已通过，完整前端检查仍待完成；本次未修改原 Java/前端实现。
- 没有跑 Java/Go 业务差分、数据库或任何真实第三方基础设施集成。
- 没有迁移现有数据库、模型密钥、附件、Redis 会话、RabbitMQ/OpenSearch/S3 数据。
- 没有替换本地运行实例、推送分支、合并 main 或执行部署。
- 全部业务模块和路由迁移、完整配置/认证/安全/持久化/模型/后台任务均未完成。
- `check-complete` 当前必须失败；最终验收依赖 tasks.md 的 P1–P8 和五项发布门禁。

## 证据边界

源码哈希和词法索引能防止基线文件静默缺席或漂移，不能证明 Go 方法行为与 Java 完全一致。必须结合代码审查、逐项测试替代、真实数据升级/恢复、故障注入与前端验收，才能将条目标记为 verified。
