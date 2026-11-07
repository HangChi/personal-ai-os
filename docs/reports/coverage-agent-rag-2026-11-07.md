# 2026-11-07 Agent / RAG 测试与覆盖率扩展

本轮最终结果：**Java 分支覆盖率 95.09%，Go 完整测试集合的语句覆盖率 95.05%**。Java 与 Go 使用各自的覆盖率工具；Go 默认测试单独为 89.16%，95.05% 包含隔离集成测试和真实进程 smoke，不将两种口径混为一谈。

## 构建与执行范围

- 日期：2026-11-07，Asia/Shanghai；最终验证约于 20:13 完成。
- 基础提交：`a47a928847ba7aa3dd8f679d6ae36beb2ae6f47a`，分支 `go`。
- Java 测试及共用夹具：`fe949546de36ef0ac1bb37a6d2e2198a95984e61`。
- Go 测试、隔离 runner 及装配清理修复：`74722b31b5f751b582059e78eb6131f83368a36b`。
- 环境：Java 21、Go 1.27.1、Docker。测试使用合成数据、模拟模型和离线 HTTP 替身，不读取实际 `.env`，不请求收费模型。
- 集成 runner 自行创建 PostgreSQL/pgvector、Redis、RabbitMQ 和 OpenSearch，使用随机回环端口和临时存储；只清理本次创建的容器，不操作已有业务库。
- 未修改覆盖率排除项、业务代码统计范围或原有门禁。Java 原有行 83% / 分支 65% 门禁继续保留，本轮目标由独立汇总脚本按未四舍五入的数值校验 95%。

## 最终覆盖率

| 语言与测试集合 | 指标 | 已覆盖 / 总量 | 结果 |
| --- | --- | ---: | ---: |
| Java 全量 `verify` | JaCoCo 分支 | 6,428 / 6,760 | **95.0888%** |
| Java 全量 `verify` | JaCoCo 行 | 10,920 / 11,454 | 95.3379% |
| Go 默认全量测试 | 语句 | 18,171 / 20,380 | 89.1609% |
| Go 默认 + 全部隔离集成 + 真实进程 smoke | 语句 | 19,398 / 20,409 | **95.0463%** |

Java 最终执行前移走旧的默认 `target/jacoco.exec`，重新运行全部测试；最终 JaCoCo XML 只有一个 session。没有用定向测试累积结果代替最终全量验证。

Go 最终结果只合并本次完整测试产出的 **31 份 atomic profile**：默认全量 1 份、PostgreSQL 测试 18 份及进程 smoke 1 份、Redis 组合测试 7 份及进程 smoke 1 份、P6 设施组合测试 3 份。未并入此前用于定位缺口的定向 profile 或旧轮次 profile。

Go 原生工具报告语句覆盖率，不报告分支覆盖率。各 profile 按源文件和 block 起止位置去重、累加命中次数；合并脚本拒绝语句数或同一源文件 block 布局不一致的输入。集成 build tag 额外编译的 `internal/platform/testdb` 有 29 条语句，完整集合的分母因此从 20,380 增至 20,409，未排除这部分测试支持代码。

### 同口径历史对比

| 指标 | 本轮扩展前的初始审计 | 最终 | 增量 |
| --- | ---: | ---: | ---: |
| Java 分支 | 4,726 / 6,760，69.91% | 6,428 / 6,760，95.09% | +1,702 分支，+25.18 个百分点 |
| Java 行 | 9,855 / 11,454，86.04% | 10,920 / 11,454，95.34% | +1,065 行，+9.30 个百分点 |
| Go 默认语句 | 9,340 / 20,380，45.83% | 18,171 / 20,380，89.16% | +8,831 语句，+43.33 个百分点 |

完整 Go 集合的 95.05% 不直接与仅运行默认测试的 45.83% 比较；初始审计没有相同完整集成集合的基线。

### 重点模块

| 模块 | 指标 | 最终结果 |
| --- | --- | ---: |
| Java `RagRetrievalPipeline` | 分支 | 108 / 110，98.18% |
| Java `KnowledgeRetrievalService` | 分支 | 113 / 114，99.12% |
| Java `KnowledgeGovernance` | 分支 | 137 / 140，97.86% |
| Java `ChatRunService` | 分支 | 202 / 214，94.39% |
| Go `agent` 包 | 完整集合语句 | 1,020 / 1,050，97.14% |
| Go `chat` 包 | 完整集合语句 | 3,604 / 3,846，93.71% |
| Go `knowledge` 包 | 完整集合语句 | 3,130 / 3,303，94.76% |
| Go `model` 包 | 完整集合语句 | 2,105 / 2,178，96.65% |

