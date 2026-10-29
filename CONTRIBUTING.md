# 参与贡献

先说清一件事：这是一个**个人自托管项目**，没有产品团队、没有路线图会议。
接受贡献，但请按下面的方式提，能省掉双方很多来回。

## 最快的参与方式

1. **报 bug** —— 带上复现步骤与实测输出（见下文「报缺陷要带什么」）
2. **改文档** —— 错别字、失效的命令、跑不通的步骤，直接提 PR，不必先开 issue
3. **提功能** —— **先开 issue 说明场景**。项目按阶段串行推进，
   一个「顺手加的功能」很可能与下一阶段的既定设计冲突

## 本地跑起来

前置：JDK 21、Docker（含 `docker compose`）、Node.js `^20.19.0 || >=22.12.0`。
不需要预装 Maven —— 用仓库自带的 `./mvnw`。

```bash
cp .env.example .env          # 至少填 OPENAI_API_KEY 等模型配置
docker compose up -d          # PostgreSQL 17 + pgvector，映射到 55432
cd backend && ./mvnw spring-boot:run
cd frontend && npm install && npm run dev
```

细节与排错见 [`docs/development.md`](docs/development.md)。

## 提交前必须跑的命令

```bash
# 后端：单元 + Testcontainers 集成测试，含 83% 行 / 65% 分支覆盖率门禁
cd backend && ./mvnw -B test

# 前端：与 CI 完全一致的五步
cd frontend && npm run lint && npm run format:check && npm run type-check \
  && npm run test:logic && npm run test:component
```

**PR 里请说明你跑了哪些**。CI 会跑同一套，但「等 CI 告诉我」比自己先跑一轮更慢。

> ⚠️ 本机若 `JAVA_HOME` 为空，`mvn` 可能落在非 21 的 JDK 上，症状是
> 集成测试成片失败且日志里有 `IllegalClassFormatException`（class 主版本不认识）。
> 显式指定：`JAVA_HOME=/path/to/jdk-21 ./mvnw -B test`。判据是这类异常的条数应为 **0**。

## 代码约定

完整约束以项目宪法为准：[`.specify/memory/constitution.md`](.specify/memory/constitution.md)。
最常被退回的几条：

| 约定 | 说明 |
|---|---|
| 结构变更只走 Flyway | `ddl-auto` 只能是 `validate`；已合并的迁移**不得修改**，只能追加新的 |
| 写操作必经 Service | Controller 不直接调 Repository，也不含业务逻辑 |
| 不暴露 Entity | 对外一律 DTO |
| 构造器注入 | 禁止字段注入 `@Autowired` |
| 长耗时步骤移出事务 | 模型调用、嵌入、文档解析都不许在 `@Transactional` 里 |
| 不提交密钥 | 含 `.env`、测试里的真实 token、日志里的完整 URL |

前端另有几条（详见 `frontend/` 的结构）：

- 公共组件放 `src/components/`，靠 unplugin 自动注册，**不要全局注册**
- 纯函数放 `src/lib/`，并配 `.mjs` 测试；视图只做编排
- 颜色只消费 `--el-*` / `--os-*` 变量，不写死色值

## 报缺陷要带什么

缺了这三样，多半只能回你一句「复现不了」：

1. **复现步骤**（尽量给 `curl` 或最小操作序列，而不是「点了几下就崩了」）
2. **实际 vs 期望**（两句就够）
3. **证据** —— 后端日志里带 `requestId` 的那几行、接口响应体、或数据库里的实际行

不要贴完整的 `.env`；密钥请打码。

## 关于 AI 辅助提交

这个仓库的多数改动是借助 AI 完成的，**这不影响合并标准**：
代码要能解释、测试要真能抓到缺陷、注释里不要写「此处待优化」。
提 PR 时如果某段是生成的，请你自己先读懂 —— 审阅时我会问「为什么这么写」。

## 提交信息

格式：`<type>(<scope>): <中文简述>`

```
fix(knowledge): 文档替换加互斥，避免两代向量混存
feat(chat): 会话级互斥，同会话并发流返回 409
docs(ops): 备份恢复步骤补上 pg_restore 的 -c 参数
```

类型：`feat` / `fix` / `refactor` / `test` / `docs` / `chore` / `perf`。
scope 用模块名（`chat` / `task` / `knowledge` / `calendar` / `memory` / `mcp` / `ui` / `ops`）。

## 许可证

提交即表示同意以 [Apache License 2.0](LICENSE) 授权。
