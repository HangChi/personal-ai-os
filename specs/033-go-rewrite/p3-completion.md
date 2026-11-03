# P3 收口与验收（2026-11-03）

固定 Java 基线：`cb5d694d62fa93742b5789b97273e76d931ce335`。T300–T305 已完成；此前的 T300a/T301a 保留为历史子阶段。源码主体在 `backend-go/internal/business/model`，显式 Foundation/CLI 已装配。P4 尚未开始，`readyz` 仍为 503，prod 仍拒绝启动；这不是全量迁移或生产切换验收。

## 交付边界

任务 | 已交付职责 | 主要证据
--- | --- | ---
T300 | V20/active/环境逐字段回落，配置与档案 CRUD、激活、排序、改名、模型清单整体替换、会话模型名继承工厂、启动应用与命名实例缓存 | `write.go`、`postgres_write.go`、`runtime.go`；真实库 `write_integration_test.go`、`runtime_test.go`
T301 | 原 AES-GCM/旧明文、掩码、nil 保留/空白清除/非空替换、写入加密、认证失败脱敏 | `keys.go`、`keys_test.go`、原 Java 双向密文夹具、真实库密钥与事务回滚断言
T302 | 标准库 OpenAI 兼容和原生 Ollama 同步/流式协议、图片、工具 schema/调用增量、额外请求体、usage、取消、无应用重试/重定向 | `protocol.go`、`protocol_test.go`，全部使用本机模拟服务
T303 | 连接测试、三种协议的模型列表/窗口探测、有界详情补查、人工窗口优先、内置目录/参考、被动证据隔代、音频状态/转写 | `probe.go`、`windows.go`、`metadata.go`、`evidence.go`、`audio.go`；Java 差分与模拟 HTTP
T304 | 嵌入适配器、默认仅观测、本地 LRU/写入后 TTL、完整选项/模型隔离、批次原子校验、专用 Redis 共享/故障回退、计数/计时/MAX/可选直方图 | `embedding.go`、`module.go`、`embedding_metrics.go`；缓存竞态/TTL/容量、真实 TLS Redis/ACL
T305 | 全部 14 条模型/档案/音频路由，原安全链、事务外热应用、最小表/序列权限、Worker 隔离与 CLI 五角色生命周期 | `http.go`、`foundation_model_integration_test.go`、两个真实存储 runner 与进程冒烟

会话模型继承指 P3 的按名解析/配置继承接口；会话表、消息、Advisor、工具执行、上下文预算器消费及用户对话 SSE 属于 P4。`Service.LookupWindow` 已提供 active 配置查找和数据库失败回落接口，预算器在 T401 接入。知识向量维度检查位于原 `knowledge/startup`，随 T601 验收；P3 构造器不自动发嵌入探测请求。

## 保留的原行为与明确差异

- 新建档案不激活；激活先停旧后启新，同一档案重复激活不改 revision/时间。排序只改顺序，改名不增 revision。原三张表、序列与 42 SQL 均复用，不自动 migrate 或 repair。
- 保存事务先提交，再构造/替换运行模型。构造失败时已保存配置保留、旧模型继续用；解析后没有密钥也保留旧模型。非 active 档案更新不切换运行模型。
- 原启动应用只读取 V20；active 档案供 current、按名解析和音频使用。Go 忠实保留此不对称行为，未擅自修改原产品语义。profile 的 protocol 用于探测；热应用工厂保持 OpenAI 兼容协议。原生 Ollama 由启动 provider 选择。
- 模型清单去 null/空白/重复并保持首个顺序。CHAT/EMBEDDING 单值列取清单第一项或旧标量；AUDIO 仅取清单。整个子表为空才合成历史 CHAT/EMBEDDING；不按用途补齐。配置窗口 >=1024 优先于内置值，不因目录更大而覆盖用户值。
- 详情补查最多 12 个、4 并发、6 秒预算、每次 2.5 秒，429 后停止新增调度。窗口只有在允许的失败类型才回落列表/目录/参考；401/403/429/网络失败不会被参考值掩盖。
- 网络诊断采用固定安全文案，省略 Java 异常类名、代理/TLS 详细原因；上游正文只在允许的探测/音频 DTO 中截断且去掉配置密钥。音频 multipart 的文件名/类型额外移除 CR/LF 并转义引号，阻止头注入。嵌入结果额外拒绝重复/越界索引、非有限向量，整批成功才缓存。这些是明确的安全收紧，不宣称异常文本逐字一致。
- 共享缓存键保持哈希 namespace/identity/digest 形状，不含原文；选项采用 Go 确定性 JSON，未承诺与 Java 的选项类名序列化跨语言命中。同一 Go 身份跨实例命中已验证；Java/Go 并行测试仍使用独立 namespace。
- 初始 OpenAI extra-body 保留属性字符串类型；热切换不携带初始 extra-body。嵌入模型在启动期固定，不随聊天配置热切换。
- MAX 使用默认一分钟三槽窗口，累计 COUNT/TOTAL_TIME 与固定 68 个桶不随 MAX 过期清零；`management.metrics.distribution.percentiles-histogram.personalos.embedding.duration` 已实际消费。

## 原 Java 测试职责映射

原清单 P3 共 17 个测试文件、231 个测试声明。以下按职责合并为 Go 用例，并在 `progress.json` 逐声明关联证据；231 是来源声明数，不是 Go 测试执行数或逐例差分数量。JPA 的“构造后 revision=0”等中间对象细节在 Go 没有对应实体生命周期，以首次提交 revision=1、后续递增和零值/空值持久化验证可观察契约。诊断异常类名等安全收紧见上一节。