95% 是各语言整体指标，不表示每个包、类或文件均达到 95%。

## 新增业务回归

[共用夹具](../../testdata/README.md)包含 164 个合成契约用例，覆盖计划迁移、结构化提问、证据审查、引文审计和恢复冲突范围。两版分别用实际业务实现消费夹具，另补各自的本地用例，不要求测试文件一一对应。

夹具 SHA-256：`3d0ca790e4c8b0b7c24ab4deb0c4b2cf2be16db38db2da56381abaad6139ca95`。算法：按仓库相对路径排序，每个 JSON 的 UTF-8 路径、NUL、原始文件字节、NUL 依次输入 SHA-256。

扩展覆盖以下决策与副作用：

- **Agent 执行与恢复**：工具参数/schema、调用预算、执行异常、审计失败、确认回执、幂等和重复执行保护；计划/提问/跟进状态迁移；运行容量竞争、取消、恢复、关闭及租约释放。
- **流式执行与上下文**：先持久化再发布完成事件，summary-only 阻止外部读取和工具调用，未知副作用不重放；摘要并发检查、截断和超时；模型切换代数、上下文窗口证据、文本与图片 token 校准隔离。
- **RAG 检索**：查询改写失败与超长结果、混合召回/RRF、去重和文档多样性、重排缺项/非法评分/网络失败、部分召回故障、取消与预算；空文档 ID 不生成有效引文。
- **证据与治理**：证据不足、冲突和澄清保护，来源/revision/精确 quote 校验，人工决策与恢复范围，审查失败回退、全局引用编号、MCP 文本来源回退和来源数量限制。
- **摄入与存储**：Markdown、PDF、DOCX、网页/DNS、远程对象和本地附件；向量残留、队列重试、任务唤醒、fencing、事务失败与回滚；租约在错误路径释放，失败后不得继续写入。
- **HTTP 与配置**：媒体协商、HEAD、JSON 数值/布尔边界、multipart 和读取错误；模型保存/激活/缓存/Redis 配置失败；凭据脱敏、生产校验、保留策略、任务/日程/工作流/维护入口。

SQL 顺序替身用于验证查询参数、事务提交/回滚、失败后的零额外写入与业务决策，不能代替 PostgreSQL 约束、锁和隔离级别验证。后者由实际隔离集成测试承担。

## 业务修复与测试设施修正

本轮唯一业务源代码修改位于 Go `knowledge.OpenModule`：原具名返回变量 `m` 同时被 defer 清理和 worker 闭包捕获，`return nil, err` 会将它覆写为 nil。现在使用稳定的局部模块引用，与具名返回值分开，保证失败装配能关闭已启动资源；配置失败回归及 race 测试验证清理路径。

完整集成验证还修正了旧测试设施的过期预期：迁移历史从 42 对齐到 49；最终 schema 为 48 个业务表和 21 个触发器；备份比对包含 49 个表（含 Flyway 历史）及 10 个序列。备份数据比对改为比较 runner 实际新建的合成备份源库与恢复库；表达式索引检查限定 `public`，避免另一个 baseline 测试 schema 的同名索引误报。固定 V1–V42 的原 SQL/hash oracle 保持不变。

历史 P8 清单预检曾因本轮测试文件字节变化而拒绝旧候选 hash。当前 scope ledger 已重绑到已提交源码；原基线 hash、历史记录和 release gates 不变，状态继续 `in_progress`，不以覆盖率替代整组迁移验收。

## 完整验证结果

