# Feature Specification: 生产安全加固

**Feature Branch**: `025-production-security`

**Status**: Implemented

## 背景

项目仍是单用户、自托管系统，但生产入口需要把认证、会话、CSRF、数据库暴露和 HTTPS
配置收敛到一个可检查的部署方案。开发环境必须继续支持 `http://localhost` 和默认关闭认证。

## 目标

- 提供独立的生产 profile 和 Compose 文件。
- 生产启动对认证、CSRF、Secure Cookie、数据库密码和登录密码 fail closed。
- 让 SPA 的普通请求、上传和 SSE 都能携带会话 CSRF token。
- 生产只发布 Caddy 的 80/443，数据库和应用服务留在内部网络。
- 记录域名、证书和密钥由部署者配置，不在本地申请或暴露服务。

## 非目标

- 多用户、角色权限、账户自助改密。
- 自动修改现有开发数据库密码或迁移本地卷到生产卷。
- 代替云防火墙、WAF、备份和主机补丁管理。

## 约束

- 本地默认值保持兼容：认证和 CSRF 默认关闭，Cookie Secure 默认关闭。
- 生产必须使用 `SPRING_PROFILES_ACTIVE=prod` 与 `compose.production.yml`。
- CSRF 令牌通过 `/api/v1/auth/csrf` 获取，写请求使用 `X-CSRF-TOKEN`。
- 失败响应继续使用统一 API 信封。
