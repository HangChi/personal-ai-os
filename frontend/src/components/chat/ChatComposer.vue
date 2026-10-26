<script setup lang="ts">
/**
 * 对话输入区（从 ChatView 拆出，2026-10-26；阶段 22 增加思考程度）。
 *
 * <p>包含：知识库增强开关（含条数）、思考程度、输入框、发送/停止。
 * 键盘行为在这里实现而不是父组件，因为它完全由「输入框聚焦时的按键」触发：
 *
 * <ul>
 *   <li>{@code enterSend=true}（默认）：Enter 发送，Shift+Enter 换行</li>
 *   <li>{@code enterSend=false}：Enter 换行，Ctrl/Cmd+Enter 发送</li>
 *   <li>输入法选词期间的 Enter 一律不发送（否则打不了中文）</li>
 * </ul>
 */
import { Reading } from '@element-plus/icons-vue'
import { nextTick, onMounted, ref, watch } from 'vue'

import { THINKING_HINTS, THINKING_LABELS, type ThinkingLevel } from '@/lib/settings'

const props = defineProps<{
  /** 输入内容（v-model） */
  modelValue: string
  /** 是否正在生成：决定显示「停止」还是「发送」 */
  streaming: boolean
  /** 知识库增强开关 */
  knowledgeOn: boolean
  /** 检索条数 */
  topK: number
  /** 模型思考程度 */
  thinkingLevel: ThinkingLevel
  /** Enter 是否直接发送（来自设置页偏好） */
  enterSend: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
  (e: 'update:topK', value: number | null): void
  (e: 'update:thinkingLevel', value: ThinkingLevel): void
  (e: 'toggle-knowledge', next: boolean): void
  (e: 'send'): void
  (e: 'stop'): void
}>()

/** 条数下拉的候选项：常用值 + 上限，避免用户为了填 20 而点十下步进器 */
const TOP_K_OPTIONS = [2, 4, 6, 8, 12, 20]

/** 思考档位（顺序即展示顺序） */
const THINKING_LEVELS: ThinkingLevel[] = ['off', 'standard', 'deep']

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

/**
 * 输入框随内容长高（上限 160px）。
 *
 * <p>固定行数有两个问题：短消息时输入框显得空荡，长消息时行数装不下、
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
        <el-icon :size="12"><Reading /></el-icon>
        知识库增强
      </button>

      <!--
        条数改成下拉而不是数字步进器：步进器为了选 20 要点十下，且在窄工具栏里
        两侧按钮把数字挤成一条缝。下拉一次点选，宽度也固定。
      -->
      <el-select
        v-if="knowledgeOn"
        class="chat-composer__topk"
        :model-value="topK"
        size="small"
        title="检索条数"
        @change="(value: number) => emit('update:topK', value)"
      >
        <el-option v-for="option in TOP_K_OPTIONS" :key="option" :label="`${option} 条`" :value="option" />
      </el-select>

      <div class="chat-composer__thinking">
        <span class="chat-composer__thinking-label">思考</span>
        <el-radio-group
          :model-value="thinkingLevel"
          size="small"
          @change="(value: ThinkingLevel) => emit('update:thinkingLevel', value)"
        >
          <el-radio-button v-for="level in THINKING_LEVELS" :key="level" :value="level">
            {{ THINKING_LABELS[level] }}
          </el-radio-button>
        </el-radio-group>
        <span class="chat-composer__thinking-hint">{{ THINKING_HINTS[thinkingLevel] }}</span>
      </div>
    </div>

    <div ref="root" class="chat-composer__box">
      <el-input
        :model-value="modelValue"
        class="chat-composer__textarea"
        type="textarea"
        :rows="2"
        maxlength="4000"
        resize="none"
        :placeholder="knowledgeOn ? '输入消息，将基于知识库回答' : '输入消息'"
        @update:model-value="(value: string) => emit('update:modelValue', value)"
        @keydown="handleKeydown"
      />
      <!--
        操作行进框内：改造前是「裸 textarea + 下方一排控件」，三块各占一行、
        互相没有归属感。合成一个带边界的输入框后，聚焦高亮、字数、按钮都收在里面，
        与主流聊天工具的结构一致。
      -->
      <div class="chat-composer__footer">
        <span class="chat-composer__hint">
          {{ enterSend ? 'Enter 发送 · Shift+Enter 换行' : 'Ctrl+Enter 发送 · Enter 换行' }}
        </span>
        <span class="chat-composer__count">{{ modelValue.length }} / 4000</span>
        <el-button v-if="streaming" round @click="emit('stop')">停止</el-button>
        <el-button v-else type="primary" round :disabled="modelValue.trim().length === 0" @click="emit('send')">
          发送
        </el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.chat-composer {
  flex: none;
  padding: var(--os-space-3) var(--os-space-5) var(--os-space-4);
  border-top: 1px solid var(--el-border-color-lighter);
  background: var(--el-bg-color);
}

/* ---- 工具行 ---- */

