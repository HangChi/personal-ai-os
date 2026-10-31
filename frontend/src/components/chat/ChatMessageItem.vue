<script setup lang="ts">
/**
 * 单条消息（从 ChatView 拆出，2026-10-26）。
 *
 * <p>助手消息去气泡（头像 + 内容直排），用户消息右对齐主色气泡；
 * 时间与复制按钮默认隐藏、悬停浮现（FR-003：不抢版面）。
 *
 * <p>引用区（阶段 19）随消息走：引用是这条回答的一部分，
 * 拆成独立组件反而要在父层再拼一次编号。
 */
/*
 * 图标必须逐个显式 import。
 *
 * unplugin-vue-components 只自动解析 EP 的 `El*` 组件，**不解析 @element-plus/icons-vue
 * 的图标**；未导入的图标名会被 Vue 当成「未知组件」渲染成空，`vue-tsc` 也不报错
 * （未知标签回落到 any）。改造前 `RefreshRight` 与 `EditPen` 就漏在这里 ——
 * 「重新生成」「编辑」两按钮只剩文字、图标位空一格，因为这个坑不报错，
 * 一直没人发现。合并成一条 import 也顺带修掉原先分散的两处 Document 导入。
 */
import { CopyDocument, Document, EditPen, RefreshRight, User } from '@element-plus/icons-vue'

import MarkdownRenderer from '@/components/MarkdownRenderer.vue'
import { relativeTime } from '@/lib/relative-time'
import ChatToolTrail from '@/components/chat/ChatToolTrail.vue'
import type { ChatAttachment, ChatMessage, ToolInvocation } from '@/types/api'

const props = defineProps<{
  message: ChatMessage
  /** 本消息产生的工具调用轨迹（仅最后一条助手消息会传）；空数组不渲染 */
  toolTrail: ToolInvocation[]
}>()

/** 附件内容地址：后端给的是相对路径，这里拼 API 基路径 */
function attachmentUrl(item: ChatAttachment): string {
  const base = import.meta.env.VITE_API_BASE_URL ?? '/api/v1'
  return `${base}/chat/uploads/${item.id}/content`
}

const emit = defineEmits<{
  (e: 'copy', content: string): void /** 仅助手消息：重跑最后一条回答 */
  (e: 'regenerate'): void
  /** 仅用户消息：编辑后重发 */
  (e: 'edit', message: ChatMessage): void
}>()
</script>

