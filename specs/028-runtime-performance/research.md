# 架构决策

1. Micrometer Prometheus registry 使用 Spring Boot BOM 管理版本，复用既有指标；选择可选独立管理端口，替代公开 Actuator 或让采集器保存用户登录会话。官方：https://docs.spring.io/spring-boot/reference/actuator/metrics.html
2. Prometheus/Grafana 作为生产 Compose 的可选叠加，独立数据卷、15 天/2GB 保留限制；Grafana 仅回环地址，采集端口不发布。镜像固定为官方已发布版本 v3.14.0 / 13.2.2（https://prometheus.io/download/ ，https://grafana.com/grafana/download）。
3. 后台任务复用 PostgreSQL，使用 SKIP LOCKED 短事务抢占与租约恢复，避免增加独立 broker 和跨资源提交：https://www.postgresql.org/docs/17/sql-select.html 。外部副作用无法保证 exactly-once，须由任务类型明确重试语义。
4. 缓存优先评估重复计算与命中率；单实例使用有界本地缓存，避免 Redis 额外服务。Caffeine 若落地则由 Boot BOM 管理，默认关闭，按模型身份隔离：https://github.com/ben-manes/caffeine/wiki/Eviction 。
