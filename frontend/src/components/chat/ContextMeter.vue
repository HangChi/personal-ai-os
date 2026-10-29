<script setup lang="ts">
/**
 * 上下文读数（阶段 22；2026-10-29 改为与字数同规格的一行读数）。
 *
 * <h2>它现在的落点：输入区第一行，紧挨字数统计</h2>
 * 这一行是「本轮的生成参数 + 用量」：模型名、字数、上下文。
 * 三者都是**读数**（只读信息），规格必须一致 —— 12px、24px 热区、
 * placeholder 灰、tabular-nums。改造前它在消息卡片头部，与标题/消息数并列，
 * 于是同一份用量信息在两个位置各有样式，漂移不可避免。
 *
 * <h2>为什么去掉了「上下文」这三个字的标签</h2>
 * 进度条 + `12K / 32K` 这个形状本身已经说明了「用掉多少 / 总量多少」。
 * 加一个「上下文」标签占 36px，换来的是「这一行的第 3 项也是上下文」
 * 这条用户看一眼就懂的信息 —— 而且它让这个读数比右边的字数宽一倍，
 * 一行里出现两个明显不同宽度的数字块。
 *
 * <p>标签没了，语义靠两处补：`title` 里有完整解释（构成、超预算后怎么裁），
 * 下拉菜单的表头写着「上下文预算 · 模型上限 32K」。
 *
 * <h2>为什么保留进度条而不只显示数字</h2>
 * 数字给精确值，进度条给**一眼可判的相对量**。用户真正要判断的是
 * 「是不是快满了」，而那是个比例问题 —— 纯数字需要心算 `12K / 32K = 37%`。
 */
import { computed } from 'vue'

import type { ContextUsage } from '@/api/chat'
import { budgetPresetsFor, formatTokens, meterLevelOf, meterPercentOf } from '@/lib/context-meter'

const props = defineProps<{
  usage: ContextUsage
}>()

const emit = defineEmits<{
  (e: 'change-budget', tokens: number): void
}>()

const meterPercent = computed(() => meterPercentOf(props.usage.promptTokens, props.usage.budgetTokens))

/** 超 85% 转琥珀、超 100% 转红：颜色是「该收尾了」的预警，不是装饰 */
const meterLevel = computed(() => {
  switch (meterLevelOf(meterPercent.value)) {
    case 'over':
      return 'context-meter__fill--over'
    case 'warn':
      return 'context-meter__fill--warn'
    default:
      return ''
  }
})

const meterText = computed(
  () => `${formatTokens(props.usage.promptTokens)} / ${formatTokens(props.usage.budgetTokens)}`,
)

/**
 * 预算档位：固定几个整齐值，按模型窗口过滤；小窗口额外补一档「窗口的一半」。
 * 具体规则与「为什么只有一个档位时菜单等于坏了」见 {@link budgetPresetsFor}。
 */
const budgetPresets = computed(() => budgetPresetsFor(props.usage.contextWindow))

/**
 * 悬浮说明：完整解释三个数字各是什么、超预算后怎么裁。
 *
 * <p>它承担了「标签被删掉」之后的全部语义（见文件头注释）——
 * 所以三行都必须留：第一行是读数含义，第二行是**构成**（用户好奇
 * 「这 12K 是从哪来的」时唯一的答案），第三行是超预算的行为。
 */
const meterTitle = computed(() => {
  if (props.usage.budgetTokens <= 0) {
    return '本轮尚未产生上下文用量'
  }
  // 读数来源要如实区分：有真实 usage 时它是**上一轮实际**送出的量（含工具定义），
  // 没有时才是估算。把两者都说成「预计」，用户就永远不知道哪个能拿去对账。
  const headline = props.usage.estimated
    ? `本轮预计送入模型约 ${formatTokens(props.usage.promptTokens)} token`
    : `上一轮实际送入模型 ${formatTokens(props.usage.promptTokens)} token`
  return (
    `${headline}，预算 ${formatTokens(props.usage.budgetTokens)}` +
    `（模型上限 ${formatTokens(props.usage.contextWindow)}）。\n` +
    // 工具定义必须写进构成里：实测它占 4K 左右，是「读数比想象中大」的主要原因。
    // 漏掉它，用户会以为是历史或记忆算多了。
    '构成：系统提示（人设 + 会话摘要 + 长期记忆 + 检索片段）+ 工具定义 + 窗口内历史 + 本条消息。\n' +
    '超预算后更早的历史会被整体丢弃，不切半条；工具定义无法压缩，所以预算不会低于它的开销。点击可改预算。'
  )
})
</script>