<template>
  <!-- data-message-id：会话内搜索据此滚动定位。用属性而不是 ref 数组，
       因为消息是 v-for 生成的、数量随翻页变化，ref 顺序不稳 -->
  <div :class="['chat-msg', `chat-msg--${message.role.toLowerCase()}`]" :data-message-id="message.id">
    <!-- 助手：头像在左，内容直排 -->
    <template v-if="message.role !== 'USER'">
      <span class="chat-msg__avatar chat-msg__avatar--assistant">
        <AppLogo variant="mark" :size="22" />
      </span>
      <div class="chat-msg__content">
        <!--
          工具轨迹放在气泡**上方**（2026-10-27 调整）：它描述的是「为了得出下面这段回复，
          助手做了些什么」，是过程；气泡是结论。过程在结论之前，读起来才顺。
          改造前它在气泡下方，读起来像「补充说明」。
        -->
        <ChatToolTrail v-if="props.toolTrail.length > 0" :items="props.toolTrail" class="chat-msg__trail" />

        <div class="chat-msg__bubble chat-msg__bubble--assistant">
          <MarkdownRenderer :content="message.content" />
        </div>

        <!-- 引用区（阶段 19） -->
        <div v-if="message.citations.length > 0" class="chat-msg__citations">
          <el-collapse>
            <el-collapse-item>
              <template #title>
                <span class="chat-msg__citations-title">引用来源（{{ message.citations.length }}）</span>
              </template>
              <div v-for="citation in message.citations" :key="citation.index" class="chat-msg__citation">
                <div class="chat-msg__citation-head">
                  <span class="chat-msg__citation-source">
                    [{{ citation.index }}] {{ citation.documentTitle }}
                    <span class="chat-msg__citation-meta">
                      {{ citation.filename }} · #{{ citation.chunkIndex + 1 }}
                    </span>
                  </span>
                </div>
                <div class="chat-msg__citation-content">{{ citation.content }}</div>
              </div>
            </el-collapse-item>
          </el-collapse>
        </div>
        <!-- 悬停操作行 -->
        <div class="chat-msg__actions">
          <span class="chat-msg__time">{{ relativeTime(message.createdAt) }}</span>
          <button
            type="button"
            class="chat-msg__action"
            title="复制"
            aria-label="复制这条回复"
            @click="emit('copy', message.content)"
          >
            <el-icon :size="13"><CopyDocument /></el-icon>
          </button>
          <button
            type="button"
            class="chat-msg__action"
            title="重新生成"
            aria-label="重新生成这条回复"
            @click="emit('regenerate')"
          >
            <el-icon :size="13"><RefreshRight /></el-icon>
          </button>
        </div>
      </div>
    </template>

    <!-- 用户：右侧主色气泡 -->
    <template v-else>
      <div class="chat-msg__content chat-msg__content--user">
        <!-- 附件渲染在气泡**上方**：它是用户带过来的材料，先于文字被读到 -->
        <div v-if="message.attachments.length > 0" class="chat-msg__attachments">
          <a
            v-for="item in message.attachments"
            :key="item.id"
            :href="attachmentUrl(item)"
            target="_blank"
            rel="noopener"
            class="chat-msg__attachment"
            :title="item.filename"
          >
            <img
              v-if="item.contentType.startsWith('image/')"
              :src="attachmentUrl(item)"
              :alt="item.filename"
              class="chat-msg__attachment-image"
              loading="lazy"
            />
            <el-icon v-else class="chat-msg__attachment-icon"><Document /></el-icon>
            <span class="chat-msg__attachment-name">{{ item.filename }}</span>
          </a>
        </div>
        <div class="chat-msg__bubble chat-msg__bubble--user">
          <MarkdownRenderer :content="message.content" />
        </div>
        <div class="chat-msg__actions chat-msg__actions--user">
          <span class="chat-msg__time">{{ relativeTime(message.createdAt) }}</span>
          <button
            type="button"
            class="chat-msg__action"
            title="复制"
            aria-label="复制这条消息"
            @click="emit('copy', message.content)"
          >
            <el-icon :size="13"><CopyDocument /></el-icon>
          </button>
          <button
            type="button"
            class="chat-msg__action"
            title="编辑并重发"
            aria-label="编辑并重发这条消息"
            @click="emit('edit', message)"
          >
            <el-icon :size="13"><EditPen /></el-icon>
          </button>
        </div>
      </div>
      <span class="chat-msg__avatar chat-msg__avatar--user">
        <el-icon :size="14"><User /></el-icon>
      </span>
    </template>
  </div>
</template>

<style scoped>
.chat-msg {
  display: flex;
  align-items: flex-start;
  gap: var(--os-space-3);
}

.chat-msg--user {
  justify-content: flex-end;
}

/*
 * 头像 32px（原 28px）：气泡圆角提到 16px 后，28px 的头像显得头轻脚重。
 * 32 与气泡内边距（1em≈14px）加起来正好对齐首行文字的中线。
 */
.chat-msg__avatar {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  margin-top: 2px;
  border-radius: 50%;
}

.chat-msg__avatar--assistant {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  /* 一圈同色系细边：白底气泡上，纯浅色圆点会「飘」，加边后成为明确锚点 */
  box-shadow: inset 0 0 0 1px var(--el-color-primary-light-7);
}

/* 用户头像用实底主色：气泡是浅主色底，头像实底才成为「说话方锚点」 */
.chat-msg__avatar--user {
  background: var(--el-color-primary);
  color: var(--os-on-primary);
}

/*
 * 宽度上限（2026-10-26 调整）：原先 760px，在 1440px 屏上右侧会空出 350px 以上，
 * 对话区显得「没占满」。放到 1000px（约合中文 77 字）后内容铺满主列，
 * 同时仍留出一道右边距，避免文字顶到卡片边缘。
 */
