# Feature Specification: 阶段 15 — 单用户认证

**Feature Branch**: `015-single-user-auth`

**Created**: 2026-10-25

**Status**: Draft

**Input**: 用户需求：「选用主流认证方案即可」—— 当前零鉴权，能连 8080 就能读写全部数据。

## 背景

宪法原则 II 写着「若服务需对公网开放，MUST 先完成身份认证设计，否则不得开放」。
而现状是：`/api/v1/**` 全部裸奔，任何能访问端口的人都能删任务、读对话、导出全部数据。

这不是「以后再说」的问题 —— 它是**唯一**阻塞公网开放的硬条件。

### 方案选择

采用 **Spring Security + 服务端会话（Session Cookie）**，单用户、用户名密码来自环境变量。

被否掉的方案：

| 方案 | 为什么不选 |
|---|---|
| JWT（无状态 token） | 单用户场景没有「多端并发 + 水平扩展」的需求，却要额外承担 token 下发、刷新、撤销、前端存储位置（localStorage 易被 XSS 读）这一整套复杂度 |
| HTTP Basic | 浏览器原生弹窗无法定制「登录已过期」这类提示，且每个请求都回传密码 |
| OAuth / OIDC | 自托管单用户场景引入外部依赖，配置成本远超收益 |
| 反向代理 Basic Auth（Nginx） | 是可行的替代路径，但把认证放在应用外会让「会话过期提示」「登出」这些体验无处实现，且用户得自己写 nginx 配置 |

Session Cookie 是这四者里唯一「零新概念」的：浏览器自己管 cookie，服务端 `HttpSession` 管过期，
前端只需要处理 401。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 开启认证后必须登录（Priority: P1）

作为把服务暴露到公网的用户，我打开页面时应该先看到登录页，登录后才能访问任何功能。

**Why this priority**：这是本阶段的全部原因。

**Independent Test**：`AUTH_ENABLED=true` 启动 → 直接请求 `/api/v1/tasks` 得到 401；
登录后同请求返回 200。

**Acceptance Scenarios**：

1. **Given** 认证已开启且未登录，**When** 浏览器打开任意页面，**Then** 被带到登录页。
2. **Given** 未登录，**When** 直接请求任意 `/api/v1/**`（除登录与健康检查），**Then** 返回 401 + 统一错误信封（**不是** HTTP 302 跳 HTML 登录页）。
3. **Given** 输入正确用户名密码，**When** 提交，**Then** 登录成功、cookie 生效、跳到首页。
4. **Given** 输入错误密码，**When** 提交，**Then** 明确提示「用户名或密码错误」，不透露是哪一个错。
5. **Given** 已登录，**When** 点登出，**Then** 会话失效，后续请求重新 401。

---

### User Story 2 - 关闭认证时零影响（Priority: P1）

作为本地开发的我，不想为了跑起来先配密码。

**Why this priority**：这个项目的第一版定位就是「本地单用户」。
如果默认开启认证，所有现有测试、所有文档里的启动命令全部失效。

**Independent Test**：不设任何 `AUTH_*` 环境变量启动 → 所有接口照常可用，
前端不出现登录页，顶栏不显示用户名。

**Acceptance Scenarios**：

1. **Given** 未配置 `AUTH_ENABLED`，**When** 启动，**Then** 所有接口放行，行为与阶段 14 完全一致。
2. **Given** 认证关闭，**When** 前端加载，**Then** 不跳登录页、不发起登录请求。
3. **Given** `AUTH_ENABLED=true` 但没有配密码，**When** 启动，**Then** **启动失败**并说明缺哪个变量
   （比「起来了但谁都能进」安全）。

---

### User Story 3 - 会话过期有可读反馈（Priority: P2）

作为用户，长时间没操作后回来点按钮，应该看到「登录已过期」并被带回登录页，而不是一直转圈。

**Independent Test**：登录后手动清除 cookie，再点任意操作 → 出现提示并跳登录页。

**Acceptance Scenarios**：

1. **Given** 会话已失效，**When** 前端发起任意 API 请求，**Then** 收到 401 → 提示「登录已过期」→ 跳登录页。
2. **Given** 正在登录页，**When** 登录成功，**Then** 回到原本想访问的页面（或首页）。

## Edge Cases

