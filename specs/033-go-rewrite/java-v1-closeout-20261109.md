# Java 第一版本地收尾验证 · 2026-11-09

## 交付范围与结论

本轮冻结新增功能，交付范围为 Java 主后端与共用前端。三个审查问题已修复：Java 分支覆盖率、前端格式、Go 候选清单漂移。完整五阶段工程门禁通过；当前数据备份已在独立容器恢复，并由本轮已验证的 Java JAR 实际启动读取。

这是本地发布候选的收尾验证；没有部署、推送、合并、打发布标签或删除 Java。Go 生产替换不属于此次结项，原五项 release_gates 保持未批准。**原历史 E2E 仍有 1 项预算断言失败，不能将本文解读为所有历史 E2E 全绿。**

机器结果见 [脱敏汇总](java-v1-closeout-20261109.metrics.json)。原始测试、截图、失败及私有恢复证据保留在本机 `.cache/java-v1-closeout-20261109/`；真实备份与配置不提交。

## 源码与产物

- `0f7d5fa`：补 Markdown 围栏/缩进/公式/表格/引用/列表/行内代码边界与原文范围测试、PDF 页数和渲染尺寸限制；修复 KnowledgeDocumentsTab.vue 的 Prettier 格式；隔离 E2E 输出目录并允许指定 Java 发布物。
- `55ae5b527003477ed0dabe735abd1fd718fe9378`：逐组登记后续摄入、切块、改写配置、评测及回归增量，保留冻结 oracle 与旧验收状态。
- 本轮所有五阶段证据绑定后一提交与同一源码指纹 `9c00f1ecad3481d263b1b8fdadef04f9a7f349b58c6920a4ee743c72dc62096a`。
- 已验证 JAR：`.cache/java-v1-closeout-20261109/release/backend.jar`；SHA-256 `b882f36ca5a93fdac82fdf1a545725e85ebf761726afcdfa50b98ae9f0ae3c9a`。从完整 verify 产物复制并设为只读，后续黑盒与恢复均使用此文件。

## 工程门禁

| 检查 | 本轮结果 |
| --- | --- |
| Java 全量 verify | 1805 项，0 失败、0 错误、0 跳过；1 个新 JaCoCo session；打包通过 |
| Java 行覆盖 | 11169/11703 = 95.4371% |
| Java 分支覆盖 | 6695/7040 = 95.0994%，原 95% 门槛保持不变 |
| Go 默认 | 全包 race、integration 标签 vet、build 通过；默认语句 18963/20630 = 91.9195% |
| Go 完整设施集合 | 31 份新 profile；19715/20659 = 95.4306%，完整集合 95% 门槛通过 |
| Go 分支 | not_measured；不以语句覆盖冒充 |
| 关键场景 | 13/13 双端执行证据通过，含共享 Agent/RAG 契约 |
| 变更覆盖 | 相对接手提交 9211be9，无生产可执行单元改动，Java/Go 均 not_applicable；没有填成 100% |
| 前端 | lint 0 错误/5 个既有测试 warning；format/type-check、1174 逻辑测试、47 文件/305 组件测试、build 全通过 |
| 校验工具 | coverage 25、eval 28、migration 186 项回归通过 |
| 文档与路由 | 67 份 Markdown/740 个本地链接与锚点、工具/冻结迁移来源、128 个当前 Controller 路由通过 |

执行环境：Java 21.0.12、Go 1.27.1、Node 26.8.2、Docker 29.9.2。工程测试仅使用新建临时设施，没有把现有业务库当作测试夹具。

## 候选清单修复

逐组审查 11 个既有候选组的实际变化，新增 4 组登记后续产品/评测/回归文件；普通检查现在通过：1093 个固定基线文件、104 路由、27 工具、95 运行钩子、1119 测试声明，以及 19 组/647 份候选文件。

