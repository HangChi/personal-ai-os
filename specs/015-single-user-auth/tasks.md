---
description: "Task list for 阶段 15 — 单用户认证"
---

# Tasks: 阶段 15 — 单用户认证

**Input**: Design documents from `/specs/015-single-user-auth/`

---

## Phase 1: 依赖与配置

- [x] T001 `pom.xml` 加 `spring-boot-starter-security`（版本由 BOM 管理）
- [x] T002 `common/auth/AuthProperties`（`enabled` / `username` / `password`）+ `application.yml` 绑定
- [x] T003 `AUTH_ENABLED=true` 且密码为空 → **启动失败**并指出变量名
- [x] T004 `ErrorCode` 加 `UNAUTHORIZED(40100, "未登录或登录已过期", 401)`
- [x] T005 `server.servlet.session.cookie.same-site=lax` + `http-only`（Boot 默认已 httpOnly）

## Phase 2: 安全链

- [x] T006 `SecurityConfig`：`SecurityFilterChain`
      —— 关闭 CSRF（理由见 plan R-02）、`IF_REQUIRED` 会话、关闭 formLogin/httpBasic、
      放行 `/api/v1/auth/login|me|logout` + `/api/v1/health` + `/actuator/health` + 静态资源
- [x] T007 `authenticationEntryPoint`：401 + 统一 JSON 信封（不是 302）
- [x] T008 `PasswordEncoder` Bean（BCrypt）
- [x] T009 `AUTH_ENABLED=false` 时 `anyRequest().permitAll()`（与阶段 14 行为完全一致）

## Phase 3: 登录/登出/当前用户

- [x] T010 `AuthService`：校验用户名密码（BCrypt matches）、建立会话（`SecurityContextRepository.saveContext`）
- [x] T011 `AuthController`：`POST /auth/login`、`POST /auth/logout`、`GET /auth/me`
- [x] T012 `GET /auth/me` 未登录返回 `{authEnabled, user: null}`（200），不是 401
- [x] T013 登录失败统一文案「用户名或密码错误」（不区分哪一个错）
- [x] T014 登出 `session.invalidate()` + 清 SecurityContext

## Phase 4: 后端测试

- [x] T015 `AuthIntegrationTest`（`AUTH_ENABLED=true`）：未登录 401 信封 / 正确登录 200 /
      错误密码 401 / 登录后受保护接口 200 / 登出后 401 / 健康检查始终放行 /
      `me` 未登录返回 user=null
- [x] T016 `AuthDisabledIntegrationTest`（默认配置）：全部接口照常可用、`me` 返回 `authEnabled=false`
- [x] T017 `./mvnw -B verify` 全绿，**现有 249 个测试零改动通过**

## Phase 5: 前端

- [x] T018 `api/auth.ts`：login / logout / fetchCurrentUser
- [x] T019 `stores/auth.ts`：`currentUser` / `authEnabled` / `login` / `logout` / `bootstrap`
- [x] T020 `LoginView.vue`：用户名密码表单、错误提示、成功回跳
- [x] T021 `router`：`/login` 路由 + 全局守卫（`authEnabled && !user` → 跳登录，
      登录页在已登录时跳首页）
- [x] T022 `http.ts`：401 拦截 → 清状态 → 跳 `/login?redirect=...`
      （**但要排除登录接口本身的 401**，否则会把「密码错」也当会话过期）
- [x] T023 `App.vue`：`authEnabled` 时顶栏显示用户名 + 登出
- [x] T024 `test/auth-store.test.mjs`：登录成功/失败、登出清状态、关闭认证时 `authEnabled=false`

## Phase 6: 验证与文档

- [x] T025 真机：`AUTH_ENABLED=true` 启动 → 打开页面被带到登录页 → 登录 → 正常操作 → 登出 → 再被拦截
- [x] T026 真机：默认配置（关闭）启动 → 不出现登录页、顶栏无用户名、所有功能照常
- [x] T027 README：新增「开启访问控制」一节，**明确写出关闭 CSRF 的权衡与残余风险**
- [x] T028 `.env.example` 加 `AUTH_ENABLED` / `AUTH_USERNAME` / `AUTH_PASSWORD` 说明
- [x] T029 提交：`docs(auth): 阶段 15 规格` + `feat(auth): 阶段 15 实现`

## 完成记录

**提交**：`87028fd` feat(auth): 阶段 15 单用户认证（T001-T028）

**测试**
- 后端 `./mvnw -B verify`：**264** 通过（原 249 + 新增 15），**原有 249 个零改动通过**
  - `AuthServiceTest`（6 单元）：缺密码启动失败、正确/错误凭据、大小写敏感、去空白、
    关闭时不放行、不泄露明文
  - `AuthIntegrationTest`（6，`AUTH_ENABLED=true`）：未登录 401 信封、健康检查放行、
    `me` 返回 user=null、错密码与错用户名同一文案、登录后可读可写、登出后 401
  - `AuthDisabledIntegrationTest`（3，默认配置）：全接口放行、`me` 报 `authEnabled=false`、
    登录不伪造会话
- 前端 `npm run test:logic`：**224** 断言通过（+27）；`type-check`、`build` 通过

**真机验证（Playwright + curl，两个场景）**
| 场景 | 结果 |
|---|---|
| 默认关闭 | 不跳登录、顶栏无用户名、导航正常、0 错误 |
| 开启后未登录 | `curl /api/v1/tasks` → **401 + `{code:40100}`**；打开页面 → `/login?redirect=/`；导航隐藏 |
| 错误密码 | 提示「用户名或密码错误」，停在登录页 |
| 正确密码 | 回首页，顶栏显示 `admin`，导航恢复 |
| 登录后直接访问 `/tasks` | 正常渲染 |
| 登出 | 回到 `/login`，之后请求重新 401 |
| 登录成功路径页面错误 | **(无)** |

**说明**：测试脚本在「错误密码」步骤后清空了错误缓冲，因此最后一行只反映登录成功后的状态。
单独跑错误密码时浏览器会输出一条 `Failed to load resource: 401` —— 那是浏览器对
预期 401 的标准网络日志，不是应用错误。

**新增依赖**：`spring-boot-starter-security`（版本由 Boot BOM 管理）。
这是本项目第一个因安全能力引入的运行时依赖，理由见 plan 的 Complexity Tracking：
会话固定攻击、cookie 属性、过滤器顺序这些细节 Spring Security 已做对，重写只会更差。
