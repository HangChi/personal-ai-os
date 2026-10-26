# Tasks: 019-knowledge-chat-settings

> 按 Phase 分组，**每个 Phase 一次提交**。T 编号全局唯一（接 018 的 T046）。

## Phase A — 后端：检索注入 + 引用落库

- [x] **T047** V11 迁移 `chat_message_citation`（外键级联 + message_id 索引，字段见 data-model）
- [x] **T048** `ChatMessageCitationEntity` + `ChatMessageCitationRepository`（按 messageId 批量查 / 按 conversationId 删）
- [x] **T049** `knowledge/spi/KnowledgeSearchPort` 窄接口（`List<KnowledgeHitDto> search(String, int)`）；`KnowledgeRetrievalService` implements 之（方法签名已存在，加 implements 即可）
- [x] **T050** `StreamChatRequest` 扩展 `useKnowledge` / `topK`（校验注解收敛 1–20，null 回落默认）；`ChatStreamEvents` 加 `Citations` 记录
- [x] **T051** `ChatCompletionService`：`useKnowledge` 时经 `ObjectProvider<KnowledgeSearchPort>` 检索（拿不到端口 → 降级提示）；命中片段拼 system 提示（基础人设 + 贡献者 + 编号片段 + 使用约束，C-01 格式）；start 后发 `citations` 事件；检索异常 → error 降级事件 + 流继续
- [x] **T052** 终帧落库：done 前查该会话最新 ASSISTANT 消息，把命中快照写入 citation 表（同事务）；查不到只记 WARN 不抛
- [x] **T053** `ChatMessageDto` + `ChatCitationDto`；`ChatMessageService.list` 批量附带 citations（一次 in 查询，不 N+1）
- [x] **T054** Controller 透传 `useKnowledge` / `topK`
- [x] **T055** 集成测试：① 开关关 → 无 citations 事件无落库；② 开关开有命中 → citations 事件 + 落库 + GET messages 带回；③ N=0；④ 检索抛错 → error 降级 + 流完成；⑤ 删会话级联清引用；⑥ topK=99 收敛 20
- [x] **T056** `./mvnw -B verify` 全绿（基线 315 + 新增）

## Phase B — 前端：对话增强交互

- [x] **T057** `src/lib/settings.ts` 纯函数：`clampTopK` / `mergeSettings` / `conversationKnowledgeKey` + `frontend/test/settings.test.mjs`（脏值 / 越界 / 键格式）
- [x] **T058** `src/stores/settings.ts`：`os.settings.*` 四项偏好 + watch 持久化 + `resetAll()`（清 `os.` 前缀 + reload）
- [x] **T059** `api/chat.ts`：`StreamHandlers.onCitations` + `dispatchEvent` citations 分支；`streamChat` 请求体带 `useKnowledge` / `topK`
- [x] **T060** `stores/chat.ts`：`send(content, {useKnowledge, topK})`；`liveCitations` ref（本轮命中，供流式中展示）；types/api.ts 加 `ChatCitation` 与 `ChatMessage.citations`
- [x] **T061** `ChatView.vue`：输入区增强开关（会话级持久化，新会话取设置默认）+ topK 调整 + 「正在检索知识库… / 已找到 N 条依据」状态 + Enter/Ctrl+Enter 行为（设置驱动）
- [x] **T062** `ChatView.vue` 引用区：助手消息下方 `引用来源（N）` 折叠展示（编号/标题/文件名/片段号/内容），样式复用知识页引用样式
- [x] **T063** `CalendarView.vue`：`leadMinutes` 初值取 `settings.reminderLead`
- [x] **T064** 门禁：type_check + test:logic（基线 314 + 新增）+ build

## Phase C — 前端：设置页

- [x] **T065** `views/SettingsView.vue`：外观（主题三态 radio / 侧栏开关）/ 对话（增强默认 / topK / Enter 发送）/ 日程（默认提前量）/ 数据（导出 / 重置偏好）/ 关于（版本 + 后端健康）
- [x] **T066** 路由 `/settings`（meta title 设置）+ `App.vue` 导航第 8 项（Setting 图标）
- [x] **T067** 门禁 + 真机：设置页各 section、重置偏好流程、导航跳转

## Phase D — 验收与收尾

- [x] **T068** 真机：增强开/关两态对话（带 [n] 与引用区）、N=0、检索状态指示、刷新后引用仍在、设置页全项、重置偏好
- [x] **T069** 后端 `./mvnw -B verify` + 前端三门禁终跑；README 路线图加阶段 19；memory 回填
- [x] **T070** 全站回归走查（8 页 + 深色 + 375px）无新增报错