.chat-composer__toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--os-space-2);
  margin-bottom: var(--os-space-2);
}

.chat-composer__chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 10px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 999px;
  background: var(--el-bg-color);
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
  cursor: pointer;
  transition:
    border-color var(--os-duration-fast) var(--os-ease),
    background var(--os-duration-fast) var(--os-ease),
    color var(--os-duration-fast) var(--os-ease);
}

.chat-composer__chip:hover {
  border-color: var(--el-color-primary-light-5);
  color: var(--el-color-primary);
}

/* 开启态必须有底色：只描边的话，在浅底上「开没开」基本看不出来 */
.chat-composer__chip--on {
  border-color: var(--el-color-primary-light-5);
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}

.chat-composer__topk {
  width: 78px;
}

.chat-composer__topk :deep(.el-select__wrapper) {
  box-shadow: none;
}

.chat-composer__thinking {
  display: inline-flex;
  align-items: center;
  gap: var(--os-space-2);
  margin-left: auto;
}

.chat-composer__thinking-label {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}

/* 档位说明是「为什么慢」的唯一解释，窄屏放不下就先牺牲它 */
.chat-composer__thinking-hint {
  color: var(--el-text-color-placeholder);
  font-size: var(--os-font-xs);
}

@media (max-width: 1200px) {
  .chat-composer__thinking-hint {
    display: none;
  }
}

/* ---- 输入框（带边界的外盒 + 内部无框文本域） ---- */

.chat-composer__box {
  border: 1px solid var(--el-border-color);
  border-radius: var(--os-radius-xl);
  background: var(--el-bg-color);
  padding: var(--os-space-2) var(--os-space-3);
  transition:
    border-color var(--os-duration-fast) var(--os-ease),
    box-shadow var(--os-duration-fast) var(--os-ease);
}

/* 聚焦高亮落在外盒上：控件内部各自的 focus 描边会被收掉，避免出现两层框 */
.chat-composer__box:focus-within {
  border-color: var(--el-color-primary);
  box-shadow: 0 0 0 3px var(--el-color-primary-light-9);
}

.chat-composer__textarea :deep(.el-textarea__inner) {
  padding: var(--os-space-1) 2px;
  border: none;
  background: transparent;
  box-shadow: none;
  font-size: var(--os-font-sm);
  line-height: 1.6;
  /* 两行文本的高度；autosize 会在此基础上按内容上调 */
  min-height: 44px;
}

.chat-composer__footer {
  display: flex;
  align-items: center;
  gap: var(--os-space-3);
  margin-top: var(--os-space-1);
}

.chat-composer__footer .chat-composer__hint {
  margin-right: auto;
  color: var(--el-text-color-placeholder);
  font-size: var(--os-font-xs);
}

.chat-composer__count {
  color: var(--el-text-color-placeholder);
  font-size: var(--os-font-xs);
  font-variant-numeric: tabular-nums;
}
</style>
