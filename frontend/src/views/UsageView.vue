<script setup lang="ts">
/**
 * 用量统计页（清单第 7 项）。
 *
 * <h2>这一页回答什么问题</h2>
 * 「这周到底用掉多少 token、哪一天最多、有多少是真实计费口径」。
 * 上下文仪表盘回答的是「这一轮送进去多少」，两者不可互相代替。
 *
 * <h2>几个刻意的取舍</h2>
 * <ul>
 *   <li><b>不显示金额</b>：单价随 provider、模型、促销变化，把「当前单价 × 历史
 *       token」算出来写进页面，用户会把一个随时会变的数字当账单。展示 token 与
 *       真实/估算占比，金额留给用户按自己的价目表换算。</li>
 *   <li><b>估算值与真实值分开列</b>：混合求和会把估算冒充成计费口径。
 *       有估算数据时页面顶部会出现一条说明。</li>
 *   <li><b>无数据不画零值图</b>：一条平到零的折线看起来像「统计坏了」，
 *       而实际只是还没聊过。</li>
 * </ul>
 */
import { DataLine, Refresh, TrendCharts } from '@element-plus/icons-vue'
import { storeToRefs } from 'pinia'
import { computed, onMounted } from 'vue'

import { buildGeometry, formatTokens, shortDateLabel } from '@/lib/usage-chart'
import { useUsageStore } from '@/stores/usage'

const store = useUsageStore()
const { stats, series, days, loading, error } = storeToRefs(store)

/** 时间范围选项：与「按日聚合」的粒度对齐，不给「最近一年按日」这种读不清的选项 */
const RANGE_OPTIONS = [
  { value: 7, label: '近 7 天' },
  { value: 30, label: '近 30 天' },
  { value: 90, label: '近 90 天' },
]

onMounted(() => {
  void store.load()
})

function handleRange(value: number): void {
  void store.setDays(value)
}

/**
 * el-radio-group 的 change 事件载荷是 `string | number | boolean`，
 * 直接透传给 setDays 会被 TS 拒绝；这里显式转一次数字。
 */
function handleRangeChange(value: string | number | boolean): void {
  handleRange(Number(value))
}

/**
 * 图表尺寸。
 *
 * <p>用固定 viewBox + `preserveAspectRatio="none"` 让 SVG 自适应容器宽度：
 * 只有这样才能在不同屏幕下都用同一套几何计算，而不必监听 resize
 * （监听 resize 需要在组件销毁时移除，且首帧会画错一次）。
 */
const CHART_WIDTH = 720
const CHART_HEIGHT = 160

const geometry = computed(() => buildGeometry(series.value, CHART_WIDTH, CHART_HEIGHT))

/** 有数据且天数 > 0 才画图 */
const showChart = computed(() => stats.value?.hasData === true && series.value.length > 0)

/** 峰值那天（用于「最高一天」提示） */
const peakDay = computed(() => {
  if (series.value.length === 0) {
    return null
  }
  return series.value.reduce((best, item) => (item.totalTokens > best.totalTokens ? item : best))
})

/** 平均每天（按窗口天数算，含没聊天的日子 —— 那才是「日均」） */
const averagePerDay = computed(() => {
  const total = stats.value?.totalTokens ?? 0
  const window = stats.value?.days ?? 0
  return window > 0 ? Math.round(total / window) : 0
})

/** 估算占比（百分比整数）；无数据时为 0 */
const estimatedShare = computed(() => {
  const total = stats.value?.totalTokens ?? 0
  if (total <= 0) {
    return 0
  }
  return Math.round(((stats.value?.estimatedTokens ?? 0) / total) * 100)
})

/** 有估算数据时才提示；纯真实值时不该显示一条无关的说明 */
const hasEstimated = computed(() => (stats.value?.estimatedTokens ?? 0) > 0)

/** 平均每轮：轮次为 0 时不能除 */
const averagePerTurn = computed(() => {
  const turns = stats.value?.turns ?? 0
  return turns > 0 ? Math.round((stats.value?.totalTokens ?? 0) / turns) : 0
})

