# 2026-11-06 项目优化与增量完整性修复

用户要求先检查项目，再逐项优化。接手提交为 `2d78347`，本轮修复源码提交为
`f52f87f`；`scope-changes.json` 记录完整不可变提交与逐文件 SHA-256。

## 修复范围

1. Go 任务完成/重开、任务与项目幂等写入、确认/取消和数据导入在执行副作用前检查
   `Accept`。新增回归先在原实现失败，再验证406不写仓储、不进入确认服务、不占幂等路径，
   导入也不读取请求体。原复现的任务完成请求为406但仓储写入一次。
2. 补登上次台账后已有的034工作台、035安全自动化、对话恢复/RAG/MCP及公共入口候选。
   原五组共享文件推进到当前源码锚点，新候选按功能及本轮优化单独登记。
   状态全部保持 `in_progress`；登记与候选完整性不等于发布批准。
3. 工作台与公共 `testdb` 读取 `GO_TEST_POSTGRES_SSL_MODE` 和
   `GO_TEST_POSTGRES_ROOT_CERT`，移除写死的明文模式。真实TLS临时库拒绝明文连接，
   原失败的 `TestWorkspaceHierarchy` 与同包回归已通过。
4. 两个功能验证脚本共用JDK发现：显式 `JAVA_HOME` 优先，其次 `PATH`，缺少编译器报错。
   增加JDK路径含空格、PATH回退及缺失编译器测试。Go候选CI新增034/035和验证脚本路径触发，
   执行日程互通、采集、工作流、维护和搜索的TLS数据库测试及V42升级检查。
5. Java知识正文搜索改为从真实文档查询，SQL排除已有命中并按剩余名额 `LIMIT`，
   然后一次 `findAllById` 批量加载并恢复候选顺序。Go同步排除已命中ID并按剩余名额截断。
   覆盖重复分块、坏ID、孤立分块、空名额以及 `%`、`_`、反斜杠字面匹配。
   未添加索引或依赖，未做生产规模延迟基准，不声称已测得速度提升比例。

## 冻结范围和追加迁移

`inventory.json`、`inventory.md`、`surface.md` 仍固定于
`cb5d694d62fa93742b5789b97273e76d931ce335`，V1–V42原始SQL未修改。
台账校验器只允许登记高于冻结最高版本的标准追加迁移，拒绝原脚本覆盖、旧版本换名、
重复版本、非标准或嵌套路径；追加文件仍需源码提交与当前文件双重哈希校验。
V43–V49均为接手前已存在的迁移，本轮仅登记，没有修改任何SQL。
P8职责测试同步区分未改动的历史verified文件和已登记的待验收候选，不允许候选继承旧通过状态。

## 实际验证

所有数据库验证只使用脚本/Testcontainers新建的临时库，并清理本次资源；不读取应用.env，
不操作既有数据库、运行服务或真实模型凭据。未推送远端，远端GitHub Actions尚未实际运行。

| 检查 | 实际结果 |
| --- | --- |
| Java `APP_DOTENV_ENABLED=false ./mvnw -B --no-transfer-progress -Dapp.dotenv.enabled=false verify` | 1221项测试，零失败、零错误、零跳过；JaCoCo覆盖率门禁通过，BUILD SUCCESS |
| Java定向 `KnowledgeSearchContributorTest,GlobalSearchIntegrationTest` | 通过；真实PostgreSQL验证正文候选及通配符，单元测试验证一次批量加载且不逐条findById |
| Go `go test -race -p 2 -count=1 -timeout=10m ./...`、`go vet -tags=integration ./...`、`go build ./...` | 全部通过 |
| 前端lint、format:check、type-check、test:logic、test:component、build | 六项全部通过；未修改前端源码 |
| `run_go_postgres_tests.py --tls --package task --package confirmation` | 两个完整集成包通过；包含工作台层级、并发周期生成、导入导出及确认CAS |
| `run_go_postgres_tests.py --tls --package calendar --package knowledge --package workflow --package maintenance --package search --run '^Test(CalendarExchange\|CaptureAtomicVersions\|ReadOnlyWorkflows\|MaintenanceCenter\|KnowledgeBody)'` | 五个包选定测试全部执行并通过；TLS强制拒绝明文 |
| `run_go_postgres_tests.py --tls --package app --run '^TestP8'` | 通过 |
| `python3 scripts/automation/run_checks.py --upgrade-from 42` | V42→V49升级、validate、再次零迁移与四模块Go集成回归通过 |
| `python3 scripts/workspace/run_checks.py` | 完整迁移及工作台集成回归通过 |
| `python3 -m unittest discover -s scripts/workspace -p 'test_*.py'` | 3项通过 |
| `PYTHONPATH=scripts/migration python3 -m unittest test_go_scope_changes` | 15项通过，包含追加版本保护与原SQL不可改 |

审查期间原Python迁移套件181项有3个失败、4个错误；增加4项守卫测试后、台账修复前185项仍以
`Unreviewed candidate file drift: backend-go/internal/business/dataimport/import.go` 失败。
这是真实完整性失败，未通过刷新冻结清单、禁用测试或提高状态绕过。台账修复后的最终检查结果在下方追加。

此前审查的Java全量test曾因PostgreSQL SSL握手读超时导致TaskTags上下文11项错误，定向重试通过；
本轮全量verify在保留原测试与覆盖率规则的情况下1221项一次通过，没有将定向重试冒充全量通过。

临时详细日志位于本机 `/tmp/personalos-fix-*.log`，属于运行记录，未作为永久仓库证据或生产验证。

## 台账修复后最终结果

- `python3 -m unittest discover -s scripts/migration -p 'test_*.py'`：185项，全部通过。
- Node迁移诊断/模型传输用例：9项，全部通过；JDK发现测试3项再次通过。
- `go_inventory.py check`：1093原始文件、104路由、27工具、95运行钩子、1119测试声明通过；
  9组355个候选文件绑定不可变源码，9组均未标verified。
- `go_config_disposition.py`：78项通过，无遗漏/未知动态键。
- `go_p8_test_progress.py`、`go_p8_inventory_progress.py`：重新生成后检查通过；候选文件的历史完成状态已取消继承。
- `go_inventory.py check-complete`：按预期拒绝宣布迁移完成，仍报告9组增量、原未验收项和五项开放发布门禁。
  此命令的拒绝是防止错误发布的验证结果，不是常规完整性检查失败。
- 用接手前快照逐项对比，三份冻结清单SHA-256及五项发布门禁内容未变；原42个SQL仍与冻结哈希一致，
  本轮相对接手提交未修改任何SQL。`git diff --check`通过。
