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

import MarkdownRenderer from '@/components/MarkdownRenderer.vue'
import { relativeTime } from '@/lib/relative-time'
import type { ChatMessage } from '@/types/api'

defineProps<{ message: ChatMessage }>()

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

.chat-msg__avatar {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  margin-top: 2px;
  border-radius: 50%;
}

.chat-msg__avatar--assistant {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}

.chat-msg__avatar--user {
  background: var(--el-color-primary);
  color: var(--el-color-white);
}

.chat-msg__content {
  min-width: 0;
  flex: 1;
  font-size: var(--os-font-sm);
  line-height: 1.75;
}

/*
 * 可读性上限：不限制宽度时，一条长回答在 1440px 屏上能拉到 130+ 字符/行，
 * 视线换行容易串行。760px 约合中文 38~42 字，是长文阅读的舒适区间。
 */
.chat-msg__content {
  max-width: 760px;
}

.chat-msg__content--user {
  flex: 0 1 auto;
  max-width: 82%;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.chat-msg__bubble {
  padding: var(--os-space-3) var(--os-space-4);
  border-radius: var(--os-radius-lg);
  /* 气泡内的 Markdown 自带段距，这里不再叠一层 */
  width: fit-content;
  max-width: 100%;
}

/*
 * 助手气泡：浅填充底，不加边框。
 * 改造前助手消息**没有容器**——文字直接铺在页面白底上，与背景糊成一片，
 * 是「对话页很丑」最主要的一条。给一层 fill 之后，用户/助手、引用、正文的边界才分明。
 */
.chat-msg__bubble--assistant {
  background: var(--el-fill-color-light);
  /* 圆角朝向头像那一侧收窄：气泡的「尖角」暗示说话人方向 */
  border-bottom-left-radius: var(--os-radius-sm);
}

.chat-msg__bubble :deep(.md__body) {
  font-size: var(--os-font-sm);
  line-height: 1.75;
}

.chat-msg__bubble :deep(.md__body > :first-child) {
  margin-top: 0;
}

.chat-msg__bubble :deep(.md__body > :last-child) {
  margin-bottom: 0;
}

/*
 * 用户气泡：浅主色底 + 主文本色。
 * 改造前是「浅蓝底 + 主色蓝字」，蓝字落在浅蓝底上对比度不足，长句读起来发虚；
 * 换成深色文字后对比度足够，同时保留主色底以区分说话方。
 */
.chat-msg__bubble--user {
  background: var(--el-color-primary-light-8);
  color: var(--el-text-color-primary);
  border-bottom-right-radius: var(--os-radius-sm);
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
  font-size: 11px;
}

.chat-msg__action {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 0;
  border: none;
  background: none;
  color: var(--el-text-color-secondary);
  font-size: 11px;
  cursor: pointer;
}

.chat-msg__action:hover {
  color: var(--el-color-primary);
}

/* ---- 引用区（阶段 19，随新布局收紧） ---- */

.chat-msg__citations {
  margin-top: var(--os-space-3);
}

.chat-msg__citations-title {
  color: var(--el-text-color-secondary);
  font-size: 11px;
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
  font-size: 11px;
  font-weight: 500;
}

.chat-msg__citation-meta {
  margin-left: var(--os-space-1);
  color: var(--el-text-color-secondary);
  font-weight: 400;
}

.chat-msg__citation-content {
  margin-top: 2px;
  color: var(--el-text-color-secondary);
  font-size: 11px;
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
