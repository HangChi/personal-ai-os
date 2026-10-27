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
import { ChatLineRound, CopyDocument, User } from '@element-plus/icons-vue'

import { Document } from '@element-plus/icons-vue'

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

const emit = defineEmits<{ (e: 'copy', content: string): void }>()
</script>

<template>
  <div :class="['chat-msg', `chat-msg--${message.role.toLowerCase()}`]">
    <!-- 助手：头像在左，内容直排 -->
    <template v-if="message.role !== 'USER'">
      <span class="chat-msg__avatar chat-msg__avatar--assistant">
        <el-icon :size="14"><ChatLineRound /></el-icon>
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
          <button type="button" class="chat-msg__action" @click="emit('copy', message.content)">
            <el-icon :size="11"><CopyDocument /></el-icon>
            复制
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
          <button type="button" class="chat-msg__action" @click="emit('copy', message.content)">
            <el-icon :size="11"><CopyDocument /></el-icon>
            复制
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

/* 用户头像与气泡同为实底主色，改用白底主色图标避免「两团同色块糊在一起」 */
.chat-msg__avatar--user {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  box-shadow: inset 0 0 0 1px var(--el-color-primary-light-7);
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
   */
  padding: 0.75em 1em;
  border-radius: var(--os-radius-2xl);
  /* 气泡内的 Markdown 自带段距，这里不再叠一层 */
  width: fit-content;
  max-width: 100%;
}

/*
 * 助手气泡：白底 + 极轻阴影，不加边框。
 *
 * 改造前是「浅灰底 #f6f7fa + 1px 边框 #eceff4」——底色与卡片白只差 1.03:1，
 * 等于靠一根几乎看不见的线撑边界，读起来是「灰扑扑的一块」。双重弱化不如单点强化：
 * 白底让内容自己立住，阴影给一层脱离感。暗色下底色由 --el-bg-color 自动换成深色。
 */
.chat-msg__bubble--assistant {
  background: var(--el-bg-color);
  box-shadow: var(--os-shadow-bubble);
  /* 圆角朝向头像那一侧收窄：气泡的「尖角」暗示说话人方向 */
  border-bottom-left-radius: var(--os-radius-xs);
}

/*
 * 用户气泡：主色实底 + 高对比前景。
 *
 * 改造前是「浅主色底 + 深字」，与助手气泡同属浅色系，两者只靠色相区分——
 * 一屏里十几轮往返时，视觉上分不出「谁在说话」。改成实底后：
 * ①方向一眼可辨 ②自己说的话自然成为视觉重点（这是对话里最常回看的内容）。
 *
 * 前景色用 --os-on-primary 而不是写死 #fff：暗色主色是**提亮**后的 #818cf8，
 * 白字只有 2.98:1（实测），必须配深字。这个变量在两套主题下各有一个正确值。
 */
.chat-msg__bubble--user {
  background: var(--el-color-primary);
  color: var(--os-on-primary);
  border-bottom-right-radius: var(--os-radius-xs);
}

/*
 * 用户气泡内的 Markdown 要整体反色。
 *
 * 规则不能只写 color —— MarkdownRenderer 里有 19 处 var(--el-*) 取色
 * （行内代码底、引用块底、表格边框、链接色……），它们全按「浅底深字」调的。
 * 直接套实底会出现「深底上的浅灰行内代码」这类几乎看不见的组合，
 * 因此把这些变量在气泡作用域内就地重绑。
 *
 * 半透明白而不是另配一组实色：换主色时自动跟随，不用维护第二套调色板。
 */
.chat-msg__bubble--user :deep(.md__body) {
  color: var(--os-on-primary);
  --el-text-color-primary: var(--os-on-primary);
  --el-text-color-regular: rgb(255 255 255 / 92%);
  --el-text-color-secondary: rgb(255 255 255 / 78%);
  --el-text-color-placeholder: rgb(255 255 255 / 62%);
  --el-fill-color: rgb(255 255 255 / 18%);
  --el-fill-color-light: rgb(255 255 255 / 14%);
  --el-fill-color-lighter: rgb(255 255 255 / 10%);
  --el-border-color-lighter: rgb(255 255 255 / 24%);
  --el-border-color-light: rgb(255 255 255 / 30%);
  /* 链接与强调：主色在白底上是深蓝，在实底上必须提亮才看得见 */
  --el-color-primary: var(--os-on-primary);
  --el-color-primary-light-5: rgb(255 255 255 / 55%);
}

/*
 * 暗色例外：主色实底变亮（#818cf8）后，上面那套「半透明白」全部失效 ——
 * 白字压亮底本来就只有 2.98:1，再半透明更看不见。暗色下改用「半透明黑」。
 */
html.dark .chat-msg__bubble--user :deep(.md__body) {
  --el-text-color-regular: rgb(22 24 42 / 88%);
  --el-text-color-secondary: rgb(22 24 42 / 72%);
  --el-text-color-placeholder: rgb(22 24 42 / 56%);
  --el-fill-color: rgb(22 24 42 / 12%);
  --el-fill-color-light: rgb(22 24 42 / 10%);
  --el-fill-color-lighter: rgb(22 24 42 / 6%);
  --el-border-color-lighter: rgb(22 24 42 / 18%);
  --el-border-color-light: rgb(22 24 42 / 24%);
  --el-color-primary-light-5: rgb(22 24 42 / 50%);
}

/*
 * 代码块不需要在这里特判：它已改用 --os-code-* 独立令牌（见 MarkdownRenderer），
 * 永远是「一块浅色卡片」，放在实底气泡上会自动成立。
 */

.chat-msg__bubble :deep(.md__body) {
  font-size: var(--os-font-md);
  line-height: 1.72;
}

.chat-msg__bubble :deep(.md__body > :first-child) {
  margin-top: 0;
}

.chat-msg__bubble :deep(.md__body > :last-child) {
  margin-bottom: 0;
}

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
 * 可点区补到 24px 高（阶段 23）：原先 padding:0 + 12px 字，实测只有 38×17px，
 * 低于最小可点尺寸——「复制」是悬停才浮现的按钮，本来就难命中。
 * 用负外边距抵消多出来的留白，视觉位置不变。
 */
.chat-msg__action {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  min-height: 24px;
  margin: -4px 0;
  padding: 4px 2px;
  border: none;
  border-radius: var(--os-radius-sm);
  background: none;
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
  cursor: pointer;
}

.chat-msg__action:hover {
  color: var(--el-color-primary);
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
