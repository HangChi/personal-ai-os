# Java配置迁移对照索引

固定基线 `cb5d694d62fa93742b5789b97273e76d931ce335`，由真实Spring YAML加载器和Java record反射生成。只记录原始来源，不能据此标记已迁移。

覆盖 12 个配置record、67 个字段/容器节点、70 处@Value、8 处直接getProperty读取、17 个YAML文档/205 个属性出现项。

## 配置record

| 前缀 | Java类 | 字段/容器节点 |
| --- | --- | --- |
| `app.plan` | `com.personalos.agent.plan.PlanProperties` | 2 |
| `app.calendar` | `com.personalos.calendar.config.CalendarProperties` | 9 |
| `app.agent` | `com.personalos.common.ai.AgentExecutionProperties` | 2 |
| `app.ai` | `com.personalos.common.ai.AiProperties` | 4 |
| `app.context` | `com.personalos.common.ai.ContextProperties` | 9 |
| `app.context.protection` | `com.personalos.common.ai.ContextProtectionProperties` | 4 |
| `app.auth` | `com.personalos.common.auth.AuthProperties` | 5 |
| `app` | `com.personalos.common.config.AppProperties` | 2 |
| `app.rate-limit` | `com.personalos.common.config.RateLimitProperties` | 3 |
| `app.knowledge` | `com.personalos.knowledge.config.KnowledgeProperties` | 11 |
| `mcp` | `com.personalos.mcp.config.McpProperties` | 7 |
| `app.memory` | `com.personalos.memory.MemoryProperties` | 9 |

## 原始YAML文档

文档序号从0开始；同名属性在不同文档/profile分别保留，不提前合并覆盖。构建期@project.version@仅替换成基线pom版本，环境占位符保持原文。

| 文件 | 文档序号 | 属性数 |
| --- | --- | --- |
| `backend/src/main/resources/application-monitoring.yml` | 0 | 6 |
| `backend/src/main/resources/application-prod.yml` | 0 | 10 |
| `backend/src/main/resources/application-runtime-api.yml` | 0 | 1 |
| `backend/src/main/resources/application-worker-delivery.yml` | 0 | 4 |
| `backend/src/main/resources/application-worker-index.yml` | 0 | 4 |
| `backend/src/main/resources/application-worker-review.yml` | 0 | 4 |
| `backend/src/main/resources/application.yml` | 0 | 135 |
| `backend/src/main/resources/application.yml` | 1 | 3 |
| `backend/src/main/resources/application.yml` | 2 | 4 |
| `backend/src/main/resources/application.yml` | 3 | 3 |
| `backend/src/main/resources/application.yml` | 4 | 8 |
| `backend/src/main/resources/application.yml` | 5 | 5 |
| `backend/src/main/resources/application.yml` | 6 | 8 |
| `backend/src/main/resources/application.yml` | 7 | 3 |
| `backend/src/main/resources/application.yml` | 8 | 3 |
| `backend/src/main/resources/application.yml` | 9 | 3 |
| `backend/src/main/resources/application.yml` | 10 | 1 |

## 已实测的Cookie配置路径差异

基线YAML在`spring.servlet.session.cookie.secure`放值，原SecurityConfig/SharedSessionConfig/ProductionSecurityValidator读取`server.servlet.session.cookie.secure`。以下使用真实Spring属性解析和原生产守卫，只注入合成map，未读取宿主环境。

| 合成场景 | YAML路径值 | 守卫读取值 | 原生产守卫 |
| --- | --- | --- | --- |
| `local-default` | false | false | accepted |
| `local-cookie-alias` | true | false | accepted |
| `prod-cookie-alias` | true | false | 生产配置必须启用 SESSION_COOKIE_SECURE=true |
| `prod-canonical-env` | true | true | accepted |

prod-cookie-alias使用有效虚构密码、SESSION_COOKIE_SECURE=true及prod YAML，仍因守卫读到false拒绝；额外显式SERVER_SERVLET_SESSION_COOKIE_SECURE=true才通过该原守卫。Go LoadSecurity已按这份真实来源对照修正，不再把SESSION_COOKIE_SECURE/prod错误路径伪造成true；先前生产守卫参数级测试不能证明来源绑定等价。保留原Java/YAML并记录该历史差异，完整属性绑定保持T104开放；Go生产监听仍被禁止，不能据此放开生产。

## 动态与默认值边界

- MCP的`mcp.servers.*`与其env/args/auto-tools不能按静态YAML清单封死；JSON保留完整泛型类型和嵌套record通配节点。
- `@DefaultValue`、YAML默认占位符、record紧凑构造器归一化是三个来源，不能只选其中一个。
- @Value注入/直接getProperty读取的原表达式及行号、全部字段和YAML值见`config-inventory.json`；动态键调用单独标明，需回到调用方常量表/循环审查，不能按字面键过滤掉。索引生成器遇到不支持的声明会失败。
- 此处只读基线Git内容与离线依赖，不读真实.env、不绑定系统配置、不启动应用。

## 复验

```bash
python3 scripts/migration/go_config_inventory.py
```

默认重生成并比较；只有显式`--write`才更新这两份索引。另须继续运行go_inventory.py check；该索引不替代全量绑定/行为/Java-Go差分和发布门禁。
