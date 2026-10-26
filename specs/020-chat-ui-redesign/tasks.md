# Tasks: 020-chat-ui-redesign

> 纯前端视觉重做（ChatView.vue + MarkdownRenderer.vue + 少量 App.vue 无关改动）。
> 每 Phase 一次提交；门禁 = type-check + test:logic + build + 真机截图。

## Phase A — 全高列布局

- [ ] **T071** `.chat` 网格改全高：`height: calc(100vh - 136px)`（注释写明 136 = header 60 + footer 60 + main 上距 16，实测值）；主卡 flex 列
- [ ] **T072** 消息区 `flex:1; overflow-y:auto; min-height:0`；输入区停靠底部（dock）；移除 52vh 固定高度
- [ ] **T073** 窄屏（<900px）单列时高度策略退化（消息区给 min-height，不强行全高）

## Phase B — 消息视觉

- [ ] **T074** 助手消息去气泡：头像（AI 徽标圆）+ 内容直排；操作行（复制 + 相对时间）悬停浮现（opacity 过渡）
- [ ] **T075** 用户消息右对齐主色气泡（`--el-color-primary-light-9` 底 / primary 字），max-width 75%
- [ ] **T076** 头像样式统一 28px；去掉 margin-top Hack；用户/助手头像用 EP 图标或文字徽标
- [ ] **T077** 流式三点打字动画（首 token 前；检索中时附状态文字）；有内容后维持闪烁光标
- [ ] **T078** 待确认区 / 工具轨迹样式随新布局微调（更紧凑）

## Phase C — Markdown 与代码块

- [ ] **T079** MarkdownRenderer 代码块改「头部条 + pre」结构：头部含语言名 + 复制按钮（装饰遍历改查 `.md-code__copy`）；复制成功文字反馈
- [ ] **T080** 列表样式：主色标记（`::marker`）、行距收紧；表格头行底纹；引用块左侧色条（已有则细化）
- [ ] **T081** 深色模式下代码头部条与亮色都验证（截图）

## Phase D — 输入区工具行与空态

- [ ] **T082** 输入区上方工具行：知识库增强 chip（开时显示「· N 条」）、Enter 行为 chip；样式为 pill
- [ ] **T083** 空会话欢迎态：AI 徽标 + 欢迎语 + ≥3 个建议问题 chip，点击填入输入框（不自动发送）
- [ ] **T084** 流式中发送按钮换「停止」；工具行 chip 显示检索状态

## Phase E — 会话列表与验收

- [ ] **T085** 会话列表项：标题 13px / 元信息 11px 次级色、间距加大；当前项主色左边条 + 浅底；操作保持悬停浮现
- [ ] **T086** 分组标签更轻（字号/颜色/间距）
- [ ] **T087** 真机验收：1440 + 375 双视口、亮/暗、空态、流式、代码块、工具行、增强开关、引用区；截图存档 `/tmp/ui-audit/r2-*.png`
- [ ] **T088** 门禁终跑 + README 路线图加阶段 20 + memory 回填

## Notes

1. **全高高度 136px 是实测值**：header 60 + footer 60 + main padding-top 16。改 App.vue 骨架（如页脚高度）需同步改这里——已在 CSS 注释标明。
2. 代码块头部条在 v-html 内，复制按钮用原生事件 + `data-decorated` 守卫（既有模式）；语言名从 `code` 的 class 读。
3. 空态建议问题点击只填输入框不发送——用户还要改。
4. 不删任何会话数据；测试数据清理在验收后单独问用户。

## 完成记录（实现时回填）
