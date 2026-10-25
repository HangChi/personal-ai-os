#!/usr/bin/env python3
"""RAG 检索评测的查询集（2026-10-26 扩充至 58 条）。

## 三档难度

| level | 含义 | 举例 |
|---|---|---|
| `term` | 查询直接用了术语，与文档有明确词面重叠 | 「BRIN 索引适合什么场景」 |
| `natural` | 自然问句，措辞与文档不同但共享关键词 | 「索引在什么情况下会失效」 |
| `cross` | **刻意避开技术术语**，用意图/现象描述；与文档几乎没有词面重叠 | 「日志表按时间顺序写入，该建什么索引」 |

`cross` 才是真正测语义检索的一档：词袋式实现（如测试用的
DeterministicEmbeddingModel）在它上面几乎必然失败，而真实嵌入模型应该能撑住。
只看 `term` 的分数会严重高估检索能力。

## 标注

`docs` 是**可接受的文档列表**：同主题两篇（MySQL / Go / HTTP）内容有重叠，
若某查询在两篇里都有答案，两篇都算对 —— 强行只标一篇会把「答对了但命中的是
另一篇」判成失败，那不是检索的问题。`AMBIGUOUS` 字段会统计这类查询有多少条。

## 编写原则

- 查询必须**能在这篇文档里找到答案**（写之前逐篇看过正文与章节）。
- 不抄标题：抄标题只能测字面匹配。
- 每篇文档 5~6 条，避免某一篇主导总分。
"""

# 文档标题常量，避免长字符串写错导致标注失效
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

