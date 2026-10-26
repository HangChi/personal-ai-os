<script setup lang="ts">
/**
 * 上下文仪表盘（阶段 22，从 ChatComposer 抽出）。
 *
 * <h2>为什么放在消息区头部而不是输入区工具栏</h2>
 * 它是**状态读数**（这一轮带了什么），而工具栏里都是**操作**（附件/知识库/条数）。
 * 把读数混进操作行，两边都变丑：操作被挤到左边，读数孤零零挂在右边。
 * 移到卡片头部与「N 条消息」并列，语义上同属「这个会话的状态」。
 *
 * <p>点它可以改预算 —— 预算同时是窗口裁剪阈值，所以必须落到服务端。
 */
import { computed } from 'vue'

import type { ContextUsage } from '@/api/chat'

const props = defineProps<{
  usage: ContextUsage
}>()

const emit = defineEmits<{
  (e: 'change-budget', tokens: number): void
}>()

const meterPercent = computed(() => {
  if (props.usage.budgetTokens <= 0) {
    return 0
  }
  return Math.min(100, Math.round((props.usage.promptTokens / props.usage.budgetTokens) * 100))
})

/** 超 85% 转琥珀、超 100% 转红：颜色是「该收尾了」的预警，不是装饰 */
const meterLevel = computed(() => {
  const percent = meterPercent.value
  if (percent >= 100) {
    return 'context-meter__fill--over'
  }
  if (percent >= 85) {
    return 'context-meter__fill--warn'
  }
  return ''
})

const meterText = computed(
  () => `${formatTokens(props.usage.promptTokens)} / ${formatTokens(props.usage.budgetTokens)}`,
)

/**
 * 预算档位：固定几个整齐值，按模型窗口过滤。
 * 「满窗」永远在列 —— 配置的窗口不一定是整齐数，只靠固定档位会漏掉「用满模型能力」。
 */
const budgetPresets = computed(() => {
  const window = props.usage.contextWindow
  if (window <= 0) {
    return []
  }
  const candidates = [32_768, 65_536, 131_072, 524_288, 1_048_576, Math.floor(window / 1024) * 1024]
  return [...new Set(candidates)].filter((value) => value >= 1024 && value <= window).sort((a, b) => a - b)
})

/**
 * token 可读化：按 1024 进制（32768 → 32K，1048576 → 1M）。
 * 用 1000 进制会把 32768 显示成「32.8K」—— 档位名带小数点很难看。
 */
function formatTokens(value: number): string {
  if (value >= 1024 * 1024) {
    const m = value / (1024 * 1024)
    return `${Number.isInteger(m) ? m : m.toFixed(1)}M`
  }
  if (value >= 1024) {
    const k = value / 1024
    return `${Number.isInteger(k) ? k : k.toFixed(1)}K`
  }
  return String(value)
}

const meterTitle = computed(() => {
  if (props.usage.budgetTokens <= 0) {
    return '本轮尚未产生上下文用量'
  }
  return (
    `本轮预计送入模型约 ${formatTokens(props.usage.promptTokens)} token，预算 ${formatTokens(props.usage.budgetTokens)}` +
    `（模型上限 ${formatTokens(props.usage.contextWindow)}）。\n` +
    '构成：系统提示（人设 + 会话摘要 + 长期记忆 + 检索片段）+ 窗口内历史 + 本条消息。\n' +
    '超预算后更早的历史会被整体丢弃，不切半条。点击可改预算。'
  )
})
</script>

<template>
  <el-dropdown
    v-if="usage.budgetTokens > 0"
    trigger="click"
    placement="bottom-end"
    @command="(value: number) => emit('change-budget', value)"
  >
    <div class="context-meter" :title="meterTitle">
      <span class="context-meter__label">上下文</span>
      <span class="context-meter__track">
        <span class="context-meter__fill" :class="meterLevel" :style="{ width: meterPercent + '%' }" />
      </span>
      <span class="context-meter__value">{{ meterText }}</span>
    </div>
    <template #dropdown>
      <el-dropdown-menu class="context-meter__menu">
        <div class="context-meter__head">上下文预算 · 模型上限 {{ formatTokens(usage.contextWindow) }}</div>
        <el-dropdown-item
          v-for="preset in budgetPresets"
          :key="preset"
          :command="preset"
          :class="{ 'context-meter__item--on': preset === usage.budgetTokens }"
        >
          {{ formatTokens(preset) }}
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<style scoped>
/* 头部状态读数：弱化到「一行小字 + 细条」，不抢标题 */
.context-meter {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
}

.context-meter__label {
  color: var(--el-text-color-placeholder);
  font-size: var(--os-font-xs);
}

.context-meter__track {
  display: inline-block;
  width: 48px;
  height: 4px;
  border-radius: 999px;
  background: var(--el-fill-color);
  overflow: hidden;
}

.context-meter__fill {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: var(--el-color-primary);
  transition: width var(--os-duration-normal) var(--os-ease);
}

.context-meter__fill--warn {
  background: var(--el-color-warning);
}

.context-meter__fill--over {
  background: var(--el-color-danger);
}

.context-meter__value {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
  font-variant-numeric: tabular-nums;
}

.context-meter__menu {
  min-width: 148px;
}

.context-meter__head {
  padding: 6px 12px 4px;
  color: var(--el-text-color-placeholder);
  font-size: var(--os-font-xs);
}

.context-meter__item--on {
  color: var(--el-color-primary);
  font-weight: 500;
}
</style>
