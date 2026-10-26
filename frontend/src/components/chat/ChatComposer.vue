<script setup lang="ts">
/**
 * 对话输入区（从 ChatView 拆出，2026-10-26）。
 *
 * <p>包含三样东西：知识库增强 chip（含条数）、输入框、发送/停止。
 * 键盘行为在这里实现而不是父组件，因为它完全由「输入框聚焦时的按键」触发：
 *
 * <ul>
 *   <li>{@code enterSend=true}（默认）：Enter 发送，Shift+Enter 换行</li>
 *   <li>{@code enterSend=false}：Enter 换行，Ctrl/Cmd+Enter 发送</li>
 *   <li>输入法选词期间的 Enter 一律不发送（否则打不了中文）</li>
 * </ul>
 */
import { nextTick, onMounted, ref, watch } from 'vue'

const props = defineProps<{
  /** 输入内容（v-model） */
  modelValue: string
  /** 是否正在生成：决定显示「停止」还是「发送」 */
  streaming: boolean
  /** 知识库增强开关 */
  knowledgeOn: boolean
  /** 检索条数 */
  topK: number
  /** Enter 是否直接发送（来自设置页偏好） */
  enterSend: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
  (e: 'update:topK', value: number | null): void
  (e: 'toggle-knowledge', next: boolean): void
  (e: 'send'): void
  (e: 'stop'): void
}>()

/**
 * 输入框随内容长高（上限 160px）。
 *
 * <p>固定 3 行有两个问题：短消息时输入框显得空荡，长消息时 3 行装不下、
 * 中途还得手动拖（已禁用 resize）。改成 2 行起步、按 scrollHeight 增长，
 * 超过上限后再内部滚动 —— 与主流聊天工具的手感一致。
 */
const root = ref<HTMLElement | null>(null)

function autosize(): void {
  const element = root.value?.querySelector('textarea')
  if (!element) {
    return
  }
  element.style.height = 'auto'
  element.style.height = `${Math.min(element.scrollHeight, 160)}px`
}

watch(
  () => props.modelValue,
  async () => {
    await nextTick()
    autosize()
  },
)

// 挂载时也算一次：从「点击建议问题」进来的内容可能一进来就不止两行
onMounted(autosize)

function handleKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Enter' || event.isComposing) {
    // isComposing：中文输入法选词期间的 Enter 不触发发送
    return
  }
  if (props.enterSend) {
    if (!event.shiftKey && !event.ctrlKey && !event.metaKey) {
      event.preventDefault()
      emit('send')
    }
  } else if (event.ctrlKey || event.metaKey) {
    event.preventDefault()
    emit('send')
  }
}
</script>

<template>
  <div class="chat-composer">
    <div class="chat-composer__toolbar">
      <button
        type="button"
        class="chat-composer__chip"
        :class="{ 'chat-composer__chip--on': knowledgeOn }"
        :title="knowledgeOn ? '点击关闭知识库增强' : '开启后每条消息先检索知识库再回答'"
        @click="emit('toggle-knowledge', !knowledgeOn)"
      >
        <span class="chat-composer__chip-dot" />
        知识库增强
        <template v-if="knowledgeOn">· {{ topK }} 条</template>
      </button>
      <span v-if="knowledgeOn" class="chat-composer__topk">
        条数
        <el-input-number
          :model-value="topK"
          :min="1"
          :max="20"
          size="small"
          controls-position="right"
          @update:model-value="(value: number | undefined) => emit('update:topK', value ?? null)"
        />
      </span>
      <span class="chat-composer__hint">
        {{ enterSend ? 'Enter 发送 · Shift+Enter 换行' : 'Ctrl+Enter 发送 · Enter 换行' }}
      </span>
    </div>
    <div ref="root" class="chat-composer__input">
      <el-input
        :model-value="modelValue"
        type="textarea"
        :rows="2"
        maxlength="4000"
        resize="none"
        :placeholder="knowledgeOn ? '输入消息，将基于知识库回答' : '输入消息'"
        @update:model-value="(value: string) => emit('update:modelValue', value)"
        @keydown="handleKeydown"
      />
      <div class="chat-composer__actions">
        <span class="chat-composer__count">{{ modelValue.length }} / 4000</span>
        <el-button v-if="streaming" @click="emit('stop')">停止</el-button>
        <el-button v-else type="primary" :disabled="modelValue.trim().length === 0" @click="emit('send')">
          发送
        </el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* ---- 停靠输入区 ---- */

.chat-composer {
  flex: none;
  padding: var(--os-space-3) var(--os-space-5) var(--os-space-4);
  border-top: 1px solid var(--el-border-color-lighter);
  background: var(--el-bg-color);
}

.chat-composer__toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--os-space-3);
  margin-bottom: var(--os-space-2);
}

.chat-composer__chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 10px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 999px;
  background: var(--el-bg-color);
  color: var(--el-text-color-secondary);
  /* 11px 在多数屏幕上偏小，chip 是可点击控件，按令牌升到 12px */
  font-size: var(--os-font-xs);
  cursor: pointer;
  transition:
    border-color var(--os-duration-fast) var(--os-ease),
    color var(--os-duration-fast) var(--os-ease);
}

.chat-composer__chip-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--el-text-color-placeholder);
}

.chat-composer__chip--on {
  border-color: var(--el-color-primary-light-5);
  color: var(--el-color-primary);
}

.chat-composer__chip--on .chat-composer__chip-dot {
  background: var(--el-color-primary);
}

.chat-composer__topk {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--el-text-color-secondary);
  font-size: 11px;
}

.chat-composer__topk :deep(.el-input-number) {
  width: 76px;
}

.chat-composer__hint {
  margin-left: auto;
  color: var(--el-text-color-placeholder);
  font-size: var(--os-font-xs);
}

/* ---- 输入框 ---- */

.chat-composer__input :deep(.el-textarea__inner) {
  padding: var(--os-space-2) var(--os-space-3);
  border-radius: var(--os-radius-lg);
  font-size: var(--os-font-sm);
  line-height: 1.6;
  /* 两行文本的高度；autosize 会在此基础上按内容上调 */
  min-height: 44px;
  box-shadow: none;
}

.chat-composer__actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--os-space-3);
  margin-top: var(--os-space-2);
}

.chat-composer__count {
  margin-right: auto;
  color: var(--el-text-color-placeholder);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}
</style>
