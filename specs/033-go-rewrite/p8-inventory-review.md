# P8 全清单职责处置

固定Java基线：`cb5d694d62fa93742b5789b97273e76d931ce335`。本记录区分代码职责移植、原样保留与尚未通过的真实验收，不能据清单条数宣布等价或发布完成。

逐文件SHA-256、处置类型与状态见[p8-inventory-disposition.json](p8-inventory-disposition.json)，原声明逐项测试见[p8-test-mapping.md](p8-test-mapping.md)。前阶段实现/差分证据保留；新增源代码职责不是按目录统一通过，而是在`go_p8_inventory_progress.py`显式列出原文件、Go实现、测试与架构对应说明。任何未映射Java代码或运行钩子使审计失败。

## 剩余运行职责

| 原职责 | Go承担方式与证据 |
| --- | --- |
| Spring启动/配置扫描 | 显式CLI/Foundation；原ConfigData/Binder默认与profile对照、各角色构建/资源清理 |
| ContextProperties/窗口注册 | 类型化配置、当前生效档案CHAT窗口查询与nil/异常回落；显式注入Budget，不造Spring注册器 |
| Auth/Security/SharedSession装配 | BCrypt/失败锁定、会话旋转与CSRF；共享Redis/TLS/ACL/固定Cookie策略、跨OS与故障恢复 |
| 独立Monitoring链 | 管理监听真实health/prometheus，应用/环境路径拒绝，业务监听不泄露指标 |
| ContextTask/MDC/Reactor钩子 | 有界1/2工作单元+32队列、满丢弃；context只传requestId，执行器独立生命周期，不模仿线程本地容器 |
| Dotenv/生产校验/限流 | 显式文件优先级及测试禁用、原安全校验；限流参数/平滑复充/数据库时钟/Redis TIME与150ms，不回退 |
| 确认DTO/状态/仓储/Token | 原动作枚举与DTO、一次令牌、独立事务CAS、失败/取消/过期真实双池观察 |
| 幂等DTO/结果/审计仓储 | 固定摘要、持久占位/重放、审计过滤在分页前、R-053未知TOOL保护 |
| PlainText/Texts/JSON | 实际Poppler、媒体/扩展名与截断；Java strip/UTF-16/错误文案；稳定JSON和Java摘要真实回放 |
| Worker/RequestId/数据库关闭钩子 | 依赖前角色隔离、context/响应/日志一致、停止续约及仅释放本实例租约 |
| 任务DTO/枚举/载荷 | 原字段绑定/省略、完成/重开不变量、分页/全局统计、标签批取与确认前无写 |
| Java测试辅助类型 | 确定向量/脚本模型/HTTP客户端用httptest与显式依赖；Instants职责由迁移比较器承担；package-info按领域依赖保留 |

剩余两路由（错误入口与公开业务health）和14个装配/配置/停止钩子逐项补入运行证据。所有104路由、27工具、95钩子、1119测试声明均有implemented/verified处置；这不是把发布门禁改为verified。

## 原样保留的资产

| 范围 | 处置及实际边界 |
| --- | --- |
| 42条SQL | 逐文件SHA冻结；真实Flyway锁竞争/validate/零重复、空库/合成升级/Java→Go→Java回退；Go只验证历史 |
| 原Java构建/资源 | Maven/Java继续可用，1137测试及JaCoCo通过；Go模块/镜像/配置与候选CI并行，原配置不改 |
| 前端源码/测试/构建 | SHA不变；lint/format/type-check/1151逻辑/串行195组件/build、真实Chrome10页三宽度及流程；真实Push另列未通过 |
| 原Compose/Caddy/监控 | 保留Java资产；Go另建combined/distributed、Flyway/TLS数据库、非root/只读与Caddy；Prometheus/Grafana/六告警规则/本地发现真实验证 |
| 本地运维/备份/Push密钥/演示配方 | Go独立状态和显式目标；原归档格式与新随机库恢复/回退、0600/拒绝覆盖实测。演示七组配方在新随机TLS空库/认证Go真实回读通过，禁止原main/reset，不操作用户运行数据 |
| 业务规格/历史报告/仓库模板 | 原文与SHA保留；当前Go操作及差异在新增docs/specs中，不把旧Java历史报告当Go实测 |

## 保持开放的处置

23个文件仍为in_progress：3个生产配置、4个真实Push相关源码/测试、原E2E入口，以及15个原在线语料/模型评估集合文件。原演示配方隔离适配已完成，证据见[p8-demo-seed.json](p8-demo-seed.json)。数量只帮助定位，不能替代逐项说明。

- 生产TLS/证书续期及发布批准没有实测材料；原生产安全校验通过不等于生产入口通过。prod拒绝、readyz503继续保持。
- 真实FCM接受后15秒内无Service Worker push事件；synthetic CDP控制通过只说明本地通知链，不能升级真实投递状态。
- 原演示脚本main固定连接现有容器、默认清空全部业务表，仍禁止直接运行。Go适配仅加载原七组配方并替换IO边界；原main/reset从未调用，已验证隔离回读不等于对用户库执行授权。
- 原E2E保持80 PASS/1与固定Java同FAIL/1原case SKIP/2 NOTE，不修改原断言凑绿。
- 原在线评估含外部采集、全库重建与真实模型；仓库原26篇全文不足，18组受控召回不能冒充原语料或生产模型语义质量。只读/纯单测也不能解除整个集合的在线评估职责。

真实用户备份、外部副作用核对、通知最终送达、生产容量/峰值与用户验收仍是独立门禁。五release_gates状态与证据保持原样，2438项均未自动升级verified；check-complete仍应失败。T803父项保留开放，不能把“审计已记录”误写为“未完成职责已通过”。