## Notes（贯穿全清单的硬约束）

1. **useKnowledge=false 是回归保护面**：请求体缺省值、事件序列、落库行为必须与阶段 1 逐字符一致——T055①是守门测试。
2. **终帧落库依赖 Advisor 时序**（plan Complexity Tracking）：实测 `adviseStream` 的 `after()` 在上游；实现按此，测试断言；查不到消息只 WARN 不抛。
3. **检索内容只进 system 提示**，不进用户消息、不进工具上下文（宪法 II：模型边界不变）。
4. **引用是快照**：不外键 knowledge 文档；文档删除不影响历史引用。
5. **前端会话级开关按会话键存**，切会话读取目标会话的键；新会话回落设置默认。
6. 每段提交前门禁全绿 + 真机动作留截图（quickstart）；未验项保持未勾选。

## 完成记录（实现时回填）

### Phase D（验收与收尾）

- T068 真机：开/关两态、[n]+引用区、刷新后引用仍在、设置页全项、重置偏好（Playwright 三套脚本全过）。
  N=0 真机未复现（问「猫咪为什么不吃饭」检索返回 3 条弱相关片段——已知检索局限），但模型正确回答
  「知识库中没有」未错误引用；真空命中由集成测试覆盖（count=0 分支）。
- T069 门禁终跑：后端 321；前端 348 + build（1364KB）；README 路线图 + memory 回填完成。
- T070 回归：亮/深 9 页（含新设置页）+ 375px 窄屏，零控制台报错、零横向溢出。



### Phase C（前端：设置页）

- 门禁：type-check 0 错；test:logic 348/348；build 通过。
- 真机（Playwright 全过）：侧栏第 8 项「设置」→ /settings 五 section（外观/对话/日程/数据/关于）；
  主题切深色即生效且刷新保持；enterSend/topK/lead 改动落盘、刷新保持；
  **跨页联动**：对话页 Enter 变换行、日程页默认提前量变 30；
  重置偏好：二次确认存在、取消不丢偏好、确认后 os.* 清空且主题回跟随系统；控制台零报错。
- 验收脚本误报一条：读 `os.settings.topK` 验证取消后偏好还在——真实键是
  `os.settings.knowledgeTopK`（键名笔误，非应用问题；隔离复现确认取消行为正确）。

### Phase B（前端：对话增强交互）

- 门禁：type-check 0 错；test:logic **348/348**（基线 314 + 新增 34 条 settings 纯函数测试）；build 通过。
- 真机（Playwright 全过）：开关按会话键持久化；开增强发问 → 4 条引用 + 回答带 [1] 编号；刷新后引用与开关都在；关增强再发无引用；Enter 默认发送；控制台零报错。
- 实现要点：
  - 会话级开关**不用 watch 写回**——切会话时 loadKnowledgePref 改 ref，watch 会把旧会话值写到新会话键上（静默串话）；写回只在 toggleKnowledge 显式发生。
  - 检索状态用三态机（idle/searching/done）而非 liveCitations 判空：零命中时 citations 事件带空数组到达，与「事件没到」无法区分。
  - topK 用 computed setter 直接绑设置页默认值（clamp 在 setter），避免同步逻辑。
  - Enter 行为含 isComposing 守卫：中文输入法选词期的 Enter 不触发发送。

### Phase A（后端：检索注入 + 引用落库）

- 门禁：`./mvnw -B verify` **321 测试全绿**（基线 315 + 新增 6），BUILD SUCCESS。
- 新测试 ChatKnowledgeIntegrationTest 6 场景全过：开关关零检索零引用（事件序列与阶段 1 逐字符一致）/ citations 事件先行+系统提示编号注入+落库可查回 / 零命中 count=0 / topK 99→20 / 检索故障降级（error 提示+流继续）/ 删会话级联清引用。
- 关键时序假设被测试证实：终帧回调里引用成功挂到刚落库的助手消息（Advisor after() 在上游，plan Complexity Tracking 的实测依据成立）。
- 测试坑：同类用例共享 Testcontainers 库，全局 count 断言会互相干扰 → 仓储加 countByMessageIdIn，断言改按会话计（写进 Notes）。
- 另一编译坑：Kotlin 风格的 `"event:".length` 写进了 Java 测试（应为 length()），javac 直接拦下。


