# 数据模型

## Project
id UUID；name 去空白、必填、最多 100 字；description 最多 2000 字；archived boolean 默认 false；createdAt/updatedAt 时间。

## Task 扩展
projectId 可空，引用 project；parentTaskId 可空，引用 task 且删除 RESTRICT；仅允许同项目根父任务；父任务有子任务时不可移动项目、不可改成子任务/周期任务。
recurrenceType 为 NONE/DAILY/WEEKDAYS/WEEKLY/MONTHLY，默认 NONE；recurrenceTimezone 为有效 IANA 时区，默认 UTC；recurrenceAnchorDay 可空或 1..31；recurrenceGenerated 默认 false，表示本期已生成下一期，任何公开更新不允许清除此标记。
周期任务必须有 dueAt，且不能有父任务或子任务。完成时必须所有子任务 DONE。

## 原子状态迁移
TODO/DOING→DONE 且规则非 NONE、recurrenceGenerated=false：在同一事务设标记，生成 TODO 下一期并复制标签。DONE→非 DONE 不撤销下一期；再次 DONE 不重复生成。首次 INSERT DONE（备份还原）不生成。
旧备份缺失新字段时采用默认值；恢复先插入项目与全部任务，再回填父任务引用，避免数组顺序依赖。
