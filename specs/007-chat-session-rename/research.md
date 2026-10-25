# Research: 阶段 7 — 会话重命名

**Date**: 2026-10-25 | **Plan**: [plan.md](./plan.md)

> 本阶段的决策几乎全部由**现有代码的事实**推出，而非外部资料。
> 凡引用现有实现行为处均标注文件与结论来源；凡涉及外部事实（HTTP 语义）标注规范。

---

## R-01 HTTP 方法：`PATCH` 而非 `PUT`

**Decision**：`PATCH /api/v1/chat/sessions/{conversationId}`，请求体 `{ "title": "..." }`。

**依据**：
- 语义上这是**部分更新**（只改一个字段，`createdAt` / 消息均不动）。`PUT` 的契约是「用请求体整体替换资源」，
  将来若给会话加字段（如归档标记），`PUT` 会隐含「没传的字段要清空」这一危险解读。
- 与现有路由风格一致：同一控制器已有 `@PostMapping("/sessions")`、`@DeleteMapping("/sessions/{id}")`，
  端点集合按 REST 资源组织。

**被否决的方案**：
- **`PUT /sessions/{id}`**：需要把整份会话视图（含 `messageCount` 这类派生字段）当请求体传回来，
  而派生字段本不该由客户端提供 —— 要么忽略它（契约撒谎），要么校验它（无意义）。
- **`POST /sessions/{id}/rename`**：动词式路由，与同模块既有资源风格不一致；
  且无法用 HTTP 方法表达「这是幂等的部分更新」。

---

## R-02 校验放在 Service 层，DTO 不加 `@Size`

**Decision**：`RenameSessionRequest` 保持纯 record（仅 `String title`），校验全部在
`ChatSessionService` 内经 `Texts.requireNotBlank(title, "标题", Texts.TITLE_MAX_LENGTH)` 完成。

**依据（关键，这是本阶段唯一容易做错的地方）**：
两个"长度"不是同一个东西：

| 校验点 | 度量对象 | 例：`"   " + 190 字` |
|---|---|---|
| Bean Validation `@Size(max = 200)` | **原始**字符串 `length()` | 原始 200+ → **拒绝** |
| `Texts.requireNotBlank` | **strip 后**的长度 | strip 后 190 → **接受** |

业务要求是后者（FR-002 要求落库前去除首尾空白，FR-003 要求按去空白后的字符数计算）。
若两处并存，会出现「用户粘贴带缩进的文本，看起来没超 200 却被拒」这种无法自解释的行为。
错误码层面两者其实**一致**（`GlobalExceptionHandler.handleMethodArgumentNotValid` 也映射到
`VALIDATION_FAILED(40000)`），所以问题不在错误码，而在**判定标准的分叉**。

**被否决的方案**：
- **两处都校验（现状 `CreateSessionRequest` 的写法）**：`@Size` 先拦，Service 的长度分支成为死代码；
  两个口径永远无法同时正确。
- **只用 `@Size`，Service 不校验**：无法实现「strip 后计算长度」，且 `@Size` 对 `null` 放行，
  空标题的判定还得另写一处 —— 校验分散在两个地方反而更多。
- **在 DTO 上加自定义约束（`@NormalizedSize`）**：为一个字段引入自定义 ConstraintValidator，
  新抽象层的收益（复用性）为零 —— 宪法原则 I 要求回答"不这样做的代价"，这里答不上来。

> 与宪法 III「对外一律 DTO，并做参数校验」的关系：DTO 仍是唯一对外契约，校验仍然存在且**唯一**，
> 只是位置在写路径收敛的 Service 层。这不是省略校验，而是避免同一规则有两个来源。

---

## R-03 改名**不**更新 `updatedAt`

**Decision**：`rename` 只写 `title`，不触碰 `updatedAt`。

**依据**：列表按 `updatedAt DESC` 排序（`ChatSessionRepository.findAllByOrderByUpdatedAtDesc`），
而 `updatedAt` 的业务含义是「最近一次对话活动」—— 消息写入与创建时更新。
若把改名算作活动，用户每改一次名，会话就会跳到列表顶部，正在按时间翻找的上下文被打断（US-2）。

**被否决的方案**：
- **改名即更新 `updatedAt`**：实现更"顺手"（JPA 实体改了字段就 flush），但会产生
  「改个名字会话就置顶」的怪异行为，且该行为一旦上线，用户会以为改名有副作用。
- **新增 `renamed_at` 字段**：本阶段无任何需求读取它。

---

## R-04 **不**新增「标题已被用户锁定」状态列

**Decision**：不新增 `title_locked` 列，自动标题逻辑的既有条件（标题等于默认文案 + 会话无消息）保持不变。

**依据**：唯一会出问题的路径是「空会话 → 改名成字面量『新会话』→ 发首条消息」，
此时自动标题会覆盖用户输入。这条路径需要两个条件同时成立，且用户主动把标题改成一个**系统默认文案**。

