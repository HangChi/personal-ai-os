# 研究与决策

## R-01：生产使用独立 Compose

继续复用本地 Compose 容易把开发端口、默认密码和本地卷带入生产。单独的生产文件可以让数据库、backend、frontend
只加入内部网络，且用独立命名卷避免误迁移。

## R-02：Session CSRF 而非可读 Cookie token

当前认证使用服务端会话。生产使用 `HttpSessionCsrfTokenRepository`，前端通过同源安全 GET 取得 token，
避免把 CSRF token 放进 JavaScript 可读的长期 Cookie；登录轮换会话后前端清除缓存并重新获取。

## R-03：Caddy 负责 HTTPS

Caddy 只发布 80/443，自动申请和持久化证书；backend 不直接暴露宿主机端口，避免绕过统一安全头和路由。
域名和 ACME 邮箱由部署者提供，仓库只提交配置模板。