/** 柱子悬停提示：日期 + 总量 + 轮次 */
function barTitle(index: number): string {
  const item = series.value[index]
  if (!item) {
    return ''
  }
  const parts = [`${item.date}：${item.totalTokens.toLocaleString('zh-CN')} token`]
  if (item.turns > 0) {
    parts.push(`${item.turns} 轮`)
  }
  if (item.estimated) {
    parts.push('（估算值）')
  }
  return parts.join('，')
}

/**
 * 只在数据点不太密时才显示日期标签。
 *
 * <p>90 天时 720 宽的画布上每根柱不到 8px，标签会叠成一团黑块 ——
 * 与其挤成不可读，不如只标首尾与中间几个。
 */
const labelStep = computed(() => (series.value.length <= 14 ? 2 : Math.ceil(series.value.length / 7)))

function shouldShowLabel(index: number): boolean {
  return index % labelStep.value === 0 || index === series.value.length - 1
}

/** 每个标签的实际 x 需要加上柱宽一半（柱子居中在槽位里） */
function labelX(index: number): number {
  const { xs, barWidth } = geometry.value
  return (xs[index] ?? 0) + barWidth / 2
}
</script>

<template>
  <div class="usage">
    <PageHeader
      size="page"
      title="用量"
      description="按天汇总模型 token 消耗。数字来自上游返回的真实 usage，取不到时按字符估算并标注。"
    >
      <el-radio-group :model-value="days" size="small" @change="handleRangeChange">
        <el-radio-button v-for="option in RANGE_OPTIONS" :key="option.value" :value="option.value">
          {{ option.label }}
        </el-radio-button>
      </el-radio-group>
      <!-- 去掉 size="small"：同一颗「刷新」在其他页是 32px，这里 24px，同一个动作两种大小 -->
      <el-button :icon="Refresh" :loading="loading" @click="store.load()">刷新</el-button>
    </PageHeader>

    <el-alert v-if="error" type="error" :closable="false" show-icon title="无法加载用量统计" :description="error" />

    <template v-else>
      <el-skeleton v-if="loading && !stats" :rows="4" animated />

      <el-empty
        v-else-if="stats && !stats.hasData"
        :description="`最近 ${days} 天还没有用量记录 —— 去对话页聊几轮就会出现在这里`"
      />

      <template v-else-if="stats">
        <!-- 估算说明放在最上面：用户第一眼就该知道手上的数字能不能对账 -->
        <el-alert
          v-if="hasEstimated"
          type="info"
          :closable="false"
          show-icon
          title="部分数字为估算值"
          :description="`共 ${formatTokens(stats.estimatedTokens)} token（占 ${estimatedShare}%）来自按字符折算的估算，非上游计费口径。通常因为当前模型不返回 usage。`"
        />

        <div class="usage__stats">
          <el-card class="usage__stat" shadow="never">
            <div class="usage__stat-label">总 token</div>
            <div class="usage__stat-value">{{ formatTokens(stats.totalTokens) }}</div>
            <div class="usage__stat-hint">{{ stats.turns }} 轮对话</div>
          </el-card>
          <el-card class="usage__stat" shadow="never">
            <div class="usage__stat-label">输入</div>
            <div class="usage__stat-value">{{ formatTokens(stats.totalPrompt) }}</div>
            <div class="usage__stat-hint">送入模型的上下文</div>
          </el-card>
          <el-card class="usage__stat" shadow="never">
            <div class="usage__stat-label">输出</div>
            <div class="usage__stat-value">{{ formatTokens(stats.totalCompletion) }}</div>
            <div class="usage__stat-hint">模型生成（含思考）</div>
          </el-card>
          <el-card class="usage__stat" shadow="never">
            <div class="usage__stat-label">日均</div>
            <div class="usage__stat-value">{{ formatTokens(averagePerDay) }}</div>
            <div class="usage__stat-hint">近 {{ stats.days }} 天平均</div>
          </el-card>
          <el-card class="usage__stat" shadow="never">
            <div class="usage__stat-label">每轮平均</div>
            <div class="usage__stat-value">{{ formatTokens(averagePerTurn) }}</div>
            <div class="usage__stat-hint">单轮上下文 + 生成</div>
          </el-card>
        </div>

        <el-card class="usage__chart-card" shadow="never">
          <template #header>
            <div class="usage__chart-header">
              <span class="usage__chart-title">
                <el-icon><TrendCharts /></el-icon>
                每日 token
              </span>
              <span v-if="peakDay && peakDay.totalTokens > 0" class="usage__chart-peak">
                最高：{{ peakDay.date }}（{{ formatTokens(peakDay.totalTokens) }}）
              </span>
            </div>
          </template>

          <div v-if="showChart" class="usage__chart">
            <!--
              viewBox 固定 + preserveAspectRatio="none"：宽度自适应容器、高度不变。
              用 SVG 而非柱状组件是因为样式要跟着 --el-* 变量走（含暗色），
              图表库在这方面要额外写一遍主题适配。
            -->
            <svg
              class="usage__svg"
              :viewBox="`0 0 ${CHART_WIDTH} ${CHART_HEIGHT}`"
              preserveAspectRatio="none"
              role="img"
              :aria-label="`最近 ${days} 天每日 token 用量柱状图`"
            >
              <!-- 峰值参考线：先画，柱子盖在上面 -->
              <line
                v-if="geometry.peakY !== null"
                class="usage__peak-line"
                x1="0"
                :y1="geometry.peakY"
                :x2="CHART_WIDTH"
                :y2="geometry.peakY"
              />
              <rect
                v-for="(item, index) in series"
                :key="item.date"
                class="usage__bar"
                :class="{ 'usage__bar--estimated': item.estimated, 'usage__bar--empty': item.totalTokens === 0 }"
                :x="geometry.xs[index]"
                :y="CHART_HEIGHT - geometry.heights[index]"
                :width="geometry.barWidth"
                :height="geometry.heights[index]"
                :title="barTitle(index)"
              >
                <title>{{ barTitle(index) }}</title>
              </rect>
            </svg>

            <!--
              轴标签用 HTML 而不是 SVG text：SVG text 会被 preserveAspectRatio="none"
              横向拉伸变形（字会被压扁），HTML 绝对定位则不受影响。
            -->
            <div class="usage__axis">
              <span
                v-for="(item, index) in series"
                v-show="shouldShowLabel(index)"
                :key="item.date"
                class="usage__axis-label"
                :style="{ left: `${(labelX(index) / CHART_WIDTH) * 100}%` }"
              >
                {{ shortDateLabel(item.date) }}
              </span>
            </div>
          </div>

          <el-empty v-else description="这个窗口内没有用量" :image-size="60" />
        </el-card>

        <el-card v-if="stats.daily.length > 0" class="usage__table-card" shadow="never">
          <template #header>
            <span class="usage__chart-title">
              <el-icon><DataLine /></el-icon>
              按天明细
            </span>
          </template>
          <!--
            列宽（2026-10-29 按 Element Plus 源码重算）。

            **EP 的 fit 只在「存在无 width 的列」时才生效**。见
            `element-plus/es/components/table/src/table-layout.mjs:102`：

                if (flexColumns.length > 0 && fit) { ...摊剩余... }
                else { bodyWidth.value = bodyMinWidth }   // ← 全部固定宽度

            `flexColumns` = 所有 `!isNumber(column.width)` 的列，也就是
            **只给了 min-width 或什么都没给的列**。一旦每列都写死 `width`，
            flexColumns 为空 → 直接走 else → 表格内容宽 = 列宽之和
            → 容器有余量时**右侧整片空白**，表头分隔线也只画到内容右端
            （实测这张表 innerW 578 / wrapW 998，右侧空掉 420px，像没画完）。

            反过来，只要**存在** flex 列，第 109~120 行会把 `bodyWidth - 各列基准宽之和`
            按 `minWidth` 比例分摊：多个 flex 列时**每列都按比例变宽**（不是全给一列）。
            所以正确做法是**所有列都写 min-width**——既给了内容下限，
            又让富余元素均摊，不会出现「一列吃掉 400px」的巨缝。

            数值列 96：内容是「1,223,745」这类 9 字符数字，实测 62px
            + 左右各 12px 内边距 = 86，96 留余量给更长的值。

            但「按比例分摊」有个副作用：**minWidth 虚高会多分富余**。
            日期列原给 130（日期文字实测仅 78px），分到 228px；而右邻「输入」
            是 `align="center"`、数字贴着列右边缘 → 日期文字到输入数字之间被拉出
            227px 空洞，是全表最刺眼的一处。日期收到 96 后与其余五列基准值接近，
            富余均摊，间隙都落在同一量级。
          -->
          <el-table :data="[...stats.daily].reverse()" max-height="360">
            <el-table-column prop="date" label="日期" min-width="166" align="center" />
            <el-table-column label="输入" min-width="166" align="center">
              <template #default="{ row }">{{ row.promptTokens.toLocaleString('zh-CN') }}</template>
            </el-table-column>
            <el-table-column label="输出" min-width="166" align="center">
              <template #default="{ row }">{{ row.completionTokens.toLocaleString('zh-CN') }}</template>
            </el-table-column>
            <el-table-column label="合计" min-width="166" align="center">
              <template #default="{ row }">
                <strong>{{ row.totalTokens.toLocaleString('zh-CN') }}</strong>
              </template>
            </el-table-column>
            <el-table-column prop="turns" label="轮次" min-width="166" align="center" />
            <el-table-column label="口径" min-width="166" align="center">
              <template #default="{ row }">
                <el-tag v-if="row.estimated" size="small" type="warning" effect="plain">估算</el-tag>
                <el-tag v-else size="small" type="success" effect="plain">真实</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </template>
    </template>
  </div>