**被否决的方案**：
- **加 `title_locked boolean`**：迁移 + 实体字段 + 创建/改名/自动标题三处分支，
  只为覆盖上面这一条路径。按宪法原则 I，新增状态必须能回答"不加它的代价"——
  代价就是这条极窄边界行为，已写进 spec Assumptions 并在 quickstart 里作为已知行为记录。
- **把 `title` 改成 nullable，用 `null` 表示"未命名"**：语义更干净（"用户显式命名过"= 非 null），
  但要改列约束 + 全链路空值兜底（前端展示、DTO、排序），改动面比加一列还大。
  且会让 DTO 的 `title` 出现"有时 null"的二态，前端每个展示点都要判空。

> 若将来真的出现用户抱怨，加列的迁移是纯增量的（`ADD COLUMN ... DEFAULT false`），现在不做不会堵死后路。

---

## R-05 前端改成功后**本地替换**，不重拉整个列表

**Decision**：`renameSession` 成功后，用返回的 DTO 就地替换 `sessions` 里对应项。

**依据**：
- 服务端 FR-008 已返回完整会话视图（含 `messageCount`），本地替换无信息缺失。
- 重拉列表会带来一次可见的闪烁与滚动位置跳动；且改名本就**不改变顺序**（R-03），
  重拉除了浪费一次请求没有收益。

**被否决的方案**：
- **成功后 `await loadSessions()`**：与既有 `createSession` / `removeSession` 的写法一致（它们必须重拉，
  因为增删会改变集合与顺序），但改名不改集合也不改顺序，套用同一模式属于机械模仿。
- **乐观更新（先改 UI 再发请求）**：改名失败时要回滚，且用户能立刻看到"改了又变回去"；
  改名的请求耗时在局域网内是毫秒级，不值得为它引入回滚逻辑。

---

## R-06 交互形态：`ElMessageBox.prompt`，不做内联编辑

**Decision**：点「重命名」→ 弹 Element Plus 的 prompt 对话框，预填当前标题，提交前做非空与 200 字校验。

**依据**：
- 项目已依赖 Element Plus，且 `ChatView.vue` 已用 `ElMessageBox.confirm` 做删除确认 —— 形态一致。
- prompt 自带校验回调与"确定/取消"，无需在列表项里维护 `editing` 状态、焦点与 Esc 处理。
- 会话标题是短文本（≤200），对话框输入足够；内联编辑的收益主要在"重命名非常频繁"的场景，本项目不是。

**被否决的方案**：
- **列表项内联 `el-input`**：需要在 `v-for` 里维护 `editingId`，处理点击穿透（列表项本身有 `@click` 选中会话）、
  失焦保存/取消、Esc 退出。为一个小功能引入三类交互边界，容易出"点输入框却切换了会话"的 bug。
- **在会话头部（右侧主区）改名**：能改的只有当前打开的会话，而用户改名的动机往往正是
  "在列表里找不到了"——此时未打开的会话恰恰是最需要改名的。

---

## R-07 **不**引入 `Idempotency-Key`

**Decision**：改名端点不参与双层幂等机制。

**依据**：幂等机制（`IdempotencyService` + `operation_record`）是为**不可逆或有副作用**的写操作设计的
（创建任务、删除、调用外部工具）。改名是**幂等操作本身**：同一标题重复提交，结果与提交一次相同，
数据库最终状态一致（FR-005 就是据此定义的）。要求客户端生成幂等键只会增加调用方负担。

**被否决的方案**：
- **套用 `Idempotency-Key`**：会写一条 `operation_record`，让审计表充满"改标题"这种无追溯价值的记录，
  稀释 `operation_record` 的信号。

---

## R-08 顺手收敛 `create` 的校验到 `Texts`

**Decision**：`ChatSessionService.create` 改用 `Texts.trimToNull` + `Texts.requireNotBlank`，
并删除 `CreateSessionRequest` 上的 `@Size(max = 200)`。

**依据**：
- 阶段 6 之后已存在 `common/text/Texts`（`TITLE_MAX_LENGTH = 200`），但 `chat` 模块仍保留
  自己的一份 `private static final int TITLE_MAX_LENGTH = 200` —— 与 `task`、`calendar` 的写法不一致，
  且长度上限再次出现第三份拷贝。
- 保留 `@Size` 会让**创建**与**改名**对"长度"用不同口径（见 R-02），同一字段两条规则正是阶段 6 技术债清偿要消灭的模式。

**风险与验证**：既有行为变化仅限「原始长度 > 200 但去空白后 ≤ 200」的输入 —— 从拒绝变为接受，
这是修复而非回归。`ChatSessionService` 的 201 字符用例（无首尾空白）行为不变，
由阶段 1 的既有集成测试与新增测试共同守住。

**被否决的方案**：
- **不动 `create`，只让 `rename` 用 `Texts`**：留下两个口径，下一个人改标题校验时必然踩坑。
