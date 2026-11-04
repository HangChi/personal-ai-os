# P8 全量验收与运维进度

2026-11-04：P8实施中，T800–T808父项尚未收口。固定Java基线继续为`cb5d694d62fa93742b5789b97273e76d931ce335`。五个发布门禁保持not_started，所有清单条目不自动升级verified。现有Java部署与SQL保留；没有自动推送、合并、生产部署或删除Java目录。

## 实施顺序

1. 补齐默认关闭的数据保留策略及生命周期，避免部署遗漏实际运行钩子。
2. 独立Go镜像/Compose/Caddy、五角色/管理端口、非root及显式凭据，先在隔离环境验证。
3. 本地启动停止、备份恢复、Push密钥和监控告警，复用基线资产并记录差异。
4. 全量前端检查与现有E2E接Go；逐页、通知和上传下载在真实浏览器验证。
5. 汇总并审查154类/1119测试声明、全部来源文件和接口证据，逐项补缺。
6. 真实设施全量/故障/恢复、性能与隔离切换演练；证据通过后才核验发布门禁。
7. 更新Go运维/开发说明，保留迁移历史；Java归档须用户验收后另作决定。

## T801a 数据保留策略

`--foundation --p8` 包含P7及全部前序业务，新增用量/操作记录清理；默认保持回环监听，不解除prod拒绝或readyz503。`app.retention.token-usage-days`与`app.retention.operation-record-days`默认0（永久保留），负值/超过Java int的值启动拒绝。两类截止时间独立，按UTC瞬时减整天，严格早于截止时间才删除；不能用Go time.Duration乘天数导致大整数溢出。

`app.retention.cron` 默认本地04:30，支持原P4已验收的六字段数字/名称/范围/列表/步长/?、标准宏及`-`。L/W/#扩展在连接前明确拒绝，不静默改成默认。与附件GC复用同一解析器，原附件测试重新race通过。串行循环不重叠，同秒去重，每轮30秒限制；停止先取消并等待清理后才关池。

安全差异（R-053）：原Java仓储按时间删除全部操作记录，会连同结果未明的TOOL占位删除。Go依据FR-006及R-043保留`source=TOOL AND outcome=IN_PROGRESS`，避免自动解除幂等保护并重放未知外部副作用。完成的旧审计仍按显式保留期删除，截止边界记录保留；需要人工核查未知结果，不能用清理代替决定。

原Java声明映射：`DataRetentionServiceTest.permanentRetentionNeverDeletes`、`enabledPoliciesUseIndependentCutoffs`、`eachPolicyCanBeEnabledIndependently`、`rejectsNegativeDays` → `TestP8RetentionPermanentAndIndependentCutoffs`、`TestP8RetentionBinding`；仓储和调度补充为`TestP8RetentionCutoffsAndUncertainTools`、`TestP8RetentionFoundationValidationAndShutdown`、`TestP8RetentionCronDisableCancellationAndDeduplication`。

实际验证：

- `go test -race -count=1 ./internal/platform/maintenance ./internal/platform/config ./internal/business/chat`：通过。
- `go test -race -count=1 ./internal/platform/maintenance ./internal/platform/config ./cmd/personalos ./internal/app -run '^TestP8'`、integration vet/build：通过；无integration标签的app未运行数据库测试，另列下项。
- `python3 scripts/migration/run_go_postgres_tests.py --package app --tls --run '^TestP8'`：2个真实TLS PostgreSQL/pgvector测试全部通过race（10.743s）；原42迁移真实Flyway竞争、validate/二次零执行通过，临时容器已清理。
- 真实库覆盖永久保留无写、两类独立截止、精确边界、第二次清理零删除、未知TOOL保留；真实Foundation开启每秒清理、负值/非法cron拒绝、正常/重复关闭通过。
- P8 CLI包含全部前序模块；容器网络仅在显式`--container --foundation --p8`且认证/CSRF同时开启时允许通配监听，其他入口继续回环。`TestP8ContainerNetworkIsExplicitAndAuthenticated`和`TestP8ContainerListenerBoundary`通过race；这只是容器监听边界，镜像/五角色实际运行另行验收。
- 24项Python迁移脚本测试、78项配置台账和1093文件固定基线check通过。两处保留期配置已wired，7项保留策略来源/调度/声明记implemented；其他项目与五门禁不变。

该子项不代表T801的全部备份/恢复/监控职责完成，也不代表P8发布验收完成。

## 首批提交门禁

2026-11-04 Java全量离线verify通过：1137测试、零失败/错误/跳过，JaCoCo原覆盖率门禁通过，BUILD SUCCESS（27:49）。命令另加`-Dapp.dotenv.enabled=false -Dspring.test.context.cache.maxSize=2`，不读取现有.env。日志`/tmp/personalos-p8-java-verify.log`。

前端lint/format/type-check、1151逻辑测试通过；首轮组件并发运行两项超时，保留失败记录，串行复验30文件/195测试全部通过（145.88s），随后生产构建通过（6.60s）。未改前端源码或放宽测试期限。日志`/tmp/personalos-p8-frontend-components-serial.log`、`/tmp/personalos-p8-frontend-build.log`。最后源码的integration vet/build也通过。
