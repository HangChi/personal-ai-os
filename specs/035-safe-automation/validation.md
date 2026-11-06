# 035 验证记录

## 日历互通（2026-11-06，V44）
- Java：`JAVA_HOME=.../jdk-21... ./mvnw -B --no-transfer-progress verify` 全量通过（首次测试发现JPA未flush导致来源外键失败，修复后全量重跑通过）。
- Go：calendar/migration `go test -race` 通过；独立临时库 `TestCalendarExchange` 通过，覆盖摘要、预览零写入、并发去重、导出再导入、跨窗口与相邻冲突。
- 前端：types/lint/format/1174逻辑断言/263组件测试/build 通过。
- `python3 scripts/automation/run_checks.py --ui`：全新44迁移、validate、幂等迁移、Go DB测试、Java临时服务真实浏览器通过；390px无横向溢出。检查了截图。
- 明确边界：文件上限1MiB/200事件；复杂重复、月末29..31日重复及未来存在夏令时变化的应用时区重复明确拒绝，不静默改义。不含账户同步/邀请或提醒组件。
- 仅使用本次创建的无卷临时数据库和临时服务进程；均已清理。未读取.env、未重启/部署用户服务、未调用真实模型。
