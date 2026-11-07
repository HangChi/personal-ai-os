# 数据库租约与 fencing

> 本页记录模块契约及分阶段验证范围。当前启动/模块组合见[Go 文档中心](../../../docs/README.md)，两版最新能力与差异见[双端说明](../../../../docs/backend-parity.md)；下文固定基线、测试数和 P1/P3 等范围不是当前全产品或生产批准。

对应固定 Java DatabaseCoordination 的 acquire/renew/Lease/close；consume 位于 ratelimit。显式 New(Pool) 才启动 10 秒续租循环，已接显式Foundation，默认诊断不创建协调器。使用 V37 原表和数据库时钟，租期 120 秒；获取/释放为独立短事务，空闲 slot 按顺序通过 SKIP LOCKED 领取，持有期间不占用连接。容量 1–100，资源长度按 Java UTF-16 单元限制为 160（空资源保留）。

Fence(ctx, Transaction) 必须在业务最终写事务中调用并处理错误，以所有者 UUID、未过期条件和行锁防止旧执行者写入；没有活动写事务、只读事务、结束事务和失效租约均拒绝。业务仓储使用 Pool.WithManagedTx 显式传递 Transaction，不能把 SQL 指针带出回调或自行提交/回滚。

事务外使用 Release(ctx)，事务内使用 ReleaseAfter(tx)。后者登记后本地立即不可用，但数据库所有权要等提交/回滚/panic 完成且连接归还之后才释放；完成回调使用独立 5 秒清理 context。释放幂等且带 owner 条件，旧所有者不会释放新所有者。失败不重试，数据库所有权自然到期。

任一续租错误或零更新撤销本地信任，之后续租成功也不会恢复。Close 停止续租、取消在途获取、等待归档后释放租约；独立清理最多 5 秒，不关闭调用方 Pool。应用应先停止请求/Worker 与最终写事务，再关闭协调器和 Pool。Foundation已验证进程退出装配、真实TLS网络分区/恢复、有效租约保持与恢复释放，以及COMMIT响应丢失不重试；持久业务Worker恢复按所属阶段验证。

`python3 scripts/migration/run_go_postgres_tests.py --package coordination` 创建专用临时容器并加载原 V37，验证共享容量、过期 fencing/旧 owner 释放隔离、只读/结束事务、续租失效不复活、提交/回滚/panic 延迟释放、32 路竞争、SKIP LOCKED、缺表故障、实际 10 秒续租与关闭。每个集成包使用独立容器，避免各包 DDL 相互污染；不用现有数据库。
