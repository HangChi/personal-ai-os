# Go 开发与贡献

Go候选与Java基线共存，固定来源为`cb5d694d62fa93742b5789b97273e76d931ce335`。本阶段修改范围是`backend-go/`、`scripts/migration/`、`specs/033-go-rewrite/`和独立Go工作流；原Java、前端业务代码、42条SQL和原部署资产保持不变。阶段记录及代码按可审查的小批次提交，不把阶段完成等同于发布批准。

## 工具与验证

模块最低Go1.27.0，本地验证使用1.27.1。race需要C工具链；PDF需要Poppler的`pdfinfo`/`pdftotext`。基础测试不需要应用凭据：

```bash
cd backend-go
go mod download
go mod verify
go test -race -p 1 -count=1 -timeout=20m ./...
go vet -tags=integration ./...
go build ./...
```

真实设施测试从仓库根目录调用下列脚本。它们创建随机名称/回环端口/合成凭据的临时资源，并在finally只清理自身资源；禁止把既有服务地址或用户备份替换进夹具。

```bash
python3 -m unittest discover -s scripts/migration -p 'test_*.py'
python3 scripts/migration/go_inventory.py check
python3 scripts/migration/go_config_disposition.py
python3 scripts/migration/run_go_postgres_tests.py --tls
python3 scripts/migration/run_go_redis_tests.py --tls --backup
python3 scripts/migration/run_go_s3_tests.py
python3 scripts/migration/compare_go_p6.py
python3 scripts/migration/run_go_demo_seed_tests.py
```

设施runner默认复用本机既有离线缓存。Linux/CI必须显式设置绝对路径`GO_TEST_GOCACHE`与`GO_TEST_GOPATH`，先用对应`GOCACHE`/`GOPATH`运行`go mod download`；子进程固定`GOPROXY=off`、`GOTOOLCHAIN=local`。Java Flyway oracle需要JDK21及原Maven依赖缓存，先在backend执行`./mvnw -B -DskipTests -Dapp.dotenv.enabled=false package`。不打印环境、Compose展开结果或带凭据的进程参数。

独立[Go CI](../../.github/workflows/go.yml)执行全包race、integration vet、build、Python脚本/不可变基线检查和真实TLS task/confirmation/P8回归，保留失败日志。它不替代原Java/前端CI、真实浏览器、四设施故障、切换演练和人工发布审查；尚未经远端GitHub运行的配置不能声称远端通过。

演示种子验证复用固定基线`scripts/dev/seed-demo-data.py`的七组数据配方，只替换HTTP/SQL边界。runner自行创建随机TLS库和认证Go进程，校验容器标签/名称/回环端口及业务表为空，禁止原main/reset，没有现有目标参数；用完自动停止并清理。真实回读任务状态/标签、全天/重复日程、记忆置顶、Markdown文档/向量、会话消息、31天合成用量和审计结果。原脚本默认清空现有业务表，不能直接用来验证Go，也不能把本测试当用户库/生产模型/浏览器验收。

## 变更规则

从固定Java断言推导Go期望值，不能从Go实现生成自己的期望。原Java测试文件及1119个声明逐项记账；补充真实副作用、事务/查询数、失败/取消与并发证据。跳过、观察项和同Java基线失败都必须保留，不能当成PASS。

依赖固定版本并更新`THIRD_PARTY_NOTICES.md`；新增配置要补来源、默认值、支持/拒绝及脱敏测试。使用实际事务和租约，不通过重试未知写结果掩盖故障。测试先确认可重复的独立数据边界，禁止全库清理。完整完成检查为`go_inventory.py check-complete`，未verified项或任一未通过发布门禁必须使其失败。

架构与接口见[architecture.md](architecture.md)，安全规则见[security.md](security.md)，本地/Compose/监控/备份运维见[部署说明](../deploy/README.md)。
