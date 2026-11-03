# P1验收收口矩阵

2026-11-03：P1全部父任务已完成；P1内无剩余项。此文件保留原入口名称，记录本轮缺口的关闭证据。Java基线固定cb5d694；最终父项与门禁状态以[tasks.md](tasks.md)及[validation.md](validation.md)为准。子任务中的“仍待完成”是该批提交当时的边界，后续收口证据见本表，不回写历史记录。

| 父项 | 本轮关闭的缺口 | 验收与范围 |
| --- | --- | --- |
| T102 数据源/事务 | 支持/拒绝清单、迁移账号职责 | pgx固定版本、角色10/4池、真实TLS/最小权限/不确定COMMIT不重试；bootstrap、迁移属主、P1运行角色职责见契约。业务CRUD权限逐阶段追加 |
| T103 SQL/历史/恢复 | 恢复库Foundation五角色启动/关闭 | 42原SQL、真实Flyway历史/双执行器锁竞争、七类DDL、合成pg_dump/restore全行列摘要/10序列、默认V1基线；自定义schema/历史标记拒绝。真实用户备份/ACL恢复属P8 |
| T104 配置 | 78处直接调用方逐条台账；会话/上传/日志/框架属性装配 | 12类record/67字段真实Binder、133场景ConfigData、dotenv顺序；未知已接管框架扩展连接前拒绝。后续业务属性逐项绑定P3–P8任务，没有遗漏或预先声称实现 |
| T105 安全/会话 | 统一multipart/正文CSRF生命周期与本地会话/Cookie属性 | 请求只解析一次，返回/拒绝/超限/取消/panic清理；共享30分钟/SESSION/Lax/HttpOnly保留原策略。TLS/ACL、跨OS轮换/注销/网络恢复通过 |
| T106 HTTP/JSON | Servlet路径/方法/安全头与50片/512字节头边界 | 844个真实基础HTTP场景，区分容器HTML400、框架JSON400/405/413及业务信封；内容协商/UTF字节编码/UTC/Unicode通过。未来DTO和上传业务按所属阶段对照 |
| T107 共享保护 | 真实HTTP→Foundation→后台执行器请求ID链 | 只复制ID，不继承HTTP取消/身份/私有值；数据库桶/租约/fencing/API预留与专用Redis无回退均有真实存储/故障证据。持久业务Worker按P4–P6实现 |
| T108 角色/观测 | HTTP真正完成计量、histogram、Go运行指标与ECS日志 | 160原观测标签场景、68有限桶；SSE结束/取消/panic只计一次，未知URL不入标签，256序列有界。五角色race二进制、双端口、跨OS共享、SIGTERM与失败清理通过 |
| T109 综合回归 | 最终race/vet/build、真实双存储及原项目门禁 | 具体命令、耗时、原Java verify和前端类型/构建结果见最终验收记录；逐项progress证据单独维护，不将全量清单批量verified |

[P1基础运行时契约](p1-foundation-contract.md)说明全部支持/拒绝边界；[配置处置台账](config-disposition.md)列出70处@Value和8处getProperty及动态键。

P1交付基础运行时。任务、模型、聊天、Agent、记忆、日历、知识库、MCP、数据导入导出与生产切换按P2–P8串行实施。所有未验收业务保持未完成；五发布门禁及2438项清单继续独立追踪，check-complete在全量迁移前预期失败。
