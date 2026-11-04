# P6 知识、RAG 与后台运行验收

状态：实施中。T600–T609 必须逐批验证；当前报告不表示 P6 或发布门禁已完成。

## 第一批：文件解析与结构切分

提交：`849c625`。

新增 knowledge Reader 与 Markdown 切分器。UTF-8 严格校验、BOM、300 UTF-16 文件名/保扩展名、目录移除、大小/短文拒收、图片 MIME/无文本/上游错误均覆盖。DOCX 使用标准库 archive/zip 与 encoding/xml，读取正文段落和表格单元格，保留顺序；拒绝 ZIP bomb、超深 XML 与损坏输入。PDF 使用独立 Poppler pdfinfo/pdftotext，校验完整页数（最多 500 页），不截掉第 501 页；10 秒 context/进程退出和 64 MiB 输出上限，不构造 shell。无文本、加密和损坏错误保留分类。

Markdown 沿用原标题栈、代码围栏、120 字符章节前缀、段落累积、70%自然断点、重叠和 1.5 倍短块合并界限；另保证硬切不会制造半个 Unicode 码点。

实际执行：knowledge race 测试通过（6 个测试，含真实现场生成 PDF）；首批 25 个 Go 包全量 race 和固定基线 check 通过。模型调用装配、数据库写入、任务队列与真实 Java 差分待后续批次。

## 第二批：向量原子写入与持久索引任务

Store 使用既有Pool/Coordinator，保留原schema、vector(1024)、JSON元数据和数据库触发器。所有模型调用在事务外完成；替换/删除共用document租约，最终写事务检查数据库所有者。索引任务保留20个排队上限、摘要幂等、60秒租约/10秒续租、3次重试、payload清理、7日保留；结果与文档/向量在同一事务提交，并同时检查任务token与文档租约。

实际执行：5个真实TLS PostgreSQL集成测试与6个单测全通过race，涵盖16路抢占、24路API提交、失效重领/最终fencing、失败不破坏旧代和完整删除。HTTP/角色装配及Java全部来源断言尚在后续批次推进。

## 第三批：混合召回与候选缓存

新增向量阈值、英文标识符与中文候选补召回、按全量命中数计算 IDF、加权 RRF、文档去重和 LIKE 字面量转义。可选外部搜索仅返回候选 ID，正文重新查询 PostgreSQL；投影积压或外部失败退回本地召回。Redis 候选缓存使用专用客户端、namespace/config/query 哈希、20 秒 TTL、256 KiB 限制与数据库 generation；事务/关闭时绕过，故障直接执行召回。

实际执行：独立 TLS PostgreSQL/pgvector + TLS Redis，重放42原迁移后17项知识包测试全部通过 race。覆盖中文低相似度补召回、八文档窗口统计先于截断、失效外部ID、缓存隔离/TTL/来源变更/并发失效/关闭和Redis故障。发现并修复真实 PostgreSQL 的 ESCAPE 字面量错误。完整模块装配与端到端验收仍待后续批次。

## 第四批：搜索投影与持久唤醒

OpenSearch 使用有界 REST、CJK/HNSW 映射和现有维度验证，external_gte 版本写入、删除及冲突幂等。投影沿用原数据库 Outbox，短事务领取、60秒失效恢复、revision/token 条件确认及指数退避；重建只入队。RabbitMQ 队列持久化、消息 TTL/上限、persistent 发布与 publisher confirm；通知只唤醒执行器，数据库任务仍独立领取和轮询，信号按 revision 条件延迟重发。

实际执行 `python3 scripts/migration/run_go_p6_tests.py`：四个独立 tmpfs 容器，PostgreSQL/Redis 使用 TLS，RabbitMQ/OpenSearch 使用随机回环端口且固定本地镜像摘要。真实 OpenSearch 验证权威回表/旧版本拒覆盖/删除；真实 RabbitMQ 验证确认发布、重复/非法通知不消费业务任务、消费者重连。投影新 revision 不被旧确认删除、失败退避、重建、信号旧确认不推迟新 revision 也通过 race。首次测试容器启动配置已修复，成功后全部临时容器清理；API/五角色装配仍在下一批。
