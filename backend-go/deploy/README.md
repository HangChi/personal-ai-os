# Go 候选部署

此目录与原 Java Compose、卷和运行实例独立。`--foundation --p8 --container`要求认证与CSRF，开放容器内部业务8081/管理9091；宿主只发布回环Web端口18088。`readyz=503`和prod拒绝仍有效，不能作为生产切换批准。

## 镜像与迁移执行权

从仓库根目录执行：

```bash
docker build -f backend-go/Dockerfile -t personal-ai-os-backend-go:candidate .
docker build -f backend-go/deploy/Dockerfile.web -t personal-ai-os-web-go:candidate .
docker compose --env-file /dev/null -f backend-go/deploy/compose.yml config --quiet
```

使用系统环境显式注入`GO_AUTH_USERNAME`、`GO_AUTH_PASSWORD`、`GO_POSTGRES_DB`、`GO_POSTGRES_USER`、`GO_POSTGRES_PASSWORD`、`GO_MODEL_KEY_ENCRYPTION_KEY`。不把Compose渲染输出写到公开日志，因为环境展开后包含凭据。密文解密须沿用原模型密钥；迁移测试只使用随机合成密钥。

`GO_POSTGRES_TLS_DIR`仅包含服务端`server.crt/server.key`；`GO_POSTGRES_CA`指向CA证书。CA私钥不得挂载。证书SAN必须包含Compose内的`postgres`名称。PostgreSQL启动时复制服务端材料到临时目录并收紧密钥权限；Go和迁移器都使用`verify-full`，不能为解决连接问题关闭校验。

一次性`migrate`服务使用Flyway12.4.0，先migrate再validate，禁止自动baseline。该固定官方镜像只有amd64，已显式声明platform，ARM宿主需Docker仿真支持；不能静默改版本/历史执行器。Go镜像只读校验原42条SQL/history，绝不执行repair或伪造历史。首次启动前人工核对独立项目名（默认`personal-ai-os-go-candidate`）及新卷；禁止让Java与Go同时写同一库。

镜像UID/GID10001、只读根文件系统、cap-drop ALL、no-new-privileges；可写范围仅临时目录和附件卷。Poppler来自Debian并保留发行版许可证。容器健康检查查询私有管理health；Worker业务端口对API/livez/readyz均404，不能以此判Worker死亡。

## 分布角色与生产候选

额外叠加`compose.distributed.yml`后入口为api，另有index/review/delivery。Worker profile保留4连接池；API默认10。API共享会话须显式`GO_REDIS_SESSION_URI`（rediss）和`GO_REDIS_SESSION_NAMESPACE`；自签CA还需为该服务单独挂载证书并设置`GO_REDIS_SESSION_ROOT_CERT`。不复用会话、限流、嵌入和候选缓存的客户端/ACL。

共享会话使用默认SESSION/HttpOnly/SameSite=Lax，不接受Servlet专属Cookie覆盖，基础Compose不设置这些冗余项。`compose.monitoring.yml`默认用于combined；分布角色另设`GO_PROMETHEUS_CONFIG=prometheus.yml`，以DNS发现三个Worker。Grafana显式关闭插件预安装/自动更新，固定镜像内插件随镜像审查升级；不依赖启动时下载。

`compose.prod.yml`提供安全Cookie、TLS Caddy及持久证书卷的候选定义，要求Compose2.24.4+；`GO_PUBLIC_HOST`必须是真实受控域名，公网发布还须显式`GO_PUBLIC_BIND`/80/443映射。默认仍回环18443/18080。`GO_HTTPS_PORT`同时用于宿主映射和显式308跳转；公网443入口应设为443，避免自动跳转丢失外部端口。当前Go拒绝prod，因此该叠加不能直接启动成功，更不能据此勾选生产验收。TLS证书卷须允许UID10001写入；不得通过改为root解决权限问题。ACME/DNS/公网续期和实际入口需在发布验收另行验证。

`python3 scripts/migration/run_go_compose_tests.py --https`仅创建随机项目/端口/新卷。它原生转换候选Caddyfile，保留HTTP配置，只将签发器/续期时钟替换为90秒测试私有证书；后端明确使用monitoring测试profile，未绕过prod守卫。CA只加载到本次Python客户端，不安装系统信任。实际验证未知CA/错误域名拒绝、外部端口跳转、安全头/Secure Cookie/认证CSRF、SPA与上传下载、非root/只读、自动续期及重启后证书/会话保留，最后清理本次资源。该演练不能代表公网ACME或生产批准，报告见[p8-https.json](../../specs/033-go-rewrite/p8-https.json)。

## 运维与复验

本地二进制用`python3 scripts/migration/go_runtime.py start --binary /absolute/personalos --role combined`；只读取显式系统环境，强制关闭dotenv，要求认证/CSRF和全部连接参数。`--role api`与`--role workers`支持独立角色；`status/stop/targets`只使用`.cache/go-runtime`中本工具创建的状态，不读Java PID或进程参数。状态目录0700、日志/状态0600；停止核对PID启动身份，超时保留状态且不强杀。默认端口18081/18091，三个Worker分别18083/18093、18084/18094、18085/18095。监控发现文件使用当前运行的Worker，不加入已停止进程。

容器监控不要挂载私有状态目录。给每次start/stop/targets命令传相同的`--discovery-dir /absolute/separate-public-directory`，原子导出仅含地址/role的0644 JSON，独立目录0755；原PID/日志权限不变。原生Prometheus使用默认回环地址；macOS Docker Desktop另传`--discovery-host host.docker.internal`，挂载公开目录至`/etc/prometheus/discovery:ro`并使用`monitoring/prometheus.local.yml`。本地配置只采集Worker；API管理端口按实际部署单独配置。仅验证macOS Docker Desktop可达宿主回环，不假设Linux容器能够如此访问；Linux优先原生Prometheus/显式审核的网络方案。进程异常退出后执行targets刷新，不把静态文件视为健康探测。

Push密钥继续使用`python3 scripts/dev/generate-push-keys.py /absolute/new-file`生成0600新文件，不能覆盖既有密钥；终端只显示路径。原数据库/附件备份格式和S3归属校验保留，备份必须暂停入口写入及全部Worker，数据库dump不能代替附件、S3对象或模型加密密钥。密钥应另行安全备份，不能混入公开验收产物。

真实镜像五角色回归使用runner新建的TLS PostgreSQL/pgvector，不发布应用端口，不读取现有.env：

```bash
python3 scripts/migration/run_go_postgres_tests.py --package app --tls --run '^TestP8' \
  --p8-image personal-ai-os-backend-go:candidate
python3 -m unittest discover -s scripts/migration -p 'test_go_*.py'
```

已执行与未执行结果见[P8进度](../../specs/033-go-rewrite/p8-completion.md)。切换需停止入口写入和全部角色、备份与核查待办任务/外部副作用、部署、重新登录、数据及附件验证。回退前先停Go；不复用Spring会话，不盲重放未知TOOL，不承诺撤销已发送Push/Webhook或外部MCP写操作。
