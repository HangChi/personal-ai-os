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

（实现后回填）
