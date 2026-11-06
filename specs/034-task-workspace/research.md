# 技术决策

## 共享事务不变量
Decision: 使用 PostgreSQL BEFORE/AFTER task 触发器完成层级校验与下一期生成；前端只传用户意图。
Rationale: 同库两套后端及 Agent 完成入口共存；行锁和单事务保证重复/并发完成只生成一次。
Alternatives considered: 两套应用分别生成（易漂移）；定时预生成（扩大范围）；仅前端生成（不安全且不原子）。

## 周期语义
Decision: 基于上期 dueAt，按 recurrenceTimezone 的自然日/周/月推进，月末用 anchorDay 截断；完成一期开启下一期。不跳过逾期期次。重新打开不撤销已创建的下期。
Rationale: 行为可预测、无隐式批量写入；保留每期完成历史。
Alternatives considered: 按完成时刻计算（漂移）；跳过所有遗漏期（掩盖未做事项）。

## 今日加载
Decision: 客户端独立并发加载服务端筛选分页任务、日程、确认及最近未结束执行；失败独立展示；按本地自然日计算边界。
Rationale: 复用已有 API，避免聚合服务单点失败。
Alternatives considered: 全量加载任务（不可扩展）；共享任务页 store（筛选污染）。

## 范围与依赖
Decision: 一级子任务、项目归档恢复，无物理项目删除、无限层级或自动模型晨报；无新增运行时依赖。
Rationale: 满足当前明确需求且可独立交付；保留后续扩展空间。
