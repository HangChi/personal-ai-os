# ============================================================
# 后端镜像
#
# 多阶段：构建阶段有 Maven 与 JDK，运行阶段只要 JRE ——
# 镜像从约 800MB 降到约 300MB，更重要的是运行环境里没有编译器和源码。
#
# 构建（在仓库根目录执行）：
#   docker build -f backend/Dockerfile -t personal-ai-os-backend ./backend
#
# ⚠️ 本项目的定位是**本地自托管**，部署不在原始范围内。
#    这个镜像用于「换一台机器跑起来」和「给家人/同事用」，不是公网多租户方案。
# ============================================================

# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 先只拷 pom 再拉依赖：源码改动不会让这一层失效。
# 这是 Docker 分层缓存最有价值的一处 —— 否则每次改一行 Java 都要重下全部依赖。
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# 跳过测试：CI 已经跑过（.github/workflows/ci.yml），
# 在镜像里再跑一遍只会让构建慢几分钟，且容器内没有 Docker（Testcontainers 用不了）
RUN mvn -B -q package -DskipTests

# ---------- 运行阶段 ----------
FROM eclipse-temurin:21-jre

WORKDIR /app

# 非 root 运行：容器逃逸的门槛因此高出一档，成本只有一行
RUN useradd --system --uid 1001 --create-home app

# 只拷 fat jar。注意 `*.jar` 不会匹配 `.jar.original`（那是 repackage 留下的瘦包），
# 所以这里不需要额外的排除规则
COPY --from=build /build/target/*.jar app.jar
RUN chown -R app:app /app

USER app

# 时区固定 UTC：与数据库、日志一致，避免「同一个事件在两个地方显示不同日期」
ENV TZ=UTC \
    SERVER_PORT=8080

EXPOSE 8080

# MaxRAMPercentage 而不是固定 -Xmx：容器内存限制变化时不必改 Dockerfile。
# 75% 是给堆外（Netty/线程栈/直接内存）留出余量。
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
