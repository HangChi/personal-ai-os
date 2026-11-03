# P3 模型配置、协议与缓存

T300–T305 已完成并接入显式 Foundation。完整职责、原 Java 断言映射、兼容差异和实际验收见 [P3 收口报告](../../../../specs/033-go-rewrite/p3-completion.md)。仅提供 P3 能力；用户对话、会话持久化、Advisor/工具、预算器消费和持久运行在 P4。

## 存储与服务

`NewResolver` 接收显式 `config.Properties`，逐字段按数据库 → 已解析属性 → 内置默认回落，保留原始 `OPENAI_*` 变量来源判断。`PostgresReadStore` 复用原三表：V20入口只读旧单行，current优先active；列表用只读repeatable-read批量查询，写操作由`Service`校验后进入短事务。档案集合锁保护激活唯一性、最后一套删除保护与排序，模型清单在同一事务整体替换。

密钥 nil 保留、空白清空并回落、非空替换。`KeyCodec` 保留 `{aesgcm:v1}`、AES-256-GCM、12字节随机IV、16字节tag及原AAD，支持旧明文与未启用加密模式。DTO只返回UTF-16末四位掩码；配置、命令、协议客户端默认格式化/JSON均脱敏。坏密文失败关闭，不返回半份配置。

保存提交后才应用运行模型；失败保留数据库配置和旧运行引用。新建不激活，inactive更新不切换。原启动覆盖只读V20，按名解析/音频读取active；该原行为已用真实库测试锁定。`Runtime.Target`提供默认/显式模型名继承、单次并发构造、32项缓存上限和换代失效，在途调用持有旧实例。`LookupWindow`提供active清单查找与失败回落，供P4预算器注册。

## 协议和 HTTP

标准库适配器覆盖 OpenAI 兼容 `/v1/chat/completions` 与原生 Ollama `/api/chat`，包含同步/SSE/NDJSON、图片、工具schema/调用增量、extra-body和usage。初始provider来自显式属性，档案热切换保持原OpenAI兼容工厂。无应用重试，不跟随重定向，取消/超时关闭流；缺少终止事件返回错误。

14条路由覆盖旧配置GET/PUT/test、档案GET/POST/PUT/DELETE/activate/order/test/probe-models/probe-window、音频status/transcribe。全部走既有认证、CSRF、限流、并发和multipart生命周期。列表支持OpenAI/Anthropic/Ollama，详情补查最多12项、4并发、6秒预算；窗口保留来源与错误证据，不用内置值掩盖鉴权/网络失败。音频8MB，连接测试20秒、转写30秒。

嵌入支持OpenAI `/v1/embeddings` 与Ollama `/api/embed`。默认只观测重复率；显式开启后本地LRU/写入后TTL、完整选项/模型身份隔离、克隆向量、按索引恢复批次顺序。Redis仅为缓存，错误回落delegate；仅完整成功批次写缓存。metrics提供cache requests、delegate duration COUNT/TOTAL_TIME/MAX、Redis读写错误，以及显式配置的68桶直方图。

## 装配与验证

`OpenModule`显式接收连接池、属性、transport及可选第三个专用Redis客户端，不读取`.env`或自动调用模型。Foundation拥有自建transport，调用者拥有借用Redis；关闭顺序为请求/执行器结束后再关闭资源。数据库需原三表SELECT/INSERT/UPDATE/DELETE、模型清单序列USAGE及P1基础权限。真实受限角色与缺项拒绝均已验证。

```bash
python3 scripts/migration/java_model_config_fixture.py
python3 scripts/migration/java_p3_fixture.py
python3 scripts/migration/run_go_postgres_tests.py --tls --package model --package app --package command
python3 scripts/migration/run_go_redis_tests.py --tls --package model --package command
cd backend-go
go test -race -count=1 ./...
go vet -tags=integration ./...
go build -tags=integration ./...
```

Java夹具只使用合成凭据和固定Git源码；运行脚本自建/清理临时TLS容器，不连接现有存储。默认离线诊断、prod拒绝与readyz503守卫仍保留。

已知重试配置只接受原基线：`spring.ai.retry.max-attempts=1`，OpenAI公共/chat/embedding的`max-retries=0`。非基线值启动明确拒绝，不静默忽略或扩大模型副作用重放；`module_test.go`验证拒绝与脱敏。