</template>

<style scoped>
.usage {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-4);
}

.usage__stats {
  display: grid;
  /* auto-fit + minmax：窄屏自动从 5 列降到 2 列，不需要写媒体查询 */
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: var(--os-space-3);
}

.usage__stat :deep(.el-card__body) {
  padding: var(--os-space-3) var(--os-space-4);
}

.usage__stat-label {
  font-size: var(--os-font-xs);
  color: var(--el-text-color-secondary);
}

.usage__stat-value {
  margin-top: var(--os-space-1);
  font-size: 22px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: var(--el-text-color-primary);
}

.usage__stat-hint {
  margin-top: var(--os-space-1);
  font-size: var(--os-font-xs);
  color: var(--el-text-color-secondary);
}

.usage__chart-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--os-space-2);
}

.usage__chart-title {
  display: inline-flex;
  align-items: center;
  gap: var(--os-space-1);
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.usage__chart-peak {
  font-size: var(--os-font-xs);
  color: var(--el-text-color-secondary);
}

.usage__chart {
  position: relative;
  padding-bottom: 20px;
}

.usage__svg {
  display: block;
  width: 100%;
  height: 160px;
  overflow: visible;
}

.usage__bar {
  fill: var(--el-color-primary);
  /* 柱顶圆角：SVG 的 rx 对窄柱会变成胶囊形，这里用很小的值 */
  rx: 2;
  transition: fill 0.15s ease;
}

.usage__bar:hover {
  fill: var(--el-color-primary-dark-2);
}

/* 估算日子的柱子用浅色区分：一眼能看出「这天不可对账」 */
.usage__bar--estimated {
  fill: var(--el-color-warning-light-3);
}

.usage__bar--estimated:hover {
  fill: var(--el-color-warning);
}

/* 零值不画（高度为 0 时 rect 不可见），保留类名便于将来加占位标记 */
.usage__bar--empty {
  fill: transparent;
}

.usage__peak-line {
  stroke: var(--el-color-danger);
  stroke-width: 1;
  stroke-dasharray: 4 4;
  opacity: 0.6;
}

.usage__axis {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  height: 16px;
}

.usage__axis-label {
  position: absolute;
  transform: translateX(-50%);
  font-size: 11px;
  white-space: nowrap;
  color: var(--el-text-color-secondary);
}

.usage__table-card :deep(.el-card__body) {
  padding-top: 0;
}
</style>