QUERIES = [
    # ---------------- PostgreSQL 索引 ----------------
    {"q": "BRIN 索引适合什么场景", "docs": [PG], "level": "term"},
    {"q": "索引在什么情况下会失效", "docs": [PG], "level": "natural"},
    {"q": "什么时候该用 GIN 而不是 B-tree", "docs": [PG], "level": "natural"},
    {"q": "日志表按时间顺序不断写入、想用很小的索引先筛掉一批数据块，该建哪种索引",
     "docs": [PG], "level": "cross"},
    {"q": "数组字段要支持「包含」查询怎么建索引", "docs": [PG], "level": "cross"},
    {"q": "Hash 索引有哪些限制", "docs": [PG], "level": "natural"},
    {"q": "只给未完成的订单建索引，能做到吗", "docs": [PG], "level": "cross"},

    # ---------------- Vue3 响应式 ----------------
    {"q": "Vue3 的 computed 是怎么做缓存的", "docs": [VUE], "level": "natural"},
    {"q": "为什么 Vue3 放弃了 Object.defineProperty", "docs": [VUE], "level": "term"},
    {"q": "ref 和 reactive 该用哪个", "docs": [VUE], "level": "natural"},
    {"q": "组件卸载后副作用没清掉，Vue3 有什么办法管理副作用作用域",
     "docs": [VUE], "level": "cross"},
    {"q": "watch 的回调什么时候执行，能配置吗", "docs": [VUE], "level": "cross"},

    # ---------------- Java 虚拟线程 ----------------
    {"q": "虚拟线程有哪些坑", "docs": [VT], "level": "natural"},
    {"q": "在 synchronized 块里等 IO 会发生什么", "docs": [VT], "level": "cross"},
    {"q": "纯计算任务适合用虚拟线程吗", "docs": [VT], "level": "cross"},
    {"q": "要不要给虚拟线程建线程池", "docs": [VT], "level": "cross"},
    {"q": "ThreadLocal 在虚拟线程下有什么风险", "docs": [VT], "level": "natural"},
    {"q": "虚拟线程和平台线程有什么区别", "docs": [VT], "level": "term"},

    # ---------------- Redis ----------------
    {"q": "缓存雪崩是什么原因造成的", "docs": [REDIS], "level": "natural"},
    {"q": "Redis 为什么比直接查数据库快", "docs": [REDIS], "level": "natural"},
    {"q": "缓存击穿和雪崩有什么不同", "docs": [REDIS], "level": "cross"},
    {"q": "内存满了之后如何决定删除哪些数据", "docs": [REDIS], "level": "cross"},
    {"q": "Redis 的数据怎么落盘，断电会不会丢", "docs": [REDIS], "level": "cross"},

    # ---------------- HTTP 一篇说清楚 ----------------
    {"q": "强缓存和协商缓存应该怎么配合使用", "docs": [HTTP1], "level": "natural"},
    {"q": "HTTP/3 相比 HTTP/2 解决了什么问题", "docs": [HTTP1], "level": "term"},
    {"q": "怎么防止页面被注入恶意脚本", "docs": [HTTP1], "level": "cross"},
    {"q": "浏览器同源策略限制下怎么跨域请求", "docs": [HTTP1], "level": "cross"},
    {"q": "HTTP 各个状态码段分别代表什么", "docs": [HTTP1], "level": "natural"},

    # ---------------- MySQL 事务、索引与锁 ----------------
    {"q": "MVCC 是怎么减少读写之间的阻塞的", "docs": [MYSQL_TX], "level": "term"},
    {"q": "联合索引设计要注意什么", "docs": [MYSQL_TX], "level": "natural"},
    {"q": "库存扣减怎么防止超卖", "docs": [MYSQL_TX], "level": "cross"},
    {"q": "catch 住异常之后事务为什么没有回滚", "docs": [MYSQL_TX], "level": "cross"},
    {"q": "给一个不存在的 id 加锁为什么会把区间锁住", "docs": [MYSQL_TX], "level": "cross"},
    {"q": "读写分离会带来什么问题", "docs": [MYSQL_TX], "level": "cross"},

    # ---------------- Go GMP ----------------
    {"q": "goroutine 为什么比操作系统线程轻量", "docs": [GMP], "level": "natural"},
    {"q": "Work Stealing 是怎么回事", "docs": [GMP], "level": "term"},
    {"q": "死循环为什么不饿死其他协程", "docs": [GMP], "level": "cross"},
    {"q": "并行度配额的数量由什么决定", "docs": [GMP], "level": "cross"},
    {"q": "网络 IO 阻塞时会占住系统线程吗", "docs": [GMP], "level": "cross"},

    # ---------------- Go 并发模式选择指南 ----------------
    {"q": "并发度失控会引发什么问题", "docs": [GO_PATTERN], "level": "natural"},
    {"q": "I/O 密集型任务适合哪种并发模式", "docs": [GO_PATTERN], "level": "cross"},
    {"q": "有状态的对象要并发访问，用什么模式串行化处理",
     "docs": [GO_PATTERN], "level": "cross"},
    {"q": "监控数据大量小包写库太慢，怎么优化", "docs": [GO_PATTERN], "level": "cross"},
    {"q": "Worker Pool 的工作协程数量怎么定", "docs": [GO_PATTERN], "level": "natural"},
    {"q": "多个微服务的数据要并行拉取再合并，用哪个库更合适",
     "docs": [GO_PATTERN], "level": "cross"},

    # ---------------- HTTP 渐进 ----------------
    {"q": "gzip 对哪类资源几乎没有收益", "docs": [HTTP_GRAD], "level": "cross"},
    {"q": "断点续传靠协议里的什么机制实现", "docs": [HTTP_GRAD], "level": "cross"},
    {"q": "反向代理怎么把真实客户端 IP 传下去", "docs": [HTTP_GRAD], "level": "cross"},
    {"q": "保持连接能带来多少吞吐提升", "docs": [HTTP_GRAD], "level": "cross"},
    {"q": "做了内容协商却没写某个头，会让缓存串味", "docs": [HTTP_GRAD], "level": "cross"},
    {"q": "Basic 认证为什么必须配 HTTPS", "docs": [HTTP_GRAD], "level": "natural"},

    # ---------------- MySQL 学习记 ----------------
    {"q": "EXPLAIN 的 type 列有哪些取值", "docs": [MYSQL_LEARN], "level": "term"},
    {"q": "InnoDB 默认的事务隔离级别是什么", "docs": [MYSQL_LEARN, MYSQL_TX], "level": "natural"},
    {"q": "字段是字符串但传了数字，会导致索引失效吗", "docs": [MYSQL_LEARN], "level": "cross"},
    {"q": "OR 条件会让索引失效吗", "docs": [MYSQL_LEARN], "level": "cross"},
    {"q": "加了 JOIN 之后查询从毫秒变成 30 秒", "docs": [MYSQL_LEARN], "level": "cross"},
    {"q": "怎么看一条 SQL 有没有走索引", "docs": [MYSQL_LEARN, MYSQL_TX], "level": "natural"},
]

# 有多个可接受文档的查询条数 —— 用来提醒读者：这类查询的「正确」不止一篇
AMBIGUOUS = sum(1 for item in QUERIES if len(item["docs"]) > 1)
