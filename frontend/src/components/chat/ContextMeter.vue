<script setup lang="ts">
import { computed } from 'vue'
import type { ContextUsage } from '@/api/chat'
import { budgetPresetsFor, formatTokens, meterLevelOf, meterPercentOf } from '@/lib/context-meter'

const props = defineProps<{ usage: ContextUsage }>()
const emit = defineEmits<{ (e: 'change-budget', tokens: number): void }>()
const meterPercent = computed(() => meterPercentOf(props.usage.promptTokens, props.usage.budgetTokens))
const meterLevel = computed(() => `context-meter__fill--${meterLevelOf(meterPercent.value)}`)
const unknownWindow = computed(() => props.usage.windowSource === 'fallback')
const sourceLabel = computed(() => {
  switch (props.usage.windowSource) {
    case 'configured':
      return '模型配置，尚未验证最大容量'
    case 'endpoint':
      return '端点超限响应声明（当前运行配置）'
    case 'catalog':
      return '内置参考，可能与端点部署不同'
    case 'fallback':
      return '窗口未识别，使用保护性默认预算；可在设置中查询或填写窗口'
    default:
      return '模型窗口'
  }
})
const limitText = computed(() =>
  unknownWindow.value
    ? '窗口未知'
    : `${props.usage.windowSource === 'catalog' ? '参考' : props.usage.windowSource === 'endpoint' ? '端点' : '配置'} ${formatTokens(props.usage.contextWindow)}`,
)
const calibrationLabel = computed(() => {
  if (props.usage.inputLimitSource === 'adaptive') return '超限后保守校准的输入预算，仍是估算'
  if (props.usage.inputLimitSource === 'endpoint') return '输入预算已按端点限制收紧'
  return ''
})
const budgetPresets = computed(() =>
  [...new Set([...budgetPresetsFor(props.usage.contextWindow, props.usage.inputLimitTokens), props.usage.budgetTokens])]
    .filter((value) => value > 0 && value <= (props.usage.inputLimitTokens ?? props.usage.contextWindow))
    .sort((a, b) => a - b),
)
const meterTitle = computed(() => {
  const u = props.usage
  return [
    `模型：${u.modelName || '未配置'}`,
    `${u.estimated ? '预计输入（估算）' : '上一轮实际输入'}：${u.promptTokens.toLocaleString()} token`,
    `当前输入预算：${u.budgetTokens.toLocaleString()} token`,
    ...(u.inputLimitTokens ? [`安全输入上限：${u.inputLimitTokens.toLocaleString()} token`] : []),
    unknownWindow.value ? '模型上限：未知' : `模型上限：${u.contextWindow.toLocaleString()} token`,
    `来源：${sourceLabel.value}`,
    ...(calibrationLabel.value ? [calibrationLabel.value] : []),
    (u.verifiedInputTokens ?? 0) > 0
      ? `本次运行已验证输入至少 ${u.verifiedInputTokens!.toLocaleString()} token；这是成功请求的下限，不是最大容量`
      : '本次运行尚无真实输入用量证据',
    ...((u.tokenEstimateRatio ?? 1) > 1 ? [`token 估算已按真实用量上调 ×${u.tokenEstimateRatio!.toFixed(2)}`] : []),
    ...(u.outputReserveTokens
      ? [
          `预留回复：${u.outputReserveTokens.toLocaleString()} token；安全余量：${(u.safetyMarginTokens ?? 0).toLocaleString()} token`,
        ]
      : []),
    '输入包含系统提示、工具定义、历史与本条消息；预算控制历史裁剪，不等同于模型上限。点击调整预算。',
  ].join('\n')
})
</script>

