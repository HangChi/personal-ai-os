# Implementation Plan: 阶段 15 — 单用户认证

**Branch**: `015-single-user-auth` | **Date**: 2026-10-25 | **Spec**: [spec.md](./spec.md)

## Summary

引入 Spring Security，单用户、用户名密码来自环境变量、会话存 `HttpSession`。
`AUTH_ENABLED` 默认 `false`，关闭时全放行 —— 保证现有 249 个测试与本地开发零影响。

前端加登录页、路由守卫、401 拦截。

**唯一新依赖**：`spring-boot-starter-security`。

## Technical Context

- Java 21 / Spring Boot 4.1.1（对应 Spring Security 7.x）
- 会话：Servlet `HttpSession` + JSESSIONID cookie（`SameSite=Lax`）
- 密码：BCrypt（`PasswordEncoder`），内存只保留哈希
- 前端：Vue 3 + Pinia + vue-router
- 测试：Testcontainers 集成测试；前端 `.mjs`

## Constitution Check

| 原则 | 判定 | 依据 |
|---|---|---|
| I. 简单优先 | ✅ | 只加一个 starter。**不**做 JWT（R-01）、**不**做多用户/角色（FR-011）、**不**做记住我、**不**做登录限流（由反代承担） |
| II. 边界即安全 | ✅ | 这正是本阶段的目的。唯一有争议处是关闭 CSRF —— 理由与残余风险写进 spec Assumptions 与 README（R-02），不是悄悄关掉 |
| III. 分层与写路径收敛 | ✅ | 认证逻辑在 `AuthService`；Controller 只解析请求与包响应；密码不落 Entity、不落日志 |
| IV. 数据变更可追溯 | ✅ | **无数据库变更**（单用户凭据在环境变量，不进库） |
| V. 可验证的交付 | ✅ | 六条认证路径各有断言；真机走「拦截 → 登录 → 登出 → 再拦截」 |
| 工作流：阶段串行 | ✅ | 015 接在 014 之后 |

## Project Structure

**后端**
```
pom.xml                                      改：+ spring-boot-starter-security
common/auth/AuthProperties.java              新：enabled / username / password
common/auth/SecurityConfig.java              新：过滤器链、401 信封、BCrypt
common/auth/AuthService.java                 新：凭据校验 + 会话建立
common/auth/AuthController.java              新：login / logout / me
common/auth/dto/{LoginRequest,CurrentUserDto}.java  新
common/api/ErrorCode.java                    改：+ UNAUTHORIZED(40100)
application.yml                              改：server.servlet.session.cookie.same-site + app.auth.*
```

**前端**
```
src/api/auth.ts         新：login / logout / fetchCurrentUser
src/stores/auth.ts      新：currentUser / authEnabled / loading / error
src/views/LoginView.vue 新
src/router/index.ts     改：/login 路由 + 全局守卫
src/App.vue             改：顶栏用户名 + 登出（仅 authEnabled 时显示）
src/api/http.ts         改：401 拦截 → 跳登录
frontend/test/auth-store.test.mjs 新
```

## Phase 0 / Phase 1 结论

### R-01 会话 Cookie 而不是 JWT

单用户场景下 JWT 的三个卖点（无状态横向扩展、跨域、移动端）一个都用不上，
却要带来：token 存哪（localStorage 会被 XSS 读）、怎么撤销（需要黑名单表）、怎么刷新。
`HttpSession` 过期即失效，登出就是 `invalidate()`，没有额外的表与状态。

### R-02 关闭 CSRF 并用 SameSite=Lax（显式权衡）

Spring Security 6+ 在 SPA 场景下要正确工作，需要 `SpaCsrfTokenRequestHandler`
加前端读取 `XSRF-TOKEN` cookie —— 而它们的默认 handler 每次请求都会生成新 token，
「cookie 里的值」与「校验用的值」不一致时会得到难以排查的 403。

对当前 API 的实际情况：所有写接口都要求 `Content-Type: application/json`，
跨站表单无法构造；跨站 `fetch` 先被 CORS 预检拦下；`SameSite=Lax` 让跨站 POST 不带 cookie。

残余风险（同站子域）写在 README，不藏在注释里。若将来要彻底消除，正确的做法是
启用 CSRF token，而不是维持现状。

### R-03 默认关闭

`AUTH_ENABLED=false` 是刻意的：项目定位是本地单用户，默认开认证会让
「clone 下来就能跑」变成「先配密码」，且现有 249 个测试全部要带凭据。

安全默认值在这里要**反向**看：默认关闭的风险是「本机暴露」，而默认开启的风险是
「用户为了跑起来随手设个 123456 并留在公网上」。前者靠文档提醒，后者无法补救。

### R-04 配置缺失时启动失败

`AUTH_ENABLED=true` 但没配密码 → 启动失败。
「起来了但谁都能进」是最糟的结果：用户以为自己开了认证。

### R-05 401 必须走统一信封

Spring Security 的默认行为是 302 到表单登录页（返回 HTML）。
SPA 拿到 HTML 会解析失败，症状是「点按钮没反应」。
所以 `AuthenticationEntryPoint` 必须手写 JSON —— 与 `GlobalExceptionHandler` 的错误格式保持一致。

### R-06 密码只在环境变量

不建 `user` 表：单用户不需要，且建表就要处理「初始密码怎么来」「改密码怎么走」。
环境变量 + `.env.example` 模板是自托管项目的通行做法。
启动时用 BCrypt 编码一次，内存与 Spring 的 `UserDetails` 只持有哈希。

## Complexity Tracking

| 项 | 为什么必须引入 |
|---|---|
| `spring-boot-starter-security` | 手写认证过滤器意味着要自己处理会话固定攻击、cookie 属性、注销、过滤器顺序 —— 这些 Spring Security 都做对了，重写只会更差 |
| 关闭 CSRF | 已在 R-02 与 spec 中论证并记录残余风险，非「图省事」 |