| 输入 | 期望行为 |
|---|---|
| 用户名/密码为空 | `40000` 参数校验失败 |
| 密码正确但用户名错 | 与「密码错」同一个提示，不区分（避免枚举用户名） |
| `AUTH_ENABLED` 值非布尔（如 `yes`） | 启动失败并给出可读提示（配置错误不该静默当成 false） |
| 密码含特殊字符 / 中文 | 正常登录（不做字符集假设） |
| 并发登录同一账号 | 允许（单用户多标签页是正常用法），不做单点登录互踢 |
| 健康检查 `/api/v1/health` | 始终放行（监控探针不该需要凭据） |
| `/actuator/health` | 始终放行 |
| 静态资源与前端路由 | 始终放行（前端要能加载登录页） |

## Requirements *(mandatory)*

- **FR-001**：MUST 提供 `AUTH_ENABLED`（默认 `false`）、`AUTH_USERNAME`（默认 `admin`）、
  `AUTH_PASSWORD` 三个配置项。
- **FR-002**：`AUTH_ENABLED=true` 且未配置密码时 MUST **启动失败**，并指出缺少的变量。
- **FR-003**：登录 MUST 走 `POST /api/v1/auth/login`（JSON），成功后建立服务端会话并下发 cookie。
- **FR-004**：未认证访问受保护接口 MUST 返回 `401` + 统一错误信封，MUST NOT 重定向到 HTML 页面。
- **FR-005**：MUST 提供 `POST /api/v1/auth/logout`（使会话失效）与 `GET /api/v1/auth/me`
  （返回当前用户与 `authEnabled`，未登录时返回 `user: null` 而不是 401）。
- **FR-006**：密码 MUST NOT 以明文形式出现在日志、错误响应或仓库中；内存中只保留 BCrypt 哈希。
- **FR-007**：健康检查端点 MUST 始终放行。
- **FR-008**：前端 MUST 在 `authEnabled=true` 且未登录时跳转登录页；收到 401 MUST 提示并跳登录。
- **FR-009**：`AUTH_ENABLED=false` 时，所有接口行为 MUST 与阶段 14 完全一致（现有 249 个测试零改动通过）。
- **FR-010**：登录失败 MUST NOT 泄露「用户名是否存在」。
- **FR-011**：本阶段 MUST NOT 引入多用户、角色、权限矩阵 —— 单用户是全系统的既定前提。

## Success Criteria

- **SC-001**：`./mvnw -B verify` 全绿；**现有 249 个测试在 `AUTH_ENABLED=false` 下零改动通过**。
- **SC-002**：新增认证测试覆盖：未登录 401、正确登录 200、错误密码 401、登出后 401、
  `AUTH_ENABLED=true` 无密码启动失败、健康检查始终放行。
- **SC-003**：`npm run test:logic` 全绿，覆盖「401 触发跳转」与「关闭认证时不跳转」两条。
- **SC-004**：真机：开启认证 → 打开页面被带到登录页 → 登录 → 正常操作 → 登出 → 再次被拦截。

## Assumptions

- **关闭 CSRF，改用 `SameSite=Lax` 的会话 cookie**。这是本阶段最需要解释的一个取舍：
  - CSRF 保护在有 cookie 认证时确实是标准做法，但 Spring Security 6+ 的 SPA 场景需要
    `SpaCsrfTokenRequestHandler` + 前端读 `XSRF-TOKEN` 才能正确工作，否则会出现
    「cookie 里的 token 与校验用的 token 不一致」的隐性 403；
  - 本应用的写接口**全部要求 `Content-Type: application/json`**，跨站表单无法构造这种请求；
    跨站 `fetch` 又会先被 CORS 预检拦住；
  - `SameSite=Lax` 让浏览器在跨站 POST 时不携带会话 cookie，这是当前对 CSRF 的主要防线。
  - 残余风险：同站子域（如 `evil.example.com` 对 `app.example.com`）不受 SameSite 保护。
    若要彻底消除，需要启用 CSRF token 或把应用部署在独立域名的根路径下。
  - **这个权衡必须写进 README**，而不是埋在代码注释里。
- 不做「记住我」：单用户场景下多打一次密码的成本，低于长期 cookie 被盗的代价。
- 不做登录失败次数限制：本地/单用户部署下，暴力破解的收益远低于维护限流状态的复杂度。
  公网暴露时应由反向代理（fail2ban / WAF）承担。
