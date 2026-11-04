# Go 安全与切换边界

P8候选尚未获得生产发布批准。默认不读取`.env`；只有显式`GO_DOTENV_ENABLED=true`才允许加载，`APP_DOTENV_ENABLED=false`强制关闭。禁止输出原配置值、Cookie、模型密钥、Push订阅、MCP认证头、备份明文或包含这些内容的进程参数。日志/验收只记录脱敏结果。

## 认证与外部连接

启用单用户BCrypt认证、失败锁定、登录会话轮换、注销及CSRF；共享Redis会话与Java序列化隔离，切换后必须重新登录。默认Cookie为HttpOnly/SameSite=Lax；生产候选还要求Secure，不能通过关闭校验绕过prod守卫。MCP入口的精确CSRF豁免不豁免认证；外部写工具默认CONFIRM，只有明确原名只读名单可AUTO。

数据库默认verify-full，CA/主机名必须正确。会话、限流与缓存分别配置TLS/ACL；未知CA、错误主机名或写响应丢失时拒绝或按已记录的读缓存策略降级，不能静默转明文或重试未知写。MCP命令/URL、Webhook目的地和模型端点均是受信配置边界，不能把任意用户输入当启动指令。

附件读取核验UUID、路径/链接、类型和大小；multipart有界并在请求结束清理。容器非root、只读根、无capability、no-new-privileges；管理端口不经Web反代公开。发现文件公开目录只含地址和role，私有PID/日志保持0700目录/0600文件。

## 保留与备份

用量/操作记录默认永久保留，显式启用后按UTC严格小于截止时间批删；未知TOOL IN_PROGRESS继续保留。模型密钥沿用原AES-GCM格式，主密钥须独立安全保存；数据库和附件备份均不能恢复丢失的主密钥。默认加密备份，未加密仅可显式用于隔离合成夹具。

真实切换前暂停入口写入并停全部Worker，核查租约、Run、待确认、投递和外部未知结果，再备份数据库、附件/S3、配置与独立密钥。Go启动后重新登录并核对数据/附件、密文读取、幂等和待办；回退前同样先停Go。回退数据库不会撤销已发Push/Webhook或外部MCP写操作，未知结果需要人工核查。

当前残余：真实浏览器Push尚未确认送达；原E2E预算断言在固定Java与Go同样失败；saveMemory原case仍跳过；真实用户备份、生产入口TLS/续期和人工验收尚未完成。不能用合成控制通过、FCM接受或本地演练替代这些门禁。全部风险与有意差异见[风险记录](../../specs/033-go-rewrite/research.md)及[P8报告](../../specs/033-go-rewrite/p8-completion.md)。
