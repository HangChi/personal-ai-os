# 2026-11-07 Java / Go 五维质量门禁验证

本轮将[测试质量要求](../testing.md)落实为 Maven 与 CI 自动检查，并重新执行完整测试。Java 行/分支与 Go 完整集合语句覆盖率均超过 95%；Go 分支覆盖率仍未测量。没有改动业务源码、缩小统计范围或新增覆盖率排除项。

## 被测快照与证据

- 日期：2026-11-07，Asia/Shanghai。五阶段最终运行时间为 22:58–23:06；汇总随后通过。
- 被测修订：e046c35f4a350f5fbfab695587b4a23a0b685814。
- 源码指纹：cc9025ed929e40dc31674e4bbd21a2f92688ebf58968d800618833665ac4757d。每个阶段记录相同的 Git 修订和源码 SHA-256。
- 原始证据位于忽略提交的 .cache/quality-gates-final-20261107/，包含五个 stage.json、日志、JUnit、JaCoCo XML、31 份 profile、summary.json 与 gate-result.log。
- 较早开发验证目录 .cache/quality-gates-20261107/ 在统计器调整后被源码一致性检查拒绝，未混入最终数据。最终五阶段全部重新成功执行。
- Java / Go 测试与门禁实现分别提交。测试采用合成数据、离线模型替身和本次新建的设施，不读取真实 .env 或调用收费模型。

## 五项验收

| 维度 | Java | Go | 结果 |
| --- | --- | --- | --- |
| 代码执行覆盖 | 行 10,920 / 11,454，95.3379% | 完整集合语句 19,406 / 20,409，95.0855% | 各自 ≥95% |
| 分支覆盖 | 6,428 / 6,760，95.0888% | not_measured | Java ≥95%；Go 明确未测量 |
| 变更代码覆盖 | 不适用：无业务可执行行变化 | 不适用：无业务语句变化 | 已配置未来变更 ≥95% 门禁，不填成 100% |
| 关键业务场景 | 对应测试实际通过 | 在指定阶段实际通过 | 13 / 13 通过 |
| 双版本行为契约 | 消费相同输入与预期 | 消费相同输入与预期 | 182 个共享用例通过 |

变更比较基准为 1ed523a03ff54db39221775d9e1243ab1572b200。本轮新增测试、配置和统计器，不以修改业务代码提高覆盖率。变更门禁的负向测试验证低于阈值、遗漏源码文件和异常输入不能通过。

Go **默认全量**语句覆盖率独立为 18,168 / 20,380，**89.1462%**。95.0855% 只代表默认 + 全部隔离集成 + 真实进程集合；集成标签额外纳入 29 条支持语句，没有剔除。与此前默认集合的细小波动不解释为业务质量变化；两轮完整集合也不直接与只跑默认测试的基线比较。

## 实际执行

- Java：247 个测试类、1,755 项测试，0 失败、0 错误、0 跳过；Maven verify、行/分支 95% 原生门禁通过，JaCoCo 只有 1 个新 session。
- Go 默认：35 个包、6,770 项测试/子测试通过，0 失败；TestP7StdioHelper 为辅助子进程入口，默认跳过 1 项，不属于必选场景。
- Go：全量 race、vet（含 integration 标签）和 build 通过。
- PostgreSQL/TLS：全部 18 个包，含备份恢复、baseline、真实进程 smoke，通过。
- Redis/TLS：全部 7 个包，含 PostgreSQL 组合及真实进程 smoke，通过。
- P6：RabbitMQ / OpenSearch / TLS 组合三个包通过，临时设施已清理。
- 覆盖率与证据校验器：25 项回归测试通过；迁移 Python 脚本：185 项通过。
- 工作流 YAML、脚本 shell 语法与格式检查通过；文档、路由和迁移派生记录另按最终文档提交再次检查。

## 门禁实现

[Backend Quality 工作流](../../.github/workflows/coverage.yml)运行 Java、Go 默认、PostgreSQL、Redis、P6 五阶段，再调用[运行与汇总器](../../scripts/coverage/run_suite.py)。缺阶段、profile 不完整、源码不同、证据 SHA-256 改变、指标不达标或必选场景未实际通过都会失败。

[变更检查器](../../scripts/coverage/changed.py)按 PR merge-base 统计 Java 可执行行与 Go 差异触及 block 的语句，不把 Go 语句转换成分支。[场景清单](../../scripts/coverage/critical-scenarios.json)固定指定阶段，条件跳过不能替代该阶段的实际通过。新增共享预算与切块用例分别由两版生产实现执行，见[夹具说明](../../testdata/README.md)。

门禁回归包括 94.9999% 不得四舍五入过关、Java 分支达标不能掩盖行未达标、缺 profile、证据篡改、不同源码合并、重复 block 分母、错误阶段通过及 JUnit 参数化跳过等负向场景。

## 复现与边界

~~~bash
python3 -m unittest discover -s scripts/coverage -v
python3 scripts/coverage/run_suite.py --stage all --output .cache/quality-fresh --base 1ed523a
~~~

需要 Java 21、Go、Docker、Poppler、依赖缓存及 P6 镜像；macOS 设置 JAVA_HOME，隔离 Go runner 可指定 GO_TEST_GOPATH 和 GO_TEST_GOCACHE。每轮使用新目录，不能用旧证据替新修订背书。

本报告记录本地运行，未宣称 GitHub 已执行；工作流需推送后运行，仓库分支保护还需将新 quality-gate 设为必需检查。真实模型质量、外部服务 SLA、性能和迁移生产发布不在本轮验收范围。固定迁移基线和 release gates 不升级；新增/变更来源登记保持 in_progress，不继承历史通过结论。
