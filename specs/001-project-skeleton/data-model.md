# Data Model: 阶段 0 — 项目骨架初始化

## 概述

阶段 0 **不引入任何业务实体**。本文件只记录数据库基线与后续阶段的实体规划，避免 plan / tasks 阶段各写各的。

## 数据库基线（Flyway `V1`）

| 对象 | 类型 | 说明 |
|---|---|---|
| `vector` | Extension | pgvector，阶段 3 的向量列与 HNSW 索引依赖它 |
| `hstore` | Extension | Spring AI pgvector 默认建表脚本会引用（官方文档的参考 DDL 含 `CREATE EXTENSION IF NOT EXISTS hstore`） |
| `uuid-ossp` | Extension | 提供 `uuid_generate_v4()`；与 Spring AI 参考 DDL 的默认值一致 |
| `flyway_schema_history` | Table（由 Flyway 自动创建） | 迁移历史 |

**为什么 `V1` 只建扩展、不建业务表**：阶段 0 没有任何读写业务数据的接口。提前建表属于「为想象的需求建结构」（违反原则 I）。向量表 `vector_store` 的列维度取决于阶段 1 选定的 Embedding 模型（维度未知），此时建表**必然要改**，故推迟到阶段 3。

## 迁移脚本约定

- 命名：`V<n>__<snake_case_description>.sql`，从 `V1` 连续递增。
- 位置：`backend/src/main/resources/db/migration/`。
- 每个脚本 MUST 幂等安全表达（`CREATE EXTENSION IF NOT EXISTS`、`CREATE TABLE IF NOT EXISTS`）。
- MUST NOT 在脚本里写死密钥或环境相关值。

## 后续阶段实体规划（仅规划，不实现）

| 阶段 | 实体 | 说明 |
|---|---|---|
| 1 | `chat_session` / `chat_message` | 会话与消息；聊天记忆可先由 Spring AI JDBC ChatMemory 管理，再按需落业务表 |
| 2 | `task` | 个人任务，含状态迁移（`TODO → DOING → DONE`）与截止时间 |
| 3 | `knowledge_document` / `vector_store` | 知识文档元数据 + 向量表（维度随 Embedding 模型确定） |
| 4 | `calendar_event` / `reminder` | 日程与提醒；提醒由 Spring Scheduling 触发 |

> 上述表在各自阶段的数据模型中定义，本阶段不建表、不写实体类。

## 偏差清单

| 编号 | 偏差 | 原因 |
|---|---|---|
| D-01 | 未创建 `vector_store` 表 | Embedding 模型维度在阶段 0 未定（R-02 只锁 BOM 未选模型），提前建表必改 → 推迟至阶段 3 |
| D-02 | 八个业务模块包内暂无 `entity` / `repository` 子目录 | 无实体即无仓储，空目录无意义；随各阶段落地 |
