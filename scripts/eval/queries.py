#!/usr/bin/env python3
"""RAG 检索评测的查询集（2026-10-26 第二版：26 篇语料 / 115 条查询）。

## 三档难度

| level | 含义 | 举例 |
|---|---|---|
| `term` | 查询直接用了术语，与文档有明确词面重叠 | 「BRIN 索引适合什么场景」 |
| `natural` | 自然问句，措辞与文档不同但共享关键词 | 「索引在什么情况下会失效」 |
| `cross` | **刻意避开技术术语**，用意图/现象描述；与文档几乎没有词面重叠 | 「日志表按时间顺序写入，该建什么索引」 |

`cross` 占比最高，因为它是真正测语义检索的一档：词袋式实现能过 `term`、过不了 `cross`。
只看 `term` 的分数会严重高估检索能力。

## 标注

`docs` 是**可接受文档列表**。同主题两篇（MySQL/Go/HTTP/Docker/浏览器/Kafka/Python/
TypeScript/分布式/DP/Nginx 各 2 篇）内容有重叠，凡两篇都能回答的查询都标了两篇 ——
只标一篇会把「答对了但命中的是另一篇」判成失败，那不是检索的问题。

**双标注前逐篇 grep 核实过关键词归属**，不是凭印象加。

## 编写原则

- 查询必须**能在这篇文档里找到答案**（写之前逐篇看过正文与章节）。
- 不抄标题：抄标题只能测字面匹配。
- 每篇文档 3~8 条，避免某一篇主导总分。
"""

# ============ 第一批语料（2026-10-25）============
PG = "PostgreSQL 索引：从 B-tree 到 BRIN"
VUE = "Vue3 响应式原理：从 Proxy 到 EffectScope"
VT = "Java 虚拟线程：让 Java 轻功水上漂"
REDIS = "Redis 缓存机制"
HTTP1 = "HTTP 一篇说清楚"
MYSQL_TX = "MySQL 事务、索引与锁"
GMP = "GMP 调度器：Go 并发的心脏"
GO_PATTERN = "Go 并发模式选择指南"
HTTP_GRAD = "HTTP 渐进：编码、缓存与断点续传"
MYSQL_LEARN = "MySQL 学习记：从增删改查到慢查询"

# ============ 第二批语料（2026-10-26）============
DOCKER_NS = "Docker 与虚拟机的区别：Namespace 和 Cgroup"
DOCKER_UFS = "Docker 深度解析：UnionFS 与核心组件"
RENDER_PIPE = "浏览器渲染管道：重排、重绘与合成层"
RENDER_INPUT = "浏览器渲染原理：从输入到像素"
KAFKA_ARCH = "Kafka 实战总结：架构与吞吐优化"
KAFKA_FAST = "Kafka 为什么快：PageCache、零拷贝与 Segment"
PY_GIL = "Python 并发选型：GIL、多线程、多进程与协程"
PY_ASYNC = "Python 异步陷阱：谁在帮你并发"
TS_GENERIC = "TypeScript 泛型与条件类型"
TS_TRICKS = "TypeScript 类型体操技巧"
DIST_CAP = "分布式一致性：CAP、Raft 与脑裂"
DIST_SYS = "系统性地理解分布式系统"
DP_FIVE = "动态规划五部曲实战"
DP_BASIC = "动态规划基础与核心思想"
NGINX_CONF = "Nginx 完整配置参考手册"
NGINX_PROD = "Nginx 生产级配置优化"


