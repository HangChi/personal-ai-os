# 专用 Redis 连接边界

显式 `Config.URI` 必填，只支持 standalone `redis://` / `rediss://`，不读环境、`.env`、服务发现或全局连接。凭据解析保留原 Java 的 password-only、解码后 userinfo 冒号拆分；拒绝 query/fragment/非法端口和数据库。URI/根证书和连接对象的格式化不暴露凭据。

`rediss` 校验服务端名称/证书，最低 TLS 1.2；可显式提供 PEM 根证书，默认系统信任。不支持不校验证书或明文降级。真实 Redis TLS 已验证受信证书/主机名成功，未知 CA、错误主机名和明文访问 TLS 端口被拒。已实现的会话/模型桶最小 ACL 与精确丢响应已真实验收；完整生产网络分区和部署装配仍待验收。

每个客户端最多 4 个活跃连接。操作默认最多 2 秒，父 context 的较早 deadline 优先；禁用命令自动重试和客户端缓存，避免不确定写入被重放或会话读到旧值。库的连接建立仍使用其受限 dial 流程；该行为不等于业务操作重试。`Eval` 将 Lua nil/false 正常映射为 nil，其余错误保留 `errors.Is/As` 原因，对外格式化只给脱敏文案。调用方不得将 unwrap 后的驱动错误直接写入日志/HTTP。

创建者负责 `Close`（幂等），使用方 Store 不关闭调用方连接。共享会话、限流和其他未来缓存应各自显式选择专用连接与命名空间，不能静默复用已有应用 Redis。

`run_go_redis_tests.py` 使用固定 Redis 8.2.1 镜像摘要、新 tmpfs/随机密码/回环端口，禁止持久卷，不使用已有 Compose/.env；退出时只删除自己创建的容器。命令构建与普通单测不连接外部 Redis。

`python3 scripts/migration/run_go_redis_tests.py --tls` 生成临时 CA/独立服务端证书，在只开 TLS 的真实 Redis 上运行同一套客户端/会话/限流测试。CA 私钥不挂载 Redis，不改系统信任，退出时清理临时证书；脚本需要本机 OpenSSL/LibreSSL 和 Docker。

## 已实现功能的权限与故障证据

临时账号从 reset 开始，只授 AUTH/HELLO/PING/EVAL。会话额外需 GET/PTTL/PEXPIRE/EXISTS/SET/DEL 和自身 :go:v1:session:*；模型桶需 HMGET/TIME/HSET/PEXPIRE 和自身 SHA-256 单键。真实普通/TLS Redis 验证业务成功，管理命令、Java 会话键/其他键和 Lua 越界拒绝。这里不提供已有部署直接套用的全功能 ACL，未来缓存需独立审查命令和键。

response_loss_integration_test.go 以透明 TCP 代理在 EVAL 执行后丢响应：操作报错，服务端计数仅1，无透明重放；下一显式新操作可重连。测试不把错误解释为未写入。security_integration_test.go 证明 ACL 故障使会话 HTTP 500、模型429且计错，不回退。
