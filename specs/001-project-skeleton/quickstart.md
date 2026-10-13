# Quickstart: 阶段 0 验证场景

> **验证记录表中的每一项，只有在**实际执行**过才能标记 ✅。未执行一律 ⏳。**
> 本文件在 `tasks.md` 实现完成前，所有行均为 ⏳。

## 前置

- JDK 21、Docker（含 compose）、Node.js ≥ 20.19 与 npm。
- 未预装 Maven 时使用 `backend/mvnw`。

## 场景 A — 基础设施（对应 US1 / SC-001）

```bash
cp .env.example .env
docker compose up -d            # 期望：postgres 容器 healthy
docker compose ps               # 期望：State=Up, Health=healthy
docker compose exec postgres psql -U personalos -d personal_ai_os -c "SELECT 1"
```

> **注意**：此时 `\dx` **还看不到** `vector` 扩展 —— 扩展由后端的 Flyway `V1` 迁移创建，
> 不是由 compose 创建。要看到扩展必须先跑过后端（见场景 B）。这是本文件初稿的一处逻辑错误，已修正。

## 场景 B — 后端（对应 US2 / SC-002 / SC-004）

```bash
cd backend
./mvnw -B verify                # 期望：BUILD SUCCESS，测试全绿
./mvnw spring-boot:run          # 另开终端
curl -s http://localhost:8080/api/v1/health
curl -s http://localhost:8080/actuator/health
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/api/v1/definitely-not-exist
docker compose exec postgres psql -U personalos -d personal_ai_os -c '\dx'   # 现在应看到 vector/hstore/uuid-ossp
```

**验证点**：
- `./mvnw -B verify` 退出码 0，且包含 Testcontainers 集成测试（真实 PostgreSQL + Flyway 迁移）。
- `/api/v1/health` 返回 `code=0` 且 `data.status=UP`。
- 404 响应为统一结构且 `code=40004`，响应体不含 `Exception` / 类名 / SQL。
- 对比：Flyway 在容器内创建了 `vector` 扩展 —— `psql -c "\dx"` 可见。

## 场景 C — 前端（对应 US3 / SC-003）

```bash
cd frontend
npm install
npm run build                   # 期望：产物写入 dist/，无类型错误
npm run dev                     # 另开终端，访问 http://localhost:5173
```

**验证点**：
- 首页展示后端返回的健康状态（`UP`）与版本号。
- 关闭后端后刷新页面，出现可读错误提示而非白屏。

## 场景 D — 仓库卫生（对应 US4 / SC-005 / SC-006）

```bash
git ls-files | grep -c .                             # 文件数应为预期量级
git check-ignore -v .env backend/target frontend/node_modules .workbuddy
git grep -nE "AKID[A-Za-z0-9]{8,}|BEGIN.*PRIVATE KEY|password\s*[:=]" -- . || echo "无泄漏"
ls backend/src/main/java/com/personalos/{common,ai,agent,chat,task,knowledge,calendar,memory}
```

## 验证记录表

> 全部为 2026-10-14 本机**实际执行**的结果。

| 场景 | 命令 | 结果 | 证据 |
|---|---|---|---|
| A-基础设施 | `docker compose up -d` + `psql SELECT 1` | ✅ | 4 秒转 `healthy`；`SELECT 1` 返回 1 |
| A-扩展存在 | `psql -c "\dx"` | ✅ | 迁移**前**仅 `plpgsql`；迁移**后**为 `vector 0.8.7` / `hstore 1.8` / `uuid-ossp 1.1` |
| B-构建测试 | `./mvnw -B verify` | ✅ | `Tests run: 10, Failures: 0, Errors: 0`，`BUILD SUCCESS`（含 Testcontainers 集成测试，PostgreSQL 17.11） |
| B-健康检查 | `curl /api/v1/health` | ✅ | HTTP 200；`{"code":0,...,"status":"UP","version":"0.1.0-SNAPSHOT"}`，恰好 4 个字段 |
| B-404 统一格式 | `curl /api/v1/definitely-not-exist` | ✅ | HTTP 404；`{"code":40004,"message":"资源不存在","data":null,...}`，无堆栈/类名 |
| B-框架健康 | `curl /actuator/health` | ✅ | 整体 `UP`，含 `db` 组件 `UP`（PostgreSQL） |
| B-可执行 jar | `java -jar target/*.jar` | ✅ | 55.9 MB 可执行 jar，1.9s 内启动，优雅停机无残留进程 |
| C-前端类型检查 | `npm run type-check` | ✅ | 退出码 0（须 TS 5.9.3；TS 7 会崩，见 research R-12） |
| C-前端构建 | `npm run build` | ✅ | 1658 模块，产出 `dist/`；主包 1.01 MB 触发 chunk 告警（R-08 已知代价） |
| C-前后端联通 | 浏览器首页 | ⏳ | **未在真实浏览器打开**（当前无 GUI 环境）。同一 API 已用 curl 验证，Vite 代理配置已就位；建议用户首次 `npm run dev` 时人工确认 |
| D-仓库卫生 | `git check-ignore -q` / 密钥扫描 | ✅ | `.env` 被忽略、`.env.example` 正常入库；密钥扫描零命中；无构建产物混入（92 文件 / 696K） |

**未验证项**：`C-前后端联通` 的浏览器实测。除该项外，spec 的 SC-001 ~ SC-007 均有实测证据。
