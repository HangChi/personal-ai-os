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
   */
  padding: 0.75em 1em;
  border-radius: var(--os-radius-2xl);
  /* 气泡内的 Markdown 自带段距，这里不再叠一层 */
  width: fit-content;
  max-width: 100%;
}

/*
 * 助手气泡：浅底 + 明确边框 + 极轻阴影，三者并存。
 *
 * 这里有一段弯路值得记下：阶段 24 我曾把底色和边框当成「双重弱化」，
 * 改成白底+阴影——结果对话卡片本身就是白底，气泡对比度 1.000，整个消失了。
 * 实测结论：单靠底色最深只有 1.17（看不出体积），单靠边框要到 1.8 才成轮廓，
 * 所以它们是**互补**关系，缺一不可。
 */
.chat-msg__bubble--assistant {
  background: var(--os-bubble-bg);
  border: 1px solid var(--os-bubble-border);
  box-shadow: var(--os-shadow-bubble);
  /* 圆角朝向头像那一侧收窄：气泡的「尖角」暗示说话人方向 */
  border-bottom-left-radius: var(--os-radius-xs);
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
 */
.chat-msg__bubble--user {
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
