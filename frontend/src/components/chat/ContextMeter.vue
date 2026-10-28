<script setup lang="ts">
/**
 * 上下文仪表盘（阶段 22，从 ChatComposer 抽出）。
 *
 * <h2>它现在的落点：消息区卡片头部，与「N 条消息」并列</h2>
 * 语义上同属「这个会话的状态」：攒了多少上下文、超预算后会裁掉更早的历史。
 *
 * <h2>为什么不在输入区工具栏（2026-10-28 实测后撤回）</h2>
 * 它曾在工具栏。实测它带 gap 后占 168px，把工具栏右半段撑到 376px：
 * 1280px 视口下 toolbar 可用 692px，左侧标签组（附件 63 + 知识库 99 + 条数 78
 * + 模型下拉 160 + 4 个 gap）= 338px，338 + 8 + 376 = 722 > 692 —— **必然折行**，
 * 且折行后第一行只占 338px、右侧空出 354px，形成比改造前更难看的「左密右空」。
 * 移走后右半段降到 200px，1280px 回到一行。
 *
 * <p>判断依据是**宽度**而不是「好不好看」：160px 的读数在 436px 宽的窄屏
 * 工具栏里就是 37% 的面积，无论怎么摆都会挤走别的控件。
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
/*
 * 读数：弱化到「一行小字 + 细条」，不抢输入区正文。
 *
 * <h3>为什么没有 `margin: -6px 0` 了</h3>
 * 那对负外边距是它住在**卡片头部**时写的：头部行高紧、需要它「不占高度地
 * 溢出到上下留白里」以贴边。搬进发送框后，它所在的 flex 行是
 * `align-items: center`，负外边距会让元素**上下各溢出 6px**，把行高从
 * 24px 撑到 36px，整行中心线与右侧 chip 差 6px（实测）。
 * 为了让点击热区够大，改成显式 `height` + `padding`：热区 24px，
 * 盒子也正好 24px，不再溢出。
 */
.context-meter {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 24px;
  padding: 0 2px;
  box-sizing: border-box;
  border-radius: var(--os-radius-sm);
  cursor: pointer;
}

.context-meter:hover {
  background: var(--el-fill-color-light);
}

/* 上下文用量是「要不要压缩」的判断依据，用 secondary 而不是 placeholder */
.context-meter__label {
  color: var(--el-text-color-secondary);
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
