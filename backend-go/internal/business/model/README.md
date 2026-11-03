# 模型配置：P3 首批读取与密文兼容

当前实现 T300 的读取子集和 T301 的转换器子集，尚未挂载模型 HTTP 路由或应用启动钩子，不代表整个 P3 已验收。

- `NewResolver` 只接收已有 `config.Properties`，逐字段按数据库 → 已解析属性 → 内置默认回落，并单独查询原始 `OPENAI_*` 名称以保留来源徽章。温度按原 Java 解析语义回落；音频无内置模型。
- `PostgresReadStore` 读取原 `ai_model_config`、`ai_model_profile`、`ai_model_profile_model`，不执行迁移或业务写入。`Current` 在单语句快照中选择 active 档案，否则回落 V20；旧单行读取入口仍只读 V20。列表在只读 repeatable-read 事务中批量查询档案和模型，避免 N+1。
- 列表保持原 `sort_order, created_at` 及子项 `kind, sort_order, id` 顺序。只有整个模型子表为空时才从旧列合成 CHAT/EMBEDDING；不会把空的某个用途或 AUDIO 自动补回。未知窗口不在 DTO 中伪造数值。
- `KeyCodec` 保留原 `{aesgcm:v1}`、Base64、32 字节主密钥、12 字节随机 IV、16 字节 GCM tag 和 `personal-ai-os:model-api-key:v1` AAD。兼容旧明文与未启用加密的本地配置；缺失/错误主密钥、损坏或篡改密文都拒绝并返回通用错误。
- 内部配置/解析器/转换器的 JSON 和格式化结果均脱敏。密钥只经显式访问器供未来协议适配器使用；对外必须映射为 DTO，短密钥全掩码、长密钥只保留 Java UTF-16 末四位。Java UTF-8 输出中被截断的代理对替换为 `?`，由真实夹具锁定。

离线对照固定 Java 源码（先有原 Maven verify 生成的依赖清单与构建产物；脚本不运行 Maven、不下载依赖）：

```bash
python3 scripts/migration/java_model_config_fixture.py
```

脚本复验 23 个配置合并/DTO 场景、8 个掩码，并用原转换器解密 Go 随机密文。Go 单测读取原 Java 密文。仅合成密钥进入夹具；`--write` 才会显式重生成期望值。

```bash
cd backend-go
go test -race -count=1 ./internal/business/model
```

真实数据库测试必须通过仓库专用脚本新建容器、真实 Flyway 重放原 42 个迁移：

```bash
python3 scripts/migration/run_go_postgres_tests.py --tls --package model
```

测试核对 legacy/active 回落、更新时间/修订、排序、JSON 空数组/null、子表整体兜底、Java 密文持久化读取、错误配置不返回半份结果、无关密文不影响当前配置解析、取消及读取无副作用。测试不连接现有数据库，不修改 SQL，不读取 `.env`。

下一批：配置/档案 CRUD、激活/排序写入、保留/清空/替换密钥、会话模型继承、事务外应用与启动钩子，再进入协议调用和全路由差分。当前默认诊断/生产拒绝/readyz 守卫均未改变。