详见 [逐组审查](closeout-scope-review-20261109.md)。仅更新候选源码锚点、双端职责及证据，不修改冻结 inventory/oracle/V1–V42，不将候选或发布门禁升级为 verified。P8 派生文件重新生成后比较通过；check-complete 仍按预期拒绝未完成的 Go 发布。

## 当前发布物黑盒验收

使用新 TLS PostgreSQL、真实 Flyway V1–V49、认证/CSRF 和本机合成模型，未调用收费模型：

- 原 84 项套件：81 PASS、1 FAIL、1 SKIP、1 NOTE、0 ERROR。原问题、预算、断言与观察模式均保留；完整结果在汇总 JSON 的 legacy_e2e。
- 独立取消验证：打开真实部分 SSE，关闭客户端后上游连接停止，落库符合取消策略。
- 独立计划验证：真实任务查询、三步骤状态/完成时间、优先级统计和最终回答落库通过。
- 显式记忆补验：普通对话零自动写，重复 saveMemory 两次仍为一条记忆且具备两次成功审计；不将原 SKIP 反写为 PASS。
- 持久 Run 补验：同一 Run 停止、继续到 COMPLETED；事件 ID 唯一递增；原始用户请求仅一条；同键重放不增加任何消息。继续操作追加其自己的用户续接消息是现有协议的一部分。
- 外部 MCP：实际安装的 filesystem 2.2.0 在全新 sandbox 内执行 5 次操作；同身份不重复执行、未知结果不重发、人工确认前不写、重复确认与重启后重试保持保护。真实认证浏览器快捷指令流程通过。

### 预算失败的边界

原 G4 将预算设置为 2048（生效下限 6729），再提交重复 120 次的当前用户长消息，并要求三轮都完成。当前守卫在不可裁剪的当前消息与固定开销超预算时返回 41301；它不能为了满足旧断言而截断用户本轮问题。

独立补验保留同一长输入，确认返回 41301、模型调用数为 0，并保存明确的中断回执；同一下限内的短消息连续三轮正常 done。原 G4 的 FAIL 保留，不用补验回填原结果。

原 G5 的工具超限 NOTE 记录终帧为 error，而旧观察文字期望 done；当前 ChatCompletionService 的协议明确失败发送 error。原记录保留。此处没有改变模型预算、工具上限、错误收尾或原 E2E 判据来追求全绿。

## 真实数据备份与恢复

1. 在现有实例导出 **只读、可重复读事务快照**，将同一导出 snapshot 显式传给 pg_dump；不停止或写入原服务。
2. 对快照内全部 49 张 public 表记录行数与逐行排序后的 SHA-256 聚合指纹。实际备份快照共 37119 行，含 49 条 Flyway 历史与 2050 篇知识文档。
3. 备份前后比较本地/S3 附件快照，并核对归档内附件哈希；本次真实附件为 0，不能据此声称验证了非空真实附件恢复。
4. 在新建、仅随机回环端口、无持久卷的容器中 pg_restore，全部表的行数和指纹与原导出 snapshot 精确相同。
5. 从空环境启动上述只读 JAR，连接恢复库，完成认证并读取知识列表和任务分页；知识总数 2050 与原 snapshot 一致。模型配置使用本地不可连接的合成端点，后台模型、MCP、索引和提醒执行器关闭，不对恢复出的资料产生外部动作。
6. 测试应用停止，临时恢复容器删除；已验证归档另存于 `backups/java-v1-20261109-data/`，SHA-256 为 `8ff60a0082239b1d444e9f8ce307b64e4eaab5cc85fdb0f3a7d58972247f5f15`。
7. 所需本地环境配置另存于 `backups/java-v1-20261109-config/`，与数据归档分开；目录 0700、文件 0600。没有把真实配置、密钥、正文或数据库包加入 Git。

非空附件由合成浏览器上传/内容逐字下载及外部工具场景另行覆盖。此验证不撤销外部通知/工具副作用，也不代表公网 TLS 或 Go 切换验收。