| 验证 | 结果 |
| --- | --- |
| Java `verify` | 245 个测试类、1,753 项测试；0 失败、0 错误、0 跳过 |
| Java JaCoCo | 新 exec、单 session；原门禁及本轮 95% 分支目标通过 |
| Go 默认全量 `-race -count=1` | 35 个包通过；6,750 项测试/子测试通过，0 失败；stdio 辅助进程入口跳过 1 项 |
| PostgreSQL / TLS | 全部 18 个包通过；真实 Java Flyway、baseline、合成备份恢复和两种应用启动配置均通过 |
| Redis / TLS | 7 个包通过，包含 PostgreSQL 组合、ACL、缓存及恢复路径 |
| P6 设施组合 | RabbitMQ、OpenSearch、TLS Redis/PostgreSQL；knowledge、app、calendar 通过 |
| 真实 OS 进程 smoke | PostgreSQL、Redis 组合各一次；五种角色、管理探针、启动失败、SIGTERM 与资源释放通过 |
| Go `vet -tags=integration` / `build` | 通过 |
| 覆盖率合并器 / 迁移脚本单元测试 | 合并器 5 项、迁移脚本 161 项，全部通过 |
| 文档与差异检查 | 本地链接/锚点、Java/Go 工具名、API 路由生成检查、`git diff --check` 通过 |

## 复现

在仓库根目录执行；需要 Java 21、Go、Docker 及已准备的依赖和测试镜像。离线 runner 可通过 `GO_TEST_GOPATH`、`GO_TEST_GOCACHE` 指向已有 Go 缓存。以下命令不会使用实际应用 `.env`。

```sh
AUDIT_DIR=$(mktemp -d "$PWD/.cache/coverage-final-XXXXXXXX")

# 全新默认 exec，确保 report 与 check 使用同一份数据。
if [ -f backend/target/jacoco.exec ]; then
  mv backend/target/jacoco.exec "$AUDIT_DIR/previous-java.exec"
fi
(cd backend && ./mvnw -B --no-transfer-progress verify) > "$AUDIT_DIR/java.log" 2>&1
cp backend/target/site/jacoco/jacoco.xml "$AUDIT_DIR/jacoco.xml"

(cd backend-go && go test -race -json -coverpkg=./... \
  -coverprofile="$AUDIT_DIR/default.out" -covermode=atomic \
  -count=1 -p 1 -timeout=20m ./...) > "$AUDIT_DIR/default.log" 2>&1

python3 scripts/migration/run_go_postgres_tests.py --tls --backup --baseline \
  --coverage-dir "$AUDIT_DIR/postgres" > "$AUDIT_DIR/postgres.log" 2>&1
python3 scripts/migration/run_go_redis_tests.py --tls --backup \
  --coverage-dir "$AUDIT_DIR/redis" > "$AUDIT_DIR/redis.log" 2>&1
python3 scripts/migration/run_go_p6_tests.py \
  --coverage-dir "$AUDIT_DIR/p6" > "$AUDIT_DIR/p6.log" 2>&1

# 单独保留默认覆盖率，不用集成结果改写默认指标。
python3 scripts/coverage/summarize.py --go-profile "$AUDIT_DIR/default.out" --minimum 0
python3 scripts/coverage/summarize.py --java-report "$AUDIT_DIR/jacoco.xml" \
  --go-profile "$AUDIT_DIR/default.out" --go-profile "$AUDIT_DIR/postgres" \
  --go-profile "$AUDIT_DIR/redis" --go-profile "$AUDIT_DIR/p6" \
  --write-go-profile "$AUDIT_DIR/merged.out" --minimum 95

(cd backend-go && go vet -tags=integration ./... && go build ./...)
python3 -m unittest discover -s scripts/coverage -v
python3 -m unittest discover -s scripts/migration -p 'test_go_*py'
python3 scripts/docs/check_docs.py
python3 scripts/docs/generate_routes.py --check
git diff --check
```

本机最终证据保留在忽略提交的 `.cache/coverage-95-final/`：Java `java-final.log`、单 session XML/exec，Go `go-default-final.log`/profile、`postgres/`、`redis/`、`p6/`、真实进程覆盖数据、`summary.json` 和 `final-merged.out`。PostgreSQL 修正过期测试后分两段成功执行，日志为 `postgres-before-knowledge.log` 与 `postgres.log`；最终目录内每个包只使用已成功重跑的 profile。原始大日志和临时凭据不纳入公开仓库。

## 剩余边界

Java 仍有 332 个分支、534 行未覆盖；Go 完整集合仍有 1,011 条语句未覆盖，默认测试仍有 2,209 条未覆盖。部分模块低于整体覆盖率，后续可继续补网络故障、异步竞争及生命周期边界。覆盖率不证明真实模型回答质量、生产负载或线上外部服务行为；本轮没有重新执行真实模型评测或前端测试，也没有升级生产发布状态。
