# 原 SQL 目录与隔离重放（P1 子集）

`LoadVersioned(fs.FS)` 只读取显式目录，按整数版本排序并计算原字节 SHA-256。当前支持原 Java 的单层 V1..Vn 文件；重复、缺号、非整数版本、repeatable/undo、符号链接/子目录、不可读、空白或非法 UTF-8/NUL 全部失败，不返回部分成功结果。非 SQL 普通文件可忽略。SQL 不拆分、不插值、不改换行或编码。

目录单测逐一对照固定 Java 基线 cb5d694 的 inventory.json，必须包含全部 42 个原文件且哈希一致。当前不复制 SQL 到 Go，也没有生产迁移执行器或应用启动装配。SHA-256 是来源完整性证据，**不是 Flyway checksum**。

## 真实隔离验收

从仓库根目录运行：

```bash
python3 scripts/migration/run_go_postgres_tests.py
```

脚本创建独立临时 PostgreSQL/pgvector 容器（tmpfs、无持久卷、随机回环端口与密码）。migration 的 integration 测试另创建自己的 schema，通过事务内 SET LOCAL search_path 隔离，完整原文件逐个事务执行；结束删除自己的 schema，脚本最终清理自己的容器。不读取 .env，不连接现有 Java 数据库或写入 Flyway 历史。

两种场景均已通过：

- 空 schema 顺序执行全部 V1–V42。
- 同样从 V1 开始，在 V16/V20 后插入明确虚构的记忆/模型配置，再完成后续迁移；验证 V17 的 UTF-8 SHA-256、V21/V22 配置搬迁和默认协议、V23 模型清单裁剪与保留。

验收核对最终全部 39 张表、13 个用户触发器、vector/hstore/uuid-ossp/pgcrypto 扩展、两处 vector(1024) 和两个 HNSW 余弦索引。执行状态 CHECK、序号唯一、外键/级联、单会话 RUNNING 唯一与 WAITING_USER 兼容断言；实际向量写入/余弦查询和错误维度拒绝；V35–V42 的文档来源初始化、修订/审查重试重置、搜索 claim 重置、事务内唤醒 Outbox 和候选代数更新。失败事务不得残留业务/唤醒记录。

## 待完成

生产迁移执行权与角色、真实 Flyway 历史/CRC32/校验失败/迁移锁、存量备份恢复、Java Flyway 与 Go 重放的完整结构差分仍未完成。合成历史数据不是用户备份。以上 schema/触发器断言不代表业务仓储、索引 Worker、消息发布或完整模块已迁移；T103 与 P1 保持未完成。
