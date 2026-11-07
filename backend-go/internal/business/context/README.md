# 对话上下文与离线计数

> 本页记录模块契约及分阶段验证范围。当前启动/模块组合见[Go 文档中心](../../../docs/README.md)，两版最新能力与差异见[双端说明](../../../../docs/backend-parity.md)；下文固定基线、测试数和 P1/P3 等范围不是当前全产品或生产批准。

`token.go` 实现原 Java `PromptTokenCounter` 使用的模型映射、Unicode分词和BPE；`budget.go` 管理偏好、窗口、预留、历史副本裁剪与无输出时的有界缩小重试。持久化偏好复用原 `chat_context_preference`。

`assets/` 中四份压缩词表直接提取自原依赖 `com.knuddels:jtokkit:1.1.0`，许可证标识为 MIT；上游1.1.0 tag的许可证原文及词表上游tiktoken许可证保存在 `backend-go/licenses/`，来源记录见 `THIRD_PARTY_NOTICES.md`。来源JAR及每份词表的SHA-256、长度和token数保存在 `assets/manifest.json`；生成与校验用 `scripts/migration/go_token_assets.py`。只嵌入词表数据，运行时不依赖JVM、网络下载或外部token服务。实现由本项目自行编写；没有复制JTokkit Java实现。

修改词表/映射/分词时，必须实际运行固定原Java夹具 `scripts/migration/java_context_token_fixture.py`，再执行本包race测试。1325个固定场景覆盖五种模型映射、中英文、emoji/补充平面、Unicode空白、大小写、长词与随机组合。

阶段职责、实际回归与后续业务边界见 `specs/033-go-rewrite/p4-completion.md`。本包不直接读取环境变量、文件、业务历史或模型凭据。