原测试类 | 声明数 | Go 替代断言/差分入口
--- | ---: | ---
AiModelConfigEntityTest | 5 | `write_integration_test.go`、`config_test.go`：零温度、全空覆盖、修订/时间
AiModelProfileEntityTest | 6 | `write_integration_test.go`：新建/改名/激活/排序与修订
AiModelProfileModelEntityTest | 7 | `config_test.go`、`postgres_read_integration_test.go`：三用途、未知 kind 回落、字段/顺序
ModelConfigServiceTest | 26 | `java_oracle_test.go`、`read_test.go`、`write_integration_test.go`：回落/来源/密钥/保存/启动/应用失败
ModelProfileServiceTest | 28 | `write_integration_test.go`、`config_test.go`、`read_test.go`：清单替换、默认模型、删除/激活/排序/批量读取
ModelKeyConverterTest | 3 | `keys_test.go`、`java_model_config_fixture.py`：合成密钥双向 Java/Go 与密文认证
ModelProfileControllerTest | 24 | `p3_oracle_test.go`、`write_integration_test.go`：真实 MVC 绑定与原 schema 路由/副作用
OpenAiChatModelFactoryTest | 8 | `protocol_test.go`：原 HTTP wire、extra-body、温度、单次调用/无重试
AudioTranscriptionServiceTest | 10 | `probe_test.go`、`write_integration_test.go`：URL、不可用/空结果/失败/成功 DTO
AudioTranscriptionServiceHttpTest | 13 | `probe_test.go`、`write_integration_test.go`：multipart 默认值/文件/模型、错误状态、8MB/空音频
ModelListProbeServiceTest | 31 | `p3_oracle_test.go`、`probe_test.go`：URL/协议/鉴权/元数据；网络诊断安全收紧另记
ModelListProbeServiceHttpTest | 45 | `probe_test.go`、`p3_oracle_test.go`：列表形状/排序/去重、窗口、限额/并发/429/超时
ModelListProbeServiceWindowTest | 15 | `p3_oracle_test.go`、`probe_test.go`：详情→列表→参考、输入/总窗口、错误分类
ContextWindowReconcileTest | 5 | `write_integration_test.go`：大/小人工窗口均保留、无效/缺失回落、未知窗口不伪造
ModelWindowProbeHttpTest | 2 | `p3_oracle_test.go`、`probe_test.go`：缺失窗口补查、Ollama 原 tag 请求
CachedEmbeddingModelTest | 2 | `embedding_test.go`：默认观测、顺序/去重/选项隔离/克隆、失败不缓存
RedisEmbeddingCacheIntegrationTest | 1 | `redis_integration_test.go`：实例共享/TTL/身份/namespace/故障回退

`AiChatClientProvider` 的模型选择和 `ContextWindowEvidence`/目录/参考等 P4 来源文件已有 P3 所需接口及测试；完整 Advisor、思考预算、工具链和预算保护仍保留后续阶段状态。

## 实际执行的验证

全部协议测试只连接本机模拟 HTTP；数据库/Redis 测试由脚本创建 tmpfs 容器、自建 CA/随机回环端口，完成后清理。未读取现有 `.env`、未调用真实模型、未使用用户数据库或真实密钥。

- `java_model_config_fixture.py`：固定原 Java 23 个配置场景、8 个掩码、双向 AES-GCM 通过。
- `java_p3_fixture.py`：39 元数据 +92 目录 +14 参考 +54 MVC 绑定 +25 探测结果，合计 224 个真实 Java 差分场景通过。夹具记录固定源码哈希，默认复验、显式 `--write` 才重生成。
- Go 全量 `go test -race -count=1 ./...`：19 包通过；integration 标签的 `go vet`/`go build` 通过。后续新增模拟协议/启动/最小权限测试定向复验通过。
- TLS PostgreSQL：model、app、command 全包通过；真实 Flyway 42 迁移/validate；并发16次激活、密钥三态/密文、事务已提交后应用失败、NaN约束409与无残留副作用。补充启动继承/窗口/最小P3角色通过；P3角色不能读取知识表，缺模型表写权限/序列 USAGE 启动拒绝且无连接泄漏。
- TLS Redis：model 包通过；准确 namespace ACL、GET/PSETEX/EVAL、TTL/跨实例命中、损坏值与关闭客户端回落、越权命令/键拒绝。command 组合通过：三个独立 Redis 客户端、五角色、双端口、跨进程会话/预算、SIGTERM 与启动失败清理。
- 原 Java `./mvnw -o -B -Dapp.dotenv.enabled=false verify`：1137 tests，0 failures/errors/skipped，BUILD SUCCESS；JaCoCo 行85.79%/分支69.73%，保留原83%/65%门禁。前端 `npm run type-check`/`npm run build` 通过，Java/前端/SQL 源码未改。
- 15 项 Python 索引/配置台账、固定基线检查、变更文档本地链接及 `git diff --check` 提交前复验通过。全量 `check-complete` 继续拒绝未 verified 项和五个发布门禁；不将本阶段验收自动升级成全项目发布证据。

日志位于本机 `/private/tmp/personal-ai-os-p3-*`，非仓库长期资产；可复验脚本/夹具/断言与本报告入库。工作区曾被外部从 go 切到 main，Git 的 `cd710f7` checkout checkpoint 保存主体实现；后续在独立受管工作区的 go 分支完成验证与提交，主工作区的 main 选择保留。没有推送、部署或合并 main。

已知重试配置只接受原基线：`spring.ai.retry.max-attempts=1`，OpenAI公共/chat/embedding的`max-retries=0`。非基线值启动明确拒绝，不静默忽略或扩大模型副作用重放；`module_test.go`验证拒绝与脱敏。
