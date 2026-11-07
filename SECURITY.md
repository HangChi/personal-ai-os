# 安全策略

Personal AI OS 是单用户、自托管应用，可运行 combined 或 API/独立 Worker。它没有多租户隔离、团队角色权限或合规审计承诺。数据库、附件和可选外部服务可分布部署，不能假定所有数据只在本机。

## 私下报告漏洞

通过 [GitHub 私有安全公告](https://github.com/HangChi/personal-ai-os/security/advisories/new)报告，不要在公开 Issue 中披露可利用细节、密钥或个人数据。请提供受影响版本/模块、最小复现、影响和脱敏证据。个人项目不承诺响应 SLA，会优先处理数据丢失、身份绕过和凭据泄漏问题。

## 部署边界

Java 公网部署使用 `compose.production.yml` 及 `.env.production`，必须启用认证、强数据库/登录密码、CSRF、Secure Cookie 和 HTTPS。仅向公网开放反向代理；数据库、内部业务/管理端口保持私有。SSE 的心跳、超时与反向代理配置见[运维](docs/operations.md)。本地 `AUTH_ENABLED=false` 仅适合受信本机。当前 Java 的 Cookie 属性路径存在别名差异，需显式 `SERVER_SERVLET_SESSION_COOKIE_SECURE=true`，生产叠加示例见运维手册；不能只看 SESSION_COOKIE_SECURE 判断安全链生效。

Go 是候选，仍拒绝 prod/生产模式并保持 `/readyz=503`。不得以诊断存活、测试 PASS 或候选 Compose 定义代替发布批准。切换前需单独完成[安全与门禁](backend-go/docs/security.md)，不并行写现有 Java 库。

## 认证、CSRF 与会话

生产浏览器写请求先取 `/api/v1/auth/csrf`，随后发送 `X-CSRF-TOKEN`；登录轮换会话后重新取 token。对外 `/mcp` 有精确协议 CSRF 豁免，仍要求已配置的认证。登录失败有锁定策略（默认五次/60 秒），可能被恶意触发临时锁定。

多 API 可使用独立 Redis 共享会话，需 TLS/ACL/环境命名空间；故障不应回退为独立身份。Java/Go 会话序列化不互通，切换需重新登录。模型限流、会话和缓存各有独立连接及故障策略，见[Redis](docs/redis-extensions.md)。

## Agent 与不可信内容

模型从已注册工具 Schema 中选择，服务端仍校验参数、权限、预算、幂等和状态。删除等高风险工具先建人工确认；模型没有批准自己的工具。停止/失败不会撤销已完成写入，外部结果未知时不得自动重放，见[Agent 运行](docs/agent-runtime.md)。

Prompt 不是沙箱。外部 MCP stdio 命令具有后端进程所获权限；部署者需限制目录/身份，不开放整个 home/磁盘。AUTO_TOOLS 是受信配置，不能把写工具加入名单绕过确认。第三方网页、知识片段和工具结果属于数据，不能授权新动作；来源核对与引用保护不能保证完全消除提示注入风险。

上传有扩展名、大小、MIME/路径等验证，但不是通用恶意文件扫描器。PDF 文本提取不等于 OCR；网页采集有协议/地址/大小边界，不能用作任意内网抓取。不要给 Agent 配置超出个人场景需要的外部凭据。

## 数据出站与密钥

| 可选能力 | 可能发送的数据 |
| --- | --- |
| 外部聊天/视觉/音频模型 | 对话、上下文、附件、记忆及证据片段 |
| 外部嵌入/重排/引用审计 | 文本分块、查询、候选与回答 |
| Brave/MCP | 搜索词、工具参数及工具可访问的数据 |
| Redis/OpenSearch/S3 | 会话、缓存、搜索投影或附件；候选缓存含原文 |
| Push/Webhook | 订阅端点、提醒及通知内容 |

本地 Ollama 也取决于实际端点和部署边界。提供商条款和第三方服务漏洞由各自维护方负责；项目应修复自身集成中的访问/泄漏问题。真实模型评测同样产生出站数据和费用。

模型密钥可来自环境或持久模型配置；可选 `MODEL_KEY_ENCRYPTION_KEY` 使用 AES-256-GCM 加密数据库中的模型密钥。环境值不因此加密，旧明文需重新保存才转换。主密钥独立保存/备份，不能随公开证据或数据库一起暴露；丢失会导致密文不可读。

完整备份需要数据库、附件/S3 与独立配置/密钥；业务 JSON 导出不是完整备份。暂停入口和全部 Worker 后处理跨资源一致性，验证恢复并核查未知外部写。恢复数据库不会撤销已发通知或 MCP 副作用。

## 依赖与支持范围

依赖来源为 `backend/pom.xml`、`backend-go/go.mod/go.sum`、`frontend/package.json/package-lock.json` 及容器镜像；Go/Poppler 许可见[第三方声明](backend-go/THIRD_PARTY_NOTICES.md)。发现依赖漏洞也可报告本项目受影响的具体配置，维护者需评估升级或隔离，而非仅以“上游问题”关闭风险。

物理主机控制、操作者主动配置的超权限服务和多租户隔离不属于当前承诺。更多受信边界见[架构](docs/architecture.md)、[MCP](docs/mcp.md)及[Go 安全](backend-go/docs/security.md)。
