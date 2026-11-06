# Validation guide
1. 全新临时库回放 V1..最新迁移，validate，再次回放应零新增；不连接运行库。
2. ICS预览/导入/重复导入/导出，复杂规则整批拒绝；全天和DST往返，重叠/相邻冲突边界。
3. 本地注入HTTP transport测试公开目标与正文，私网/混合DNS/重定向/大响应必须拒绝；来源更新成功才推进正文版本，失败保留旧版。
4. 定时报告手动运行、到期多执行器、禁用/重试/每日额度/夏令时；核对chat_run与业务表无副作用。
5. 维护页面上传坏JSON/重复ID/悬空引用，核对仅记录校验；Worker离线显示失联；外部演练报告准确标记。
6. 真实完整备份演练使用既有 `python3 scripts/dev/backup.py --help` 所列verify/drill命令及隔离数据库；中心不自动执行。
7. 执行双端测试与前端完整检查，实际结果写validation.md；每功能验证后提交。

## 可复现的隔离检查
- 先完成Java `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home ./backend/mvnw -f backend/pom.xml -B --no-transfer-progress verify` 和前端 `npm --prefix frontend run build`。
- 从仓库根目录运行 `python3 scripts/automation/run_checks.py --ui`。脚本自行创建无持久卷、随机本地端口的pgvector临时容器，回放/校验49个迁移，执行Go真实库race测试，再分别启动Java和Go临时服务验证相同前端。仅清理本次创建的容器与进程；需要本地Docker、JDK21、Go、Node和Playwright。
- 截图及浏览器结果位于 `.cache/automation-ui/{java,go}/`。浏览器禁止访问测试预览源以外的网络；网页索引测试使用注入的固定抓取/嵌入样本，不需要真实模型。
- Go临时服务显式使用 `--foundation --p8` 加载新增功能；该检查不改变033发布门禁，也不表示允许替换现有Java服务。

## 界面入口与运行边界
- 日历页：ICS预览/确认/导出和冲突；知识库页：网页采集、检查更新和正文版本；侧栏「定时报告」「维护与恢复」提供新页面。
- 报告计划默认禁用；仅combined/review角色调度，可用 `app.workflows.enabled=false` 停止执行器。额度按每天的每次尝试计数（包括失败），每期最多3次；固定报告零模型/外部工具调用，仍受数据项和字符数限制。
- 维护心跳默认启用，各角色独立续写；可用 `app.maintenance.heartbeat-enabled=false` 关闭。界面刷新展示采样时间，在线不代表所有业务成功。
- 业务JSON备份包含项目/任务/日程/会话/记忆/知识分块，不包括附件、运行配置、队列、报告计划、网页正文历史。静态校验通过不等于能完整恢复；知识分块导入仍需要可用的嵌入模型。
- 页面展示的backup/verify/drill命令不自动执行。外部填报的演练结果始终标记为「未独立验证」，完整备份包仍按既有CLI流程在随机独立库演练；本次没有执行真实完整备份恢复。

## 复审回归
- `python3 scripts/automation/run_checks.py --upgrade-from 47` 在全新临时库先应用V1..V47，再升级到V49并执行双端共用规则的Go集成测试；`python3 scripts/automation/run_checks.py --ui` 验证全新49迁移及Java/Go真实HTTP浏览器流程。脚本只操作自己创建的临时资源。
- 冲突日期回归：`TZ=Asia/Shanghai npm --prefix frontend run test:component -- test/component/calendar-exchange.test.ts`；将TZ分别换成UTC、America/New_York复跑。覆盖凌晨、跨年和七天本地窗口。
- 正常升级时由现有迁移流程应用V48/V49；本次不部署或自动修正历史报告、已采集正文与既有重复数据，无法可靠还原的来源路径不做猜测合并。