QUERIES = [
    # ================= PostgreSQL 索引 =================
    {"q": "BRIN 索引适合什么场景", "docs": [PG], "level": "term"},
    {"q": "索引在什么情况下会失效", "docs": [PG, MYSQL_TX, MYSQL_LEARN], "level": "natural"},
    {"q": "什么时候该用 GIN 而不是 B-tree", "docs": [PG], "level": "natural"},
    {"q": "日志表按时间顺序不断写入、想用很小的索引先筛掉一批数据块，该建哪种索引",
     "docs": [PG], "level": "cross"},
    {"q": "数组字段要支持「包含」查询怎么建索引", "docs": [PG], "level": "cross"},
    {"q": "Hash 索引有哪些限制", "docs": [PG], "level": "natural"},
    {"q": "只给未完成的订单建索引，能做到吗", "docs": [PG], "level": "cross"},

    # ================= Vue3 响应式 =================
    {"q": "Vue3 的 computed 是怎么做缓存的", "docs": [VUE], "level": "natural"},
    {"q": "为什么 Vue3 放弃了 Object.defineProperty", "docs": [VUE], "level": "term"},
    {"q": "ref 和 reactive 该用哪个", "docs": [VUE], "level": "natural"},
    {"q": "组件卸载后副作用没清掉，Vue3 有什么办法管理副作用作用域",
     "docs": [VUE], "level": "cross"},
    {"q": "watch 的回调什么时候执行，能配置吗", "docs": [VUE], "level": "cross"},

    # ================= Java 虚拟线程 =================
    {"q": "虚拟线程有哪些坑", "docs": [VT], "level": "natural"},
    {"q": "在 synchronized 块里等 IO 会发生什么", "docs": [VT], "level": "cross"},
    {"q": "纯计算任务适合用虚拟线程吗", "docs": [VT], "level": "cross"},
    {"q": "要不要给虚拟线程建线程池", "docs": [VT], "level": "cross"},
    {"q": "ThreadLocal 在虚拟线程下有什么风险", "docs": [VT], "level": "natural"},
    {"q": "虚拟线程和平台线程有什么区别", "docs": [VT], "level": "term"},

    # ================= Redis =================
    {"q": "缓存雪崩是什么原因造成的", "docs": [REDIS], "level": "natural"},
    {"q": "Redis 为什么比直接查数据库快", "docs": [REDIS], "level": "natural"},
    {"q": "缓存击穿和雪崩有什么不同", "docs": [REDIS], "level": "cross"},
    {"q": "内存满了之后如何决定删除哪些数据", "docs": [REDIS], "level": "cross"},
    {"q": "Redis 的数据怎么落盘，断电会不会丢", "docs": [REDIS], "level": "cross"},

    # ================= HTTP 一篇说清楚 =================
    {"q": "强缓存和协商缓存应该怎么配合使用", "docs": [HTTP1, HTTP_GRAD], "level": "natural"},
    {"q": "HTTP/3 相比 HTTP/2 解决了什么问题", "docs": [HTTP1], "level": "term"},
    {"q": "怎么防止页面被注入恶意脚本", "docs": [HTTP1], "level": "cross"},
    {"q": "浏览器同源策略限制下怎么跨域请求", "docs": [HTTP1], "level": "cross"},
    {"q": "HTTP 各个状态码段分别代表什么", "docs": [HTTP1], "level": "natural"},

    # ================= MySQL 事务、索引与锁 =================
    {"q": "MVCC 是怎么减少读写之间的阻塞的", "docs": [MYSQL_TX], "level": "term"},
    {"q": "联合索引设计要注意什么", "docs": [MYSQL_TX, MYSQL_LEARN], "level": "natural"},
    {"q": "库存扣减怎么防止超卖", "docs": [MYSQL_TX], "level": "cross"},
    {"q": "catch 住异常之后事务为什么没有回滚", "docs": [MYSQL_TX], "level": "cross"},
    {"q": "给一个不存在的 id 加锁为什么会把区间锁住", "docs": [MYSQL_TX], "level": "cross"},
    {"q": "读写分离会带来什么问题", "docs": [MYSQL_TX], "level": "cross"},

    # ================= Go GMP =================
    {"q": "goroutine 为什么比操作系统线程轻量", "docs": [GMP], "level": "natural"},
    {"q": "Work Stealing 是怎么回事", "docs": [GMP], "level": "term"},
    {"q": "死循环为什么不饿死其他协程", "docs": [GMP], "level": "cross"},
    {"q": "并行度配额的数量由什么决定", "docs": [GMP], "level": "cross"},
    {"q": "网络 IO 阻塞时会占住系统线程吗", "docs": [GMP], "level": "cross"},

    # ================= Go 并发模式选择指南 =================
    {"q": "并发度失控会引发什么问题", "docs": [GO_PATTERN], "level": "natural"},
    {"q": "I/O 密集型任务适合哪种并发模式", "docs": [GO_PATTERN], "level": "cross"},
    {"q": "有状态的对象要并发访问，用什么模式串行化处理",
     "docs": [GO_PATTERN], "level": "cross"},
    {"q": "监控数据大量小包写库太慢，怎么优化", "docs": [GO_PATTERN], "level": "cross"},
    {"q": "Worker Pool 的工作协程数量怎么定", "docs": [GO_PATTERN], "level": "natural"},
    {"q": "多个微服务的数据要并行拉取再合并，用哪个库更合适",
     "docs": [GO_PATTERN], "level": "cross"},

    # ================= HTTP 渐进 =================
    {"q": "gzip 对哪类资源几乎没有收益", "docs": [HTTP_GRAD], "level": "cross"},
    {"q": "断点续传靠协议里的什么机制实现", "docs": [HTTP_GRAD], "level": "cross"},
    {"q": "反向代理怎么把真实客户端 IP 传下去", "docs": [HTTP_GRAD], "level": "cross"},
    {"q": "保持连接能带来多少吞吐提升", "docs": [HTTP_GRAD], "level": "cross"},
    {"q": "做了内容协商却没写某个头，会让缓存串味", "docs": [HTTP_GRAD], "level": "cross"},
    {"q": "Basic 认证为什么必须配 HTTPS", "docs": [HTTP_GRAD], "level": "natural"},

    # ================= MySQL 学习记 =================
    {"q": "EXPLAIN 的 type 列有哪些取值", "docs": [MYSQL_LEARN, MYSQL_TX], "level": "term"},
    {"q": "InnoDB 默认的事务隔离级别是什么", "docs": [MYSQL_LEARN, MYSQL_TX], "level": "natural"},
    {"q": "字段是字符串但传了数字，会导致索引失效吗", "docs": [MYSQL_LEARN], "level": "cross"},
    {"q": "OR 条件会让索引失效吗", "docs": [MYSQL_LEARN], "level": "cross"},
    {"q": "加了 JOIN 之后查询从毫秒变成 30 秒", "docs": [MYSQL_LEARN], "level": "cross"},
    {"q": "怎么看一条 SQL 有没有走索引", "docs": [MYSQL_LEARN, MYSQL_TX], "level": "natural"},

    # ================= Docker：Namespace 与 Cgroup =================
    {"q": "Namespace 和 Cgroup 各自负责什么", "docs": [DOCKER_NS, DOCKER_UFS], "level": "term"},
    {"q": "怎么把一个进程限制到只能用两个 CPU 核", "docs": [DOCKER_NS], "level": "cross"},
    {"q": "容器里的进程为什么看不到宿主机上的其他进程", "docs": [DOCKER_NS], "level": "cross"},
    {"q": "Docker 启动只要毫秒级，为什么虚拟机要几十秒",
     "docs": [DOCKER_NS, DOCKER_UFS], "level": "cross"},

    # ================= Docker：UnionFS 与组件 =================
    {"q": "UnionFS 的分层只读和写时复制是怎么回事", "docs": [DOCKER_UFS], "level": "term"},
    {"q": "多个镜像为什么能共用同一个基础层", "docs": [DOCKER_UFS], "level": "cross"},
    {"q": "容器删掉之后里面的数据去哪了", "docs": [DOCKER_UFS], "level": "cross"},
    {"q": "Dockerfile 的每条指令为什么能单独复用缓存", "docs": [DOCKER_UFS], "level": "cross"},

    # ================= 浏览器：渲染管道 =================
    {"q": "transform 和 opacity 为什么能绕开主线程", "docs": [RENDER_PIPE], "level": "term"},
    {"q": "只改背景色会不会导致重新计算布局", "docs": [RENDER_PIPE, RENDER_INPUT], "level": "cross"},
    {"q": "读一下 offsetWidth 为什么反而让页面更慢", "docs": [RENDER_PIPE], "level": "cross"},
    {"q": "想把重排的影响限制在一棵子树里，有什么办法", "docs": [RENDER_PIPE], "level": "cross"},
    {"q": "合成层是不是开得越多越流畅", "docs": [RENDER_PIPE], "level": "cross"},
    {"q": "官方对单帧合成该控制在多少毫秒有过建议吗", "docs": [RENDER_PIPE], "level": "cross"},

    # ================= 浏览器：从输入到像素 =================
    {"q": "重排和重绘哪个代价更大", "docs": [RENDER_PIPE, RENDER_INPUT], "level": "natural"},
    {"q": "动画为什么推荐改 transform 而不是 left",
     "docs": [RENDER_PIPE, RENDER_INPUT], "level": "cross"},
    {"q": "怎么在开发者工具里亲眼看到重绘发生", "docs": [RENDER_INPUT], "level": "cross"},
    {"q": "新手最容易卡在渲染流程的哪个阶段", "docs": [RENDER_INPUT], "level": "cross"},

    # ================= Kafka：架构与吞吐 =================
    {"q": "Kafka 的 ISR 集合是干什么的", "docs": [KAFKA_ARCH, KAFKA_FAST], "level": "term"},
    {"q": "Broker、Topic、Partition 三者是什么关系",
     "docs": [KAFKA_ARCH, KAFKA_FAST], "level": "term"},
    {"q": "为什么顺序追加写磁盘能比随机写快上百倍",
     "docs": [KAFKA_ARCH, KAFKA_FAST], "level": "cross"},
    {"q": "消费端是推送模式还是拉取模式", "docs": [KAFKA_ARCH], "level": "cross"},
    {"q": "生产者发消息时怎么决定进哪个分区", "docs": [KAFKA_ARCH, KAFKA_FAST], "level": "cross"},

    # ================= Kafka：为什么快 =================
    {"q": "Page Cache 在 Kafka 里起什么作用", "docs": [KAFKA_FAST], "level": "term"},
    {"q": "一个分区的消息在磁盘上是怎么切的，有哪些文件", "docs": [KAFKA_FAST], "level": "cross"},
    {"q": "消息的 offset 是全局唯一的编号吗", "docs": [KAFKA_FAST], "level": "cross"},
    {"q": "零拷贝到底减少了哪几次数据拷贝", "docs": [KAFKA_ARCH, KAFKA_FAST], "level": "cross"},

    # ================= Python：GIL 与选型 =================
    {"q": "GIL 是什么，为什么多线程吃不满多核", "docs": [PY_GIL], "level": "term"},
    {"q": "CPU 密集的计算在 Python 里该怎么并行", "docs": [PY_GIL], "level": "cross"},
    {"q": "几万个并发连接该用线程还是协程", "docs": [PY_GIL], "level": "cross"},
    {"q": "FastAPI 的路由写成 async def 和普通 def 有什么区别", "docs": [PY_GIL], "level": "cross"},
    {"q": "线程、进程、协程三者的切换开销怎么排", "docs": [PY_GIL], "level": "cross"},

    # ================= Python：异步陷阱 =================
    {"q": "await 一定会把控制权让出去吗", "docs": [PY_ASYNC], "level": "term"},
    {"q": "把 sleep 换成异步版本，为什么还是不并发", "docs": [PY_ASYNC], "level": "cross"},
    {"q": "事件循环底层靠什么知道某个 socket 已经可读", "docs": [PY_ASYNC], "level": "cross"},
    {"q": "怎么确认自己的协程真的在并发执行", "docs": [PY_ASYNC], "level": "cross"},
    {"q": "并发和并行到底差在哪", "docs": [PY_ASYNC, PY_GIL], "level": "natural"},

    # ================= TypeScript：泛型与条件类型 =================
    {"q": "分布式条件类型是什么，怎么阻止它分发", "docs": [TS_GENERIC], "level": "term"},
    {"q": "在条件类型里顺手把某个部分的类型取出来，用什么关键字",
     "docs": [TS_GENERIC, TS_TRICKS], "level": "cross"},
    {"q": "映射类型能做什么", "docs": [TS_GENERIC, TS_TRICKS], "level": "term"},
    {"q": "手写一个 Pick 要用到哪些类型工具", "docs": [TS_GENERIC, TS_TRICKS], "level": "cross"},

    # ================= TypeScript：类型体操技巧 =================
    {"q": "never 在联合类型和条件类型里起什么作用", "docs": [TS_TRICKS], "level": "cross"},
    {"q": "unknown 和 any 到底差在哪", "docs": [TS_TRICKS], "level": "cross"},
    {"q": "void 和 undefined 是同一个东西吗", "docs": [TS_TRICKS], "level": "cross"},
    {"q": "类型和值是不是同一个层面的东西", "docs": [TS_TRICKS], "level": "cross"},

    # ================= 分布式：CAP 与 Raft =================
    {"q": "CAP 定理为什么只能三选二", "docs": [DIST_CAP, DIST_SYS], "level": "term"},
    {"q": "Raft 是怎么选出 Leader 的", "docs": [DIST_CAP, DIST_SYS], "level": "natural"},
    {"q": "集群被网络切成两半、两边都以为自己是主，怎么防止", "docs": [DIST_CAP], "level": "cross"},
    {"q": "拜占庭将军问题要解决的是什么", "docs": [DIST_CAP], "level": "cross"},

    # ================= 分布式：系统性理解 =================
    {"q": "2PC 有什么问题，3PC 改进了什么", "docs": [DIST_SYS], "level": "term"},
    {"q": "跨多个服务的长事务，不想一直持锁，该用什么模式", "docs": [DIST_SYS], "level": "cross"},
    {"q": "分布式系统里为什么不能相信时钟", "docs": [DIST_SYS], "level": "cross"},
    {"q": "怎么验证一个共识算法的实现有没有 bug", "docs": [DIST_SYS], "level": "cross"},
    {"q": "副本之间暂时不一致，但保证最终收敛，这叫什么", "docs": [DIST_SYS], "level": "cross"},

    # ================= 动态规划：五部曲 =================
    {"q": "动态规划五部曲是哪五步", "docs": [DP_FIVE], "level": "term"},
    {"q": "01 背包压成一维数组之后，内层循环为什么要倒着走", "docs": [DP_FIVE], "level": "cross"},
    {"q": "写完状态转移方程，怎么确认自己没写错", "docs": [DP_FIVE], "level": "cross"},
    {"q": "怎么快速判断一道题是不是该用动态规划", "docs": [DP_FIVE, DP_BASIC], "level": "cross"},

    # ================= 动态规划：基础思想 =================
    {"q": "动态规划成立需要哪两个前提", "docs": [DP_BASIC, DP_FIVE], "level": "term"},
    {"q": "记忆化递归和自底向上填表怎么选", "docs": [DP_BASIC], "level": "cross"},
    {"q": "只保留前两个状态、不要整个数组，这叫什么技巧",
     "docs": [DP_BASIC, DP_FIVE], "level": "cross"},
    {"q": "动态规划和分治有什么区别", "docs": [DP_BASIC], "level": "natural"},

    # ================= Nginx：配置手册 =================
    {"q": "location 各种写法的匹配优先级", "docs": [NGINX_CONF], "level": "term"},
    {"q": "proxy_pass 末尾加斜杠和不加，转发路径有什么不同",
     "docs": [NGINX_CONF, NGINX_PROD], "level": "cross"},
    {"q": "upstream 里 backup 是什么意思", "docs": [NGINX_CONF, NGINX_PROD], "level": "term"},
    {"q": "怎么按请求频率限制访问", "docs": [NGINX_CONF], "level": "cross"},
    {"q": "改完配置文件怎么验证语法、怎么平滑生效", "docs": [NGINX_CONF], "level": "cross"},

    # ================= Nginx：生产优化 =================
    {"q": "worker_connections 和 keepalive 该怎么配", "docs": [NGINX_PROD], "level": "term"},
    {"q": "后端是有状态的，想让同一个客户端始终打到同一台", "docs": [NGINX_PROD], "level": "cross"},
    {"q": "504 一般是前端还是后端的锅", "docs": [NGINX_PROD], "level": "cross"},
    {"q": "文件名带 hash 的静态资源能不能设成永久缓存", "docs": [NGINX_PROD], "level": "cross"},
    {"q": "同一 IP 哈希分配会有什么副作用", "docs": [NGINX_PROD], "level": "cross"},
]

# 有多个可接受文档的查询条数 —— 提醒读者：这类查询的「正确」不止一篇
AMBIGUOUS = sum(1 for item in QUERIES if len(item["docs"]) > 1)