.chat-msg__content {
  min-width: 0;
  flex: 1;
  max-width: 1000px;
  font-size: var(--os-font-sm);
  line-height: 1.75;
}

.chat-msg__content--user {
  flex: 0 1 auto;
  max-width: 82%;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.chat-msg__bubble {
  /*
   * 内边距按「行」算，不按固定值：单行消息上下留 10px 就够，多行则需要 14px
   * 才不显闷。用 em 让它随字号缩放，正文字号调整时不用再改这里。
   *
   * 2026-10-29：上下改回与左右同阶（原 0.75em / 1em 的组合在 14px 下是 10.5 vs 14，
   * 但首末段距漏清又让底部多出 8px，实测变成 10.8 / 18.8）。段距已在
   * MarkdownRenderer 里清零，这里把上下左右统一到 0.85em —— 单行消息的
   * 气泡高度从 53.6 收到约 51，且四向视觉留白一致。
   */
  padding: 0.85em 1em;
  border-radius: var(--os-radius-2xl);
  max-width: 100%;
}

/* 短回答随内容收缩，长回答最多占满阅读栏；与流式消息使用同一宽度策略。 */
.chat-msg__bubble--assistant {
  /*
   * `position: relative` 是尾巴的**必要条件**，不是随手加的。
   * 两个伪元素都是 `position: absolute`，而它们要贴的是「气泡自己的左上外沿」——
   * 一旦气泡不是定位元素，`left/top` 会一路向上找到别的定位祖先（都没有的话
   * 就退到初始包含块），尾巴会跑到页面左上角去。实测漏掉这行时
   * `getComputedStyle(bubble).position === 'static'`，尾巴完全不在气泡旁边。
   */
  position: relative;
  width: fit-content;
  background: var(--os-bubble-bg);
  border: 1px solid var(--os-bubble-border);
  box-shadow: var(--os-shadow-bubble);
  /* 圆角朝向头像那一侧收窄：气泡的「尖角」暗示说话人方向 */
  border-bottom-left-radius: var(--os-radius-xs);
}

/*
 * 助手气泡的尾巴：把「头像」和「气泡」连成一个整体。
 *
 * 为什么需要它：气泡改成 `width: 100%` 之后，左边界与头像之间隔着 12px 硬空隙，
 * 视觉上头像像一枚飘在旁边的独立徽章，而气泡只是一块和它无关的灰条 ——
 * 这正是「回复和头看起来是分离的、有点丑」的来源。实测每个块平均右侧空出
 * 471~561px（内容区 816px），一块大半是空的灰底，再没有一个连接物就会散。
 *
 * 做法是**纯 CSS 双三角**（border trick），不用 SVG、不用额外 DOM：
 * 外层 `::before` 画描边三角（用气泡的边框色），内层 `::after` 画填充三角
 * （用气泡的底色，右移 1px 盖住前者的右边一条），合起来就是一个带描边的尖角。
 *
 * 几何（全部相对气泡自身）：
 * - `left: -7px`：气泡左边框外侧，伸进那 12px 空隙里；
 * - 三角宽 7px（`border-right: 7px`）；高 8px 上下（`border-width: 8px 0` 太宽，
 *   这里用 `8px` 的上下 border 造出 16px 底边，再靠 `top` 定位）；
 * - `top: 10px`：让三角的尖端对齐**头像的垂直中心**（实测头像 172~204，
 *   中心 188；气泡顶 170，所以中心相对气泡是 18px，减去三角半高 8px ≈ 10px）。
 *
 * ⚠️ 两个必须同时满足的前提：
 * 1. 气泡的 `overflow` 必须是 `visible`（默认值）。若有人给气泡加
 *    `overflow: hidden`（比如为了裁代码块圆角），尾巴会整个消失且**不报错**。
 * 2. `::before` / `::after` 会被 `.md-code` 之类的子元素挡住一层，
 *    所以尾巴的 `z-index` 不重要、但**必须写在气泡自身上**而不是内容容器上。
 */
.chat-msg__bubble--assistant::before,
.chat-msg__bubble--assistant::after {
  content: '';
  position: absolute;
  top: 11px;
  width: 0;
  height: 0;
  border-style: solid;
  pointer-events: none;
}

/* 描边三角：比填充三角大 1px（向右多 1px），露出来的那一圈就是尾巴的边 */
.chat-msg__bubble--assistant::before {
  left: -12px;
  border-width: 7px 12px 7px 0;
  border-color: transparent var(--os-bubble-border) transparent transparent;
}

/* 填充三角：底色，右移 1px 压住描边三角的内侧，让两者共用一条外边 */
.chat-msg__bubble--assistant::after {
  top: 12px;
  left: -11px;
  border-width: 6px 11px 6px 0;
  border-color: transparent var(--os-bubble-bg) transparent transparent;
}

/*
 * 用户气泡：浅主色底 + 主色边框，与助手气泡同构（都是「浅底 + 边框 + 深字」）。
 *
 * 这里有一段弯路。阶段 24 嫌"两边都是浅色分不清谁在说话"，把用户气泡改成了
 * 主色实底 #4f46e5。实测底色亮度 126 vs 助手 244，**亮度差 118** ——
 * 一屏里成了一块极响的色块，把注意力从「回答正文」抢走了，整体反而更丑。
 *
 * 现在的做法：靠**色相**区分说话人（蓝紫 vs 中性灰），而不是明暗对立。
 * 两侧亮度 242 vs 244（差 2），视觉重量平衡；边框 2.446 / 底色差 1.157，
 * 边界依然明确。这也让「用户气泡里的 Markdown」不再需要整套反色 ——
 * 它和助手气泡一样是浅底深字，MarkdownRenderer 的配色直接可用。
 *
 * ⚠️ 这里必须显式写 `width: fit-content`。它原先是从通用 `.chat-msg__bubble`
 * 继承来的，2026-10-30 助手侧改成 `width: 100%` 时把通用那条删了 ——
 * 删掉后用户气泡会退回 flex 子项的默认 `auto`，**短消息也被拉成整栏宽**，
 * 右对齐的缺口消失，「随口说一句」的观感全无。
 */
.chat-msg__bubble--user {
  width: fit-content;
  background: var(--os-bubble-user-bg);
  border: 1px solid var(--os-bubble-user-border);
  color: var(--el-text-color-primary);
  border-bottom-right-radius: var(--os-radius-xs);
}

/*
 * 只覆盖链接与强调色：默认主色（#4f46e5）落在浅主色底（#eeedfc）上对比 5.436，够用，
 * 但用更深一档（#3f38b7，7.378）能让「引用/链接」在气泡里更明确地跳出来。
 * 其余变量全部沿用全局值 —— 不再维护一整套反色映射。
 */
.chat-msg__bubble--user :deep(.md__body) {
  --el-color-primary: var(--el-color-primary-dark-2);
}

/* 暗色下主色阶梯方向相反（dark-2 是更亮的），链接改用更亮一档 */
html.dark .chat-msg__bubble--user :deep(.md__body) {
  --el-color-primary: var(--el-color-primary-light-3);
}

/*
 * 代码块不需要特判：它已改用 --os-code-* 独立令牌（见 MarkdownRenderer），
 * 永远是「一块浅色卡片」，放在任何底色上都成立。
 */

.chat-msg__bubble :deep(.md__body) {
  font-size: var(--os-font-md);
  line-height: 1.72;
}

/*
 * 首末块距由 MarkdownRenderer 统一清零（`.md__html > :first-child / :last-child`），
 * 这里不再重复写 —— 原先这三条规则写的是 `.md__body > :first-child`，
 * 而 `.md__body` 的直接子元素是 v-html 包裹层，**它们从没生效过**。
 * 留着只会让下一个人以为已经处理了。
 */

/* 悬停操作行：默认隐形，不抢版面（FR-003） */
.chat-msg__actions {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
  margin-top: var(--os-space-1);
  opacity: 0;
  transition: opacity var(--os-duration-fast) var(--os-ease);
}

.chat-msg__actions--user {
  justify-content: flex-end;
}

.chat-msg:hover .chat-msg__actions,
.chat-msg:focus-within .chat-msg__actions {
  opacity: 1;
}

@media (hover: none) {
  .chat-msg__actions {
    opacity: 1;
  }
}

.chat-msg__time {
  color: var(--el-text-color-placeholder);
  font-size: var(--os-font-xs);
}

/*
 * 可点区 26×26（2026-10-29 改）。
 *
 * 阶段 23 曾把高度补到 24px、宽度靠文字撑开（「复制」42×25 / 「重新生成」66×25）。
 * 现在去掉文字只剩图标，若沿用原来的 padding 会缩成 18×25 的窄条 ——
 * 图标按钮**必须显式定宽**，否则宽度完全由 13px 图标决定。
 *
 * 26 而不是 24：去掉文字后它是唯一的命中区，比 EP 的 small 按钮（32）小、
 * 但比 24 更耐点（实测 24 在触控板上要瞄）。间距用 gap 控制，
 * 不用 padding 撑 —— 撑出来的空隙看着像「按钮更大」，实际可点区没变。
 */
.chat-msg__action {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border: none;
  border-radius: var(--os-radius-sm);
  background: none;
  color: var(--el-text-color-secondary);
  cursor: pointer;
  transition:
    background var(--os-duration-fast) var(--os-ease),
    color var(--os-duration-fast) var(--os-ease);
}

.chat-msg__action:hover {
  background: var(--el-fill-color-light);
  color: var(--el-color-primary);
}

/* 键盘聚焦要可见：悬停才浮现的按钮，Tab 到时若没反馈等于「看不见的焦点」 */
.chat-msg__action:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 1px;
}

