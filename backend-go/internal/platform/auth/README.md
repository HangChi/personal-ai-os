# 认证迁移边界

`Service` 对齐固定 Java 的 `AuthProperties`/`AuthService`：默认用户名 admin、BCrypt 2a/cost10、Java 空白规则、null/空输入区别和连续失败锁定。构造请从 `DefaultOptions()` 开始；关闭认证仍检查失败阈值和锁定时长。密码由调用方提供，Service 不保存 Options 或明文，只保留私有哈希，格式化不输出凭据。

`Verify` 的用户名和密码用 `*string` 表达 Java null。锁定阈值那次返回失败，下一次非 null 校验返回 `*LockedError`，HTTP 层应转为 42900。用户名错误仍检查密码。互斥锁将检查与计数串行化，避免并发越过阈值；锁定状态仍为每进程，与 Java 一致。Go `time.Duration` 的可表示范围小于 Java，绑定层不得静默溢出。

`testdata/java-bcrypt.json` 来自实际执行基线 BOM 的 Spring Security 7.1.1 编码器，共 74 项。固定盐和其中密码均为测试数据，运行时使用 BCrypt 的安全随机盐。特别保留生成哈希拒绝 >72 字节、校验时允许长输入的原行为；不自行截断或正规化 Unicode。可离线复验：

```bash
python3 scripts/migration/java_bcrypt_fixture.py
```

当前尚未实现会话/Cookie/CSRF/共享 Redis 或应用接线；诊断应用继续拒绝启用认证/生产模式。基础凭据测试通过不代表 T105 或整个 Java 文件已 verified。
