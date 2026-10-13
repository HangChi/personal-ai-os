# 本地开发指南

## 前置条件

| 组件 | 版本要求 | 说明 |
|---|---|---|
| JDK | 21 | `java -version` 应显示 21.x |
| Docker | 任意近期版本，含 `docker compose` | 用于运行 PostgreSQL + pgvector，以及后端集成测试 |
| Node.js | `^20.19.0 \|\| >=22.12.0` | Vite 8 的要求 |
| Maven | **无需预装** | 仓库自带 `backend/mvnw` |

## 一、启动基础设施（PostgreSQL + pgvector）

```bash
cp .env.example .env       # 首次执行
docker compose up -d
docker compose ps          # 期望 postgres 的 State=Up、Health=healthy
```

验证扩展已就绪：

```bash
docker compose exec postgres psql -U personalos -d personal_ai_os -c '\dx'
# 期望看到 vector / hstore / uuid-ossp / plpgsql
```

> **端口说明**：默认把容器内 5432 映射到宿主机的 **55432**，以避开本机常见的 5432/5433 占用。
> 如需修改，编辑 `.env` 中的 `POSTGRES_PORT`；本机 5432 空闲时也可以改回 5432。

停止（保留数据）：

```bash
docker compose down
```

连同数据卷一起清除（**会丢数据**）：

```bash
docker compose down -v
```

## 二、启动后端

```bash
cd backend
./mvnw spring-boot:run
```

后端默认监听 `http://localhost:8080`。自检：

```bash
curl -s http://localhost:8080/api/v1/health
# 期望：{"code":0,"message":"OK","data":{"status":"UP","application":"personal-ai-os","version":"0.1.0-SNAPSHOT","timestamp":"..."},"timestamp":"..."}

curl -s http://localhost:8080/actuator/health
# 期望：整体健康，含 db 组件
```

### 后端配置

配置项集中在 `backend/src/main/resources/application.yml`，全部可由同名环境变量覆盖：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `SERVER_PORT` | 8080 | 后端监听端口 |
| `POSTGRES_HOST` | localhost | 数据库主机 |
| `POSTGRES_PORT` | 55432 | 数据库宿主机端口 |
| `POSTGRES_DB` | personal_ai_os | 数据库名 |
| `POSTGRES_USER` | personalos | 用户名 |
| `POSTGRES_PASSWORD` | personalos | 密码（**仅本地默认值**） |

> `docker compose` 会自动读取根目录 `.env`；如需让后端也读取这些变量，
> 在 shell 中 `set -a && source .env && set +a` 后再启动，或直接使用默认值（默认值与 `.env.example` 一致）。

## 三、运行后端测试

```bash
cd backend
./mvnw -B verify
```

测试使用 **Testcontainers** 拉起与 `compose.yml` 相同的 `pgvector/pgvector:pg17` 镜像，
因此是自包含的（不依赖你手动启动的数据库），但**要求 Docker 正在运行**。

> ⚠️ **Docker 未启动时 `mvn test` 会失败，这是有意设计**：
> 迁移正确性是阶段 0 的核心主张，不应被静默跳过。
> 只跑编译可加 `-DskipTests`。

首次运行会拉取镜像与依赖，耗时较长；之后会走缓存。

## 四、启动前端

```bash
cd frontend
cp .env.example .env.local   # 可选
npm install
npm run dev                  # http://localhost:5173
```

首页会实时调用后端 `/api/v1/health` 并展示状态。
**后端未启动时，页面会给出可读的错误提示，而不是白屏。**

其他命令：

```bash
npm run type-check   # vue-tsc 类型检查
npm run build        # 生产构建，产物在 frontend/dist
npm run preview      # 预览构建产物
```

开发期 `/api` 由 Vite 代理到 `http://localhost:8080`（见 `vite.config.ts`），因此不需要配置 CORS。

## 五、常见问题

**Q：`docker compose up -d` 报端口被占用**
本机 5432/5433 可能已被其它 PostgreSQL 容器占用。改 `.env` 的 `POSTGRES_PORT` 为一个空闲端口（默认 55432 已避开）。

**Q：后端启动时报连不上数据库**
先确认 `docker compose ps` 中 postgres 是 healthy；再确认 `POSTGRES_PORT` 与 compose 映射的宿主机端口一致。

**Q：后端启动时报表结构校验失败**
`ddl-auto` 固定为 `validate`，结构必须由 Flyway 迁移创建。检查 `backend/src/main/resources/db/migration/` 下脚本是否完整、`flyway_schema_history` 是否与服务端一致。

**Q：改了 `pom.xml` 里的依赖，但迁移不再执行了**
请先读 `specs/001-project-skeleton/research.md` 的 **R-11**。Spring Boot 4 把许多自动配置拆到了独立模块（例如 Flyway 必须在 `spring-boot-flyway` / `spring-boot-starter-flyway` 中），**只加底层库不会报错，但功能静默失效**。

**Q：`mvn` 命令找不到**
用仓库自带的 `./mvnw`（它会自动下载并使用 Maven 3.9.16），无需在本机安装 Maven。

**Q：应用启动后监听的不是 8080**
环境变量的优先级高于 `application.yml`。若 shell 中已导出 `SERVER_PORT`（某些 IDE、容器或 shell 配置会这么做），它会覆盖配置。用启动参数可强制覆盖：
`./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=8080`，或直接启动 jar 时加 `--server.port=8080`。
启动日志里的 `Tomcat started on port xxxxx` 是权威结论。

## 六、数据库结构变更流程

1. 在 `backend/src/main/resources/db/migration/` 新增脚本，命名 `V<n>__<snake_case>.sql`。
2. **不要**改已有迁移脚本（Flyway 会校验 checksum）。需要修正时新增一个更高版本的脚本。
3. 脚本应尽量幂等（`CREATE ... IF NOT EXISTS`）。
4. 跑一次 `./mvnw -B verify` 验证在干净库上可完整重放。