<template>
  <!--
    有可选档位才挂下拉；只剩一项（如窗口特别小的模型）时退化成纯展示。
    判据是 `budgetPresets.length > 1` 而不是 `> 0`：只有一个数字的菜单
    点开等于什么都没有，还会让用户以为控件坏了。
  -->
  <el-dropdown
    v-if="usage.budgetTokens > 0 && budgetPresets.length > 1"
    trigger="click"
    placement="bottom-end"
    @command="(value: number) => emit('change-budget', value)"
  >
    <div class="context-meter" :title="meterTitle">
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

  <!-- 不可选预算时的纯展示版：仍是同一套读数规格，只是不带 cursor: pointer -->
  <div v-else-if="usage.budgetTokens > 0" class="context-meter context-meter--static" :title="meterTitle">
    <span class="context-meter__track">
      <span class="context-meter__fill" :class="meterLevel" :style="{ width: meterPercent + '%' }" />
    </span>
    <span class="context-meter__value">{{ meterText }}</span>
  </div>
</template>

<style scoped>
/*
 * 读数容器：一行小字 + 细条，与字数统计同规格。
 *
 * <h3>为什么是 24px（而不是 32/20）</h3>
 * 同排的模型按钮热区 24px、字数文本 15px。24 是「12px 字号 × 1.5 行高 = 18，
 * 再给 6px 让它像个可点的东西」的结果，三者等高后这一行才是一条水平线。
 * 用 32 会让整行被这个最高项决定，字数与模型按钮上下各空 4~8px，
 * 看起来是「三个不同高度的东西被强行对齐到中线」。
 *
 * <p>负外边距的历史已不存在：那对 `margin: -6px 0` 是它住在卡片头部时
 * 为了「不占高度地溢出到上下留白里」写的，搬走后负边距只会把行高撑坏。
 */
.context-meter {
  display: inline-flex;
  align-items: center;
  flex: none;
  gap: 6px;
  height: 24px;
  padding: 0 4px;
  box-sizing: border-box;
  border-radius: var(--os-radius-sm);
  cursor: pointer;
  transition: background var(--os-duration-fast) var(--os-ease);
}

.context-meter:hover {
  background: var(--el-fill-color-light);
}

/* 不可选预算时不给 hover 底纹也不给手型：它看起来像能点就会有人去点 */
.context-meter--static {
  cursor: default;
}

.context-meter--static:hover {
  background: transparent;
}

/*
 * 进度条：48 × 4。
 *
 * <p>宽度定死 48：数值文本会在 `0 / 32K` 与 `128K / 1M` 之间变宽变窄，
 * 若条也跟着 flex，同一个会话里用量一变整块读数的宽度就跳 ——
 * 而它右边什么都没有，跳起来唯一的效果是「这行在抖」。
 *
 * <p>高度 4 而不是 3：3px 在 2x 屏上渲染成 6 个物理像素、1x 屏上只有 3 个，
 * 后者几乎看不出圆角，读起来像一条描边而不是容量条。
 */
.context-meter__track {
  display: inline-block;
  flex: none;
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

/*
 * 数值：与字数统计同一档（12px、placeholder 灰、tabular-nums）。
 *
 * <h3>为什么不再是 secondary</h3>
 * 原来它是 secondary、字数是 placeholder —— 同一行里两个读数不同灰阶，
 * 看起来上下文比字数「更重要」，而这个优先关系并不存在。
 * 统一到 placeholder：两者都是辅助读数，等权。
 *
 * <p>唯一保留的差异是数值本身：`tabular-nums` 让 `12K` 与 `128K`
 * 的数字位对齐，用量增长时数字不会左右跳。
 */
.context-meter__value {
  flex: none;
  color: var(--el-text-color-placeholder);
  font-size: var(--os-font-xs);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
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
