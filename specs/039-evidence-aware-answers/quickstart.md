# 隔离验证

不读取 .env、不连接真实业务服务；双端模型由固定 JSON 桩提供。

1. backend：`APP_DOTENV_ENABLED=false ./mvnw -B --no-transfer-progress -Dapp.dotenv.enabled=false verify`。
2. backend-go：`go test ./...`，受影响包补充 race。
3. 使用 scripts/migration/run_go_postgres_tests.py 选择相关包，只创建和清理脚本自己的隔离容器。
4. 前端 lint、format:check、type-check、test:logic、test:component、build。
5. `node scripts/ui/browser-checks.mjs /tmp/personalos-039-ui --chat-sources-only`，检查对照、键盘开关、恢复和窄屏。
6. 迁移派生检查保持候选 in_progress；53份冻结文件/5项门禁与起点一致，check-complete 仍应拒绝未完成迁移。