/* 工具行在气泡上方：与气泡拉开一点距离，与上一条消息靠近（同属「这一轮」） */
.chat-msg__trail {
  margin-left: 0;
  margin-bottom: var(--os-space-2);
}

/* ---- 附件（阶段 22） ---- */

.chat-msg__attachments {
  display: flex;
  flex-wrap: wrap;
  gap: var(--os-space-2);
  margin-bottom: var(--os-space-2);
}

.chat-msg__attachment {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 220px;
  padding: 4px 8px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--os-radius-md);
  background: var(--el-bg-color);
  color: var(--el-text-color-regular);
  font-size: var(--os-font-xs);
  text-decoration: none;
}

.chat-msg__attachment:hover {
  border-color: var(--el-color-primary-light-5);
  color: var(--el-color-primary);
}

/* 图片缩略图：固定方块 + 裁切，避免长图把消息流撑歪 */
.chat-msg__attachment-image {
  width: 28px;
  height: 28px;
  border-radius: var(--os-radius-sm);
  object-fit: cover;
  flex: none;
}

.chat-msg__attachment-icon {
  color: var(--el-text-color-secondary);
}

.chat-msg__attachment-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ---- 引用区（阶段 19，随新布局收紧） ---- */

.chat-msg__citations {
  margin-top: var(--os-space-3);
}

.chat-msg__citations-title {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}

.chat-msg__citation {
  padding: var(--os-space-1) 0 var(--os-space-1) 10px;
  border-left: 3px solid var(--el-color-primary-light-5);
}

.chat-msg__citation + .chat-msg__citation {
  margin-top: var(--os-space-2);
}

.chat-msg__citation-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--os-space-2);
}

.chat-msg__citation-source {
  font-size: var(--os-font-xs);
  font-weight: 500;
}

.chat-msg__citation-meta {
  margin-left: var(--os-space-1);
  color: var(--el-text-color-secondary);
  font-weight: 400;
}

.chat-msg__citation-content {
  margin-top: var(--os-space-1);
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
  line-height: 1.5;
  max-height: 120px;
  overflow-y: auto;
  white-space: pre-wrap;
  word-break: break-word;
}

@media (max-width: 900px) {
  .chat-msg__content--user {
    max-width: 85%;
  }
}
</style>
