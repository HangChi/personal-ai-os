# 技术决策

## Web Push

Decision：使用 nl.martijndwars:web-push:5.1.2 实现 VAPID 与负载加密，避免自行实现密码协议；HTTP 发送采用 JDK HttpClient（连接/请求超时、禁止重定向），持久化发送状态，无消息队列。

官方库说明：https://github.com/web-push-libs/webpush-java （5.1.2；BouncyCastle provider）。

Alternatives considered：自行实现 RFC8291（密码协议维护成本高）；仅前台通知（页面关闭无法送达）；引入外部通知 SaaS（额外依赖和账户要求）。

## 备份

使用 Python 标准库编排 Docker/pg_dump 和文件 SHA-256；可选使用系统 age 公钥加密。不新增 Python 运行依赖，不把数据库密钥写入命令行或备份元数据。数据库和附件一致性通过备份窗口暂停写入保证；脚本不擅自停服务。

推送库原始 POM 的 HTTP/CLI 依赖不使用，全部排除；显式引入 Maven Central 已发布的 bcprov-jdk18on:1.86、jose4j:0.9.7，避免旧版隐式依赖。二者负责曲线加密与 JWT 签名，替代方案为自己实现加密/签名（不采用）。

## 模型密钥静态加密

Decision：JDK AES-256-GCM，随机 12 字节 IV、128 位认证标签、固定领域 AAD；Hibernate AttributeConverter 保持 DTO/运行时用法兼容。主密钥由环境注入，默认留空，不重写现有配置；启用后重新保存配置即可逐项转换。

Alternatives considered：强制引入外部 Secret Manager（本地部署成本高）；自建数据库密钥表（与密文同库不解决备份泄漏问题）。只扩展字段长度，旧迁移不改动。