<template>
  <el-dropdown
    v-if="usage.budgetTokens > 0 && budgetPresets.length > 1"
    trigger="click"
    placement="top-end"
    @command="(value: number) => emit('change-budget', value)"
  >
    <button type="button" class="context-meter" :title="meterTitle" :aria-label="meterTitle">
      <span class="context-meter__track" aria-hidden="true">
        <span class="context-meter__fill" :class="meterLevel" :style="{ width: meterPercent + '%' }" />
      </span>
      <span class="context-meter__value"
        >{{ usage.estimated ? '≈' : '' }}{{ formatTokens(usage.promptTokens) }} /
        {{ formatTokens(usage.budgetTokens) }}</span
      >
      <span class="context-meter__limit">{{ limitText }}</span>
    </button>
    <template #dropdown>
      <el-dropdown-menu class="context-meter__menu">
        <div class="context-meter__head">
          <span class="context-meter__model">{{ usage.modelName || '当前模型' }}</span>
          <span>{{ unknownWindow ? '模型窗口未知' : `模型上限 ${usage.contextWindow.toLocaleString()} token` }}</span>
          <span class="context-meter__source">{{ sourceLabel }}</span>
          <span v-if="calibrationLabel" class="context-meter__source">{{ calibrationLabel }}</span>
          <span v-if="(usage.verifiedInputTokens ?? 0) > 0" class="context-meter__source"
            >已验证输入 ≥ {{ formatTokens(usage.verifiedInputTokens!) }}（非最大容量）</span
          >
          <span v-if="usage.outputReserveTokens" class="context-meter__source"
            >预留回复 {{ formatTokens(usage.outputReserveTokens) }} · 安全余量
            {{ formatTokens(usage.safetyMarginTokens ?? 0) }}</span
          >
        </div>
        <el-dropdown-item
          v-for="preset in budgetPresets"
          :key="preset"
          :command="preset"
          :class="{ 'context-meter__item--on': preset === usage.budgetTokens }"
        >
          输入预算 {{ formatTokens(preset) }}{{ preset === usage.budgetTokens ? ' · 当前' : '' }}
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
  <div v-else-if="usage.budgetTokens > 0" class="context-meter context-meter--static" :title="meterTitle">
    <span class="context-meter__value"
      >{{ usage.estimated ? '≈' : '' }}{{ formatTokens(usage.promptTokens) }} /
      {{ formatTokens(usage.budgetTokens) }}</span
    >
    <span class="context-meter__limit">{{ limitText }}</span>
  </div>
</template>

<style scoped>
.context-meter {
  display: inline-flex;
  align-items: center;
  flex: none;
  gap: var(--os-space-2);
  height: var(--os-space-6);
  padding: 0 var(--os-space-1);
  box-sizing: border-box;
  border: none;
  border-radius: var(--os-radius-sm);
  background: transparent;
  color: var(--el-text-color-secondary);
  font-family: inherit;
  font-size: var(--os-font-xs);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  cursor: pointer;
  transition: background var(--os-duration-fast) var(--os-ease);
}
.context-meter:hover {
  background: var(--el-fill-color-light);
}
.context-meter:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 2px;
}
.context-meter--static {
  cursor: default;
}
.context-meter--static:hover {
  background: transparent;
}
.context-meter__track {
  flex: none;
  width: var(--os-space-7);
  height: var(--os-space-1);
  border-radius: var(--os-radius-sm);
  background: var(--el-fill-color);
  overflow: hidden;
}
.context-meter__fill {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--el-color-primary);
}
.context-meter__fill--warn {
  background: var(--el-color-warning);
}
.context-meter__fill--over {
  background: var(--el-color-danger);
}
.context-meter__limit {
  padding-left: var(--os-space-2);
  border-left: 1px solid var(--el-border-color);
}
.context-meter__head {
  display: grid;
  gap: var(--os-space-1);
  max-width: 280px;
  padding: var(--os-space-2) var(--os-space-3);
  color: var(--el-text-color-regular);
  font-size: var(--os-font-xs);
  line-height: 1.5;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.context-meter__model {
  overflow-wrap: anywhere;
  font-weight: 600;
}
.context-meter__source {
  color: var(--el-text-color-secondary);
}
.context-meter__item--on {
  color: var(--el-color-primary);
  font-weight: 600;
}
@media (prefers-reduced-motion: reduce) {
  .context-meter {
    transition: none;
  }
}
</style>