## 失败与重跑记录

- 第一轮后端回归早于候选登记提交完成，被主动停止；保留 quality/ 下中断日志。quality-final/ 是冻结提交后的完整新一轮，不复用旧执行文件或 profile。
- 恢复夹具最初关闭 EmbeddingModel，导致 PgVector 自动装配缺 bean；改用不可连接的合成嵌入端点后启动成功。随后修正把 `/knowledge/documents` 列表当分页对象的夹具错误；改用真实 `/documents/search` 协议。
- 临时 PostgreSQL 的 Unix socket 曾在 initdb 临时服务期报告 ready，正式目标数据库尚未建立。最终用 TCP readiness 等待正式服务后恢复成功；未修改产品、SQL、恢复数据或 pg_restore 标准。
- Run 补验最初错误地要求“继续后只有一条 USER”，已按源码协议同时检查原请求唯一、续接存在、同键重放不新增消息。预算失败路径确实保存带中断标记的助手回执，补验另核对零模型调用，未将它当生成的事实答案。
- 浏览器夹具按当前弹窗、隐藏替换文件选择器、设置分组可访问名称修正；先前运行的失败、429 与会话初始化等待记录均保留。最终整轮结果见下节。

## 浏览器结果

最终整轮通过：真实认证/CSRF、任务创建/编辑/完成/显式删除、持久对话、附件上传/依据附件作答/逐字下载、后台知识索引与文档列表、业务备份下载。1280、768、375 三种宽度各检查10个页面，共30张视口截图，均无横向溢出、页面运行错误或HTTP错误。另有任务、对话附件、知识入库截图；截图保存在本机 `.cache/java-v1-closeout-20261109/browser-screenshots/`。人工抽查桌面任务完成和375px工作台截图，控件与文字可读，未见裁切。

会话新建通过实际POST响应取得ID，再打开该会话后输入，避免把异步切换中的草稿当作已就绪会话；按真实输入事件逐字键入。此结果只对应该最终夹具与上述发布物，不覆盖原失败运行。

## 保留的范围与下一步

- 已完成本轮三个修复和本地候选工程验证；以 Java 主后端为第一版维护基线。
- 旧 G4 FAIL、G5 NOTE、G7 SKIP 与补验分别报告，不能宣称所有历史 E2E 全绿。
- RAG 事实质量、保守澄清/拒答及延迟仍沿用 [2050 文档报告](../../docs/reports/knowledge-adversarial-2026-11-08.md)的实验边界；本轮没有通过收费真实模型重测或推断开关已改善质量。
- Go 五个发布门禁、真实 Push 稳定送达、生产 TLS/容量/切换批准继续开放；不得用本报告替代 Go 发布验收。
- 本轮没有核实远端 Actions、分支保护或发布到运行实例。上线时使用上述已验产物，保留完整备份及独立配置，并按现有运维流程执行。

## 复现入口

在新的输出目录、同一源码提交下执行：

```bash
python3 scripts/coverage/run_suite.py --stage all --output .cache/java-v1-quality-new --base 9211be9
(cd frontend && npm run lint && npm run format:check && npm run type-check \
  && npm run test:logic && npm run test:component && npm run build)
python3 -m unittest discover -s scripts/migration -p 'test_*.py'
python3 scripts/migration/go_inventory.py check
python3 scripts/migration/go_p8_inventory_progress.py
python3 scripts/migration/go_p8_test_progress.py
```

隔离设施的依赖缓存按 docs/testing.md 配置。实际发布物黑盒可指定 `run_go_e2e_tests.py --backend java --java-jar <已验JAR> --output <新目录> --mode e2e|cancel|plan|memory`，每种 mode 独立运行；原 e2e 仍预期保留上述一项 FAIL，不能忽略其退出码。真实资料恢复只使用已批准的只读备份和新建隔离目标，不直接对现有库运行导入/清理脚本。
