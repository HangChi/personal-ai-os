# 贡献指南

欢迎通过 Issue 和 Pull Request 改进 Personal AI OS。修正文档、复现缺陷、补充测试和改善体验都可以参与。较大的功能或架构变化请先用 Issue 说明使用场景、范围及与现有能力的关系。

## 开始开发

1. 阅读[项目 README](README.md)、[开发指南](docs/development.md)和[架构](docs/architecture.md)。
2. Java 使用 JDK 21、Maven Wrapper 和 Docker；前端推荐 Node 22.18+；Go 使用 1.27.0+，特有依赖见[Go 指南](backend-go/docs/development.md)。
3. 建立专用开发配置，不使用现有用户库作为测试夹具，不提交 `.env`、Cookie、真实模型密钥或个人资料。
4. 明确修改影响 Java、Go、前端与 SQL 哪些部分，按[双端同步规则](docs/backend-parity.md)维护。

启动命令见开发指南，各个长驻进程应使用独立终端。无真实模型可用本地 mock；它不能替代语义评测。

## 提交前验证

按改动范围运行，并在 PR 中说明实际执行、未执行及原因：

```bash
(cd backend && ./mvnw -B test)
(cd backend-go && go test -race -p 1 -count=1 -timeout=20m ./...)
(cd backend-go && go vet -tags=integration ./... && go build ./...)
(cd frontend && npm run lint && npm run format:check && npm run type-check \
  && npm run test:logic && npm run test:component && npm run build)
python3 scripts/docs/check_docs.py
python3 scripts/docs/generate_routes.py --check
```

Java 测试需 Docker，包含行 83%/分支 65%覆盖率门禁。Go 设施测试、前端组件交互及真实模型验证各有独立范围；不要用编译通过、固定 oracle 或合成 fixture 声称生产验收。真实模型会产生外部请求和费用，需独立目标/授权/预算，见[评测指南](docs/evaluation.md)。

## 实现约定

项目约束与历史设计见[宪法](.specify/memory/constitution.md)及 `specs/`。主要规则：

- SQL 只追加到 Java 唯一迁移目录，不修改已发布迁移；Go 不自行复制或执行迁移。
- Controller 不直接操作仓储，不返回实体；业务 Service 承担规则，Java 使用构造器注入。
- 模型、嵌入、解析、外部网络移出短事务；Go 传播 context/取消，持久任务提交验证租约与版本。
- 写操作保留幂等、审计、人工确认及未知结果保护，不能用重试掩盖不确定副作用。
- Agent 工具同步两版 Schema、分发、策略、证据、直接目录与测试；模型无自行批准能力。
- 前端公共组件放 `src/components`，纯函数放 `src/lib`；生产组件自动注册，颜色使用现有 `--el-*`/`--os-*` 设计变量。
- 保留固定 Java oracle、原标注与历史失败，不通过重写预期值消除差异。

## 文档同步

README 面向首次访问者，当前指南在 `docs/`，Go 装配在 `backend-go/docs/`。改动 Agent、RAG、配置、状态、接口或迁移时同步两版入口及[差异表](docs/backend-parity.md)。接口变更运行 `python3 scripts/docs/generate_routes.py`，不要手改生成清单。

有日期的评测/复盘保留实验范围和原始结果；新增结论另写报告并登记[报告索引](docs/reports/README.md)。不得把文档中的旧路径、测试数或分数当作当前服务的自动认证。

## Issue 与 PR

缺陷报告请包含最小复现、预期/实际结果、后端版本/配置范围、时间及脱敏 `requestId`/响应。不要贴完整环境文件、提示词、Cookie、私有文档或上游错误原文。安全问题使用[私有报告流程](SECURITY.md)。

PR 说明问题、修改后的行为、双版本影响、验证和残余限制；尽量保持单一主题。AI 辅助生成的贡献遵循同一标准，提交者应理解代码并核对测试与文档。

提交信息推荐 `<type>(<scope>): <简述>`：

```text
fix(knowledge): 避免替换时混入两代向量
feat(chat): 增加持久执行恢复
docs(agent): 补充工具确认与未知结果说明
```

类型可用 feat/fix/refactor/test/docs/chore/perf，scope 使用模块名。提交贡献即表示同意以 [Apache License 2.0](LICENSE)授权。
