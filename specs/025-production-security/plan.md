# 实施计划

1. 将生产安全要求集中为 `ProductionSecurityValidator`，配置错误在启动时失败。
2. 为 Spring Security 增加可选会话 CSRF、403 JSON handler 和 CSRF token endpoint。
3. 在 Axios、multipart、登录/登出和原生 fetch SSE 路径统一接入 token。
4. 将本地 Compose 端口绑定到回环地址，新增内部网络生产 Compose 与 Caddy HTTPS 入口。
5. 同步 README、SECURITY、运维、开发、架构和接口文档，保留阶段 15 的历史决策说明。

验证采用后端完整 verify、前端全部质量门禁、Compose 配置解析和生产配置结构检查；不申请真实证书、不向公网发送请求。
