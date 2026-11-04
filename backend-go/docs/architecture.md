# Go 架构与接口

`cmd/personalos`解析显式环境和模块开关，`internal/app`组合生命周期、HTTP与业务服务；`internal/platform`负责配置、安全链、数据库、协调和观测，`internal/business`承载业务。保持原Vue前端、统一响应信封/错误码、HTTP/SSE及MCP契约，具体104路由/27工具/95钩子见[来源索引](../../specs/033-go-rewrite/surface.md)，兼容要求见[contracts.md](../../specs/033-go-rewrite/contracts.md)。

## 启动与角色

无开关时是离线诊断，不连接数据库或启动Worker。`--foundation`接入基础及P2–P4；`--p5`接记忆/日历/投递，`--p6`包含P5并接知识，`--p7`包含P6并接MCP/搜索/导入导出，`--p8`包含P7并接保留清理。所有模式默认回环；只有显式`--foundation --p8 --container`且认证/CSRF开启才允许容器监听。

| 角色 | 执行职责 |
| --- | --- |
| combined | API及全部已启用Worker |
| api | HTTP/聊天/入队，后台任务交给其他角色 |
| index | 知识索引领取、续租与原子提交 |
| review | 来源审查领取与版本核验 |
| delivery | 提醒/Push/Webhook、知识投影及唤醒 |

Worker业务端口拒绝API；管理端口独立。`/livez`只证明响应；`/readyz`仍为503，prod仍在启动前拒绝。管理health用于候选设施探测，不解除发布门禁。角色本身不自动载入worker profile，需显式profile才能使用对应池大小。

## 数据与恢复

PostgreSQL是任务、会话、Run、确认、幂等、索引与投递的事实来源；原42条迁移由独立Java Flyway执行，Go只读校验历史和脚本SHA/CRC。池与事务显式传递context；使用数据库时钟、原子领取、租约和fencing阻止陈旧执行器提交。确认占位先独立提交，再执行处理器并持久化最终状态。

幂等区分HTTP与TOOL。外部写结果不明时保留IN_PROGRESS，停止自动重放，不能用过期清理解除保护。legacy SSE断连取消上游且不落半截助手消息；持久Run读者断开后继续后台，显式停止才取消。事件游标/数据库状态支持重连，内存Hub仅加速通知。

知识索引先在事务外读入/嵌入，再在短事务校验租约与版本并替换分块/向量。检索合并向量与关键词/RRF，聊天和问答共享保守RAG。Redis候选缓存可失效/降级，OpenSearch投影、Rabbit唤醒均不替代数据库。来源冲突、证据不足与过期引用必须按原策略拒答或澄清。

共享会话、限流、嵌入与候选缓存分别使用独立Redis客户端/命名空间/ACL。S3对象与本地附件使用实际后端，不以数据库dump代替二进制备份；导出JSON主要用于业务交换，不能代替完整恢复。

## 可追溯性

P1–P7分阶段验收，P8实测与残余失败见[P8报告](../../specs/033-go-rewrite/p8-completion.md)。`progress.json`逐项记录实现证据，五个release_gates单独审核。根项目宪法和历史Java文档保留，Go技术迁移依据仍是[plan.md](../../specs/033-go-rewrite/plan.md)，不通过改写历史来宣告切换完成。
