# Go 全量迁移

分支：`go`。Java 基线：`cb5d694d62fa93742b5789b97273e76d931ce335`。建立日期：2026-11-03。

目标是用 Go 接替当前 Java 后端，复用 Vue 前端，保留全部已有业务、协议、数据与运行能力。
“当前 Java”指上述提交中的实现，不仅是 README 阶段表或早期接口文档。

## 当前交付状态

- P0：规格、架构方案、逐文件基线清单、接口/工具/调度索引、实施顺序和验收要求已建立。
- P1已完成：显式Foundation/真实二进制已接数据库、认证/CSRF、共享限流/模型租约、健康、管理双端口、执行器、上传生命周期、HTTP观测与ECS日志。五角色/跨OS共享、真实TLS/最小ACL、网络故障恢复及提交响应丢失验证通过；844个基础HTTP场景、160个观测标签场景和68个直方图边界对照真实框架。父项验收状态见[P1收口矩阵](p1-remaining.md)和[tasks.md](tasks.md)。
- 全42原SQL、真实Flyway历史/锁竞争、七类DDL差分和合成pg_dump/restore数据/序列已验证；完整schema的P1最小权限及恢复库五角色Foundation实际启动/关闭通过。扩展schema/历史明确拒绝，支持边界见[P1契约](p1-foundation-contract.md)；合成恢复不等于真实用户备份验收。
- **Go 尚不能替代 Java。104 个注解 HTTP 路由和所有业务模块均未宣告迁移完成。**
- Go默认离线诊断不读现有`.env`、不连数据库、不迁移或启业务Worker，`/readyz`固定503。显式GO_DOTENV_ENABLED才允许加载配置；显式基础模式要求独立连接参数/迁移目录，允许已实现的认证/CSRF，生产继续拒绝。
- 不修改现有 Java、前端、SQL、Compose 或本地运行实例。两个后端必须使用独立测试数据验证。

基础代码的可执行验证结果见 [validation.md](validation.md)。实施状态见 [tasks.md](tasks.md)；逐项证据见 [progress.json](progress.json)。

## 文档导航

文档 | 用途
--- | ---
[spec.md](spec.md) | 全量兼容目标、需求与完成定义
[plan.md](plan.md) | Go 模块边界、阶段依赖和与项目宪法的技术迁移说明
[research.md](research.md) | 已确定的技术决策及尚未引入的依赖
[feature-parity.md](feature-parity.md) | 按能力划分的迁移范围、易遗漏行为和 Java 证据
[contracts.md](contracts.md) | HTTP、SSE、模型、MCP、数据与运行协议验收要求
[tasks.md](tasks.md) | 按依赖串行推进的任务与验收门禁
[inventory.md](inventory.md) | 全部 1,093 个基线文件、SHA-256、阶段与目标目录
[inventory.json](inventory.json) | 完整哈希、Java 声明/注解、接口签名、测试声明、环境变量引用
[surface.md](surface.md) | 104 个注解路由、27 个工具、95 个运行钩子的自动索引
[progress.json](progress.json) | 独立于生成清单维护的状态和验证证据
[validation.md](validation.md) | 本次实际运行的验证与未验证事项
[p1-foundation-contract.md](p1-foundation-contract.md) | P1实际支持、明确拒绝及后续业务/发布边界
[config-disposition.md](config-disposition.md) | 78处直接配置调用方逐项装配或后续阶段归属

## 防遗漏的执行规则

1. 以固定提交为真相源。1,093 个受 Git 管理的文件全部入册，其中 Java 主源码 391、测试文件 154、SQL 迁移 42。
2. 自动索引实际提取到 104 个注解 HTTP 路由、23 个内部 Agent 工具、4 个对外 MCP 工具、13 个 `@Scheduled` 声明。
3. 1,119 个测试方法声明逐项保留来源；参数化用例展开后执行数可能更多，不能用此数字替代测试报告。
4. 每个清单项默认 `not_started`。`in_progress` 表示已着手；`implemented` 必须有代码证据；`verified` 必须有行为验收证据。
5. 迁移按行为覆盖，可以合并多个 Java 类为一个 Go 包；不能因为语言不同而丢掉 Java 类承载的职责。
6. 不以“前端能打开”“编译成功”“路由全部返回 200”作为全量完成依据。
7. 新增/修改任何基线文件会使检查失败。合并新的 Java 功能时，显式更新基线、审查差异并迁移进度；不得直接重生成后声称差异已覆盖。
8. 清单只证明来源可追溯，不能单独证明逻辑等价。切换前必须完成差分、集成、恢复、前端与运维验收。

```bash
# 在仓库根目录执行：检查源码漂移、派生文档与进度证据
python3 scripts/migration/go_inventory.py check

# 检查提取器边界
python3 -m unittest discover -s scripts/migration -p 'test_*.py' -v

# 只有全量迁移、验证并补齐证据后才能成功；当前应失败
python3 scripts/migration/go_inventory.py check-complete
```

重新生成同一基线清单（保留 `progress.json`）：

```bash
python3 scripts/migration/go_inventory.py generate \
  --baseline cb5d694d62fa93742b5789b97273e76d931ce335
```

## 继续实施的入口

P1父项验收后，从[tasks.md的P2](tasks.md#p2-幂等确认与任务)开始幂等、确认、任务与标签。本次收口不提前实现或勾选P2。每阶段保留对应Java来源、Go用例、差分结果和进度证据。
