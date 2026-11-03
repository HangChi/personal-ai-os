# 认证迁移边界

`Service` 对齐固定 Java 的 `AuthProperties`/`AuthService`：默认用户名 admin、BCrypt 2a/cost10、Java 空白规则、null/空输入区别和连续失败锁定。构造请从 `DefaultOptions()` 开始；关闭认证仍检查失败阈值和锁定时长。密码由调用方提供，Service 不保存 Options 或明文，只保留私有哈希，格式化不输出凭据。

`Verify` 的用户名和密码用 `*string` 表达 Java null。锁定阈值那次返回失败，下一次非 null 校验返回 `*LockedError`，HTTP 层应转为 42900。用户名错误仍检查密码。互斥锁将检查与计数串行化，避免并发越过阈值；锁定状态仍为每进程，与 Java 一致。Go `time.Duration` 的可表示范围小于 Java，绑定层不得静默溢出。

`testdata/java-bcrypt.json` 来自实际执行基线 BOM 的 Spring Security 7.1.1 编码器，共 74 项。固定盐和其中密码均为测试数据，运行时使用 BCrypt 的安全随机盐。特别保留生成哈希拒绝 >72 字节、校验时允许长输入的原行为；不自行截断或正规化 Unicode。可离线复验：

```bash
python3 scripts/migration/java_bcrypt_fixture.py
```

`MemoryStore` 提供单进程会话，默认闲置 30 分钟，读操作续期。`SessionStore` 的四个原子操作避免 ID 冲突时误删旧会话、首次 CSRF 并发改写、注销后的旧请求重新保存身份。它不用于替代共享 Redis。

`HTTP.Handler(next)`是已接入显式Foundation的可组合安全入口，默认离线诊断仍独立：

- 顺序为路径检查 → 会话读取 → CSRF → 认证 → 认证控制器/业务处理器。
- 支持 `/api/v1/auth/login`、`logout`、`me`、`csrf`；me 的匿名结果仍是 200 + username:null。开启认证时，公共白名单以外的全部路径都要求身份。
- 登录换掉会话 ID 并丢弃原 CSRF，注销删除服务端状态和客户端 Cookie。Cookie 为 HttpOnly、SameSite Lax 和可配置 Secure；本地名称 JSESSIONID，共享存储调用方使用 SESSION。
- CSRF 与认证开关独立，支持头部和 `_csrf` 参数/表单；`/mcp` 及子路径只免 CSRF。下游身份通过 `CurrentUsername(ctx)` 获取，不使用全局安全上下文。
- JSON/表单读取上限 10 MiB。重复 Cookie 与歧义路径明确拒绝。完整媒体类型、字段强制转换、校验错误排序与 Servlet 防火墙差分仍待 T106。

`RedisStore` 使用调用方显式创建的专用 `redisclient.Client`，以 Lua 实现相同原子操作，读续期、首次 CSRF 保留原 TTL；故障向上传递，不回退内存。默认会话超时仍由调用方传入。命名空间强制附加 `:go:v1:session:`，不读取/改写 Spring Session 二进制值；切换至 Go 后需要重新登录。

真实回环HTTP/CookieJar验证登录/轮换/CSRF/注销；专用Redis验证两个HTTP实例和独立OS进程共享、TTL、并发、TLS/最小ACL与真实TCP分区恢复。脚本`python3 scripts/migration/run_go_redis_tests.py --tls --package app --package command`只连接自建临时容器，finally清理自身资源。基础应用接线已完成，完整配置、multipart正文token生命周期及剩余Servlet边界仍见[P1剩余项](../../../../specs/033-go-rewrite/p1-remaining.md)。诊断模式仍拒绝认证/生产开关，基础模式允许已实现认证/CSRF但生产继续拒绝；不代表T105或整个Java文件verified。
