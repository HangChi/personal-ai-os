# 决策与依据

- Redis 使用 Boot BOM 的 Jedis，避免引入不启用时也检查外部服务的全局自动配置。二级缓存只保存摘要与向量，命名空间须在同一用户/模型配置范围内隔离。https://redis.io/docs/latest/commands/set/
- RabbitMQ 使用 Boot BOM 的 spring-rabbit，已有数据库任务为事实来源；消息作为唤醒信号，避免双写丢任务。生产者确认和持久投递不替代消费者幂等。https://docs.spring.io/spring-amqp/reference/amqp/template.html
- OpenSearch REST API 同时支持 CJK/BM25 与 kNN；应用层 RRF 避免不同分数直接相加。https://docs.opensearch.org/latest/vector-search/ai-search/hybrid-search/index/ ，https://docs.opensearch.org/latest/analyzers/language-analyzers/cjk/
- S3 使用 AWS SDK v2，支持 endpointOverride 和 path-style；本地兼容服务选择 SeaweedFS（4.51 官方发布），不依赖已变更社区分发方式的 MinIO。https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-s3.html ，https://github.com/seaweedfs/seaweedfs/releases/tag/4.51
- 重排兼容 Cohere results[index,relevance_score] 协议；模型/端点由用户配置，不默认发送到新的外部提供商。https://docs.cohere.com/reference/rerank
- 冲突属于证据与适用条件问题，相关性分数不代表事实真实性。入库模型输出必须带可在原片段验证的引用，人工结论保留审计。
