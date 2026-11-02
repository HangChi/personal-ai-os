# 验证记录（2026-11-02）

阶段分别提交，未部署生产环境。

- 监控：真实独立管理端口可采集 Prometheus；主端口认证与管理端口拒绝业务/配置接口通过。
- 后台执行：真实 HTTP 验证浏览器断开不取消模型执行、持久化 SSE 游标重放；许可持有至停止/结束，幂等重试不额外占用；增量合并保序。
- 缓存：默认观测仍调用模型；开启后批量顺序与选项隔离、向量副本、失败不缓存通过；导入及 RAG 回归通过。没有使用合成测试的重复率声称生产收益，默认关闭。
- 后台任务：真实 multipart 返回 202 后进度可重读；同键内容校验、并发领取、崩溃租约恢复、执行中失去租约、替换旧内容保留、索引/回执原子回滚与三次上限通过。
- 后端全量 `./backend/mvnw -o -B --no-transfer-progress -f backend/pom.xml verify`：1089 tests，0 failures / 0 errors；覆盖率 LINE 86.09% / BRANCH 70.97%，门禁通过，BUILD SUCCESS。
- 全量后补充「存在索引任务时拒绝恢复」边界，独立重跑 DataImportIntegrationTest：10 tests，全部通过。
- 前端 lint / format:check / type-check 通过；逻辑断言 1133 项通过；26 个组件测试文件、165 项通过；生产构建通过。
- 生产 Compose + 监控叠加 `config --no-env-resolution --quiet` 校验通过；生成组件声明 ElProgress 已同步。

仍存在此前已知的 Surefire JVM 退出等待 30 秒提示；本轮全量测试均完成，Maven 最终成功。
监控容器和 Grafana 看板未实际启动部署，不将配置校验等同于生产运行验证。
