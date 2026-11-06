<script setup lang="ts">
/**
 * 总览页（阶段 18 Dashboard 化，User Story 5）。
 *
 * 与原版的区别：从「三张说明卡」变成「统计卡行 + 能力入口 + 系统状态」。
 * 统计数据各自独立加载、各自容错——单卡失败显示「—」，不阻塞其他卡（FR-009）。
 *
 * 各统计直接读取接口，不使用列表页的筛选、分页或缓存，也不改写列表状态。
 */
import {
  Calendar,
  ChatDotRound,
  DataLine,
  Document,
  Memo,
  Notebook,
  Tickets,
  WarningFilled,
} from '@element-plus/icons-vue'
import { storeToRefs } from 'pinia'
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import TodayWorkspace from '@/components/home/TodayWorkspace.vue'

import { listOccurrences } from '@/api/calendar'
import { useAppStore } from '@/stores/app'
import { listDocuments } from '@/api/knowledge'
import { listMemories } from '@/api/memory'
import { fetchTaskStats } from '@/api/tasks'

const appStore = useAppStore()

const { health, loading: healthLoading, error: healthError } = storeToRefs(appStore)

const router = useRouter()

/** 统计卡：值 / 加载态 / 失败态三态；失败显示 — */
interface StatCard {
  key: string
  label: string
  icon: typeof Calendar
  value: number | null
  status: 'loading' | 'ready' | 'error'
  tone: 'primary' | 'danger' | 'warning' | 'success' | 'info'
  route: string
  hint: string
}

const stats = ref<StatCard[]>([
  {
    key: 'pending',
    label: '待办任务',
    icon: Tickets,
    value: null,
    status: 'loading',
    tone: 'primary',
    route: '/tasks',
    hint: '未完成的任务数',
  },
  {
    key: 'overdue',
    label: '已逾期',
    icon: WarningFilled,
    value: null,
    status: 'loading',
    tone: 'danger',
    route: '/tasks',
    hint: '超过截止时间未完成',
  },
  {
    key: 'today',
    label: '今日日程',
    icon: Calendar,
    value: null,
    status: 'loading',
    tone: 'warning',
    route: '/calendar',
    hint: '今天到明天的全部发生',
  },
  {
    key: 'memory',
    label: '长期记忆',
    icon: Memo,
    value: null,
    status: 'loading',
    tone: 'success',
    route: '/memory',
    hint: '注入每次对话的条数',
  },
  {
    key: 'docs',
    label: '知识文档',
    icon: Document,
    value: null,
    status: 'loading',
    tone: 'info',
    route: '/knowledge',
    hint: '已建立向量索引的篇数',
  },
])

async function loadCards(keys: string[], read: () => Promise<number[]>): Promise<void> {
  const cards = stats.value.filter((card) => keys.includes(card.key))
  for (const card of cards) {
    card.value = null
    card.status = 'loading'
  }
  try {
    const values = await read()
    keys.forEach((key, index) => {
      const card = cards.find((item) => item.key === key)!
      card.value = values[index] ?? null
      card.status = 'ready'
    })
  } catch {
    for (const card of cards) card.status = 'error'
  }
}

function loadStat(key: string): Promise<void> {
  switch (key) {
    case 'pending':
    case 'overdue':
      return loadCards(['pending', 'overdue'], async () => {
        const result = await fetchTaskStats()
        return [result.pending, result.overdue]
      })
    case 'memory':
      return loadCards(['memory'], async () => [(await listMemories()).length])
    case 'docs':
      return loadCards(['docs'], async () => [(await listDocuments({ page: 0, size: 1 })).total])
    default:
      return loadCards(['today'], async () => {
        const { from, to } = todayRange()
        return [(await listOccurrences(from, to, 0, 1)).total]
      })
  }
}

function activateStat(card: StatCard): void {
  if (card.status === 'error') void loadStat(card.key)
  else void router.push(card.route)
}

/** 今日日程：本地时区的当天 00:00 ~ 23:59:59 */
function todayRange(): { from: string; to: string } {
  const now = new Date()
  const start = new Date(now.getFullYear(), now.getMonth(), now.getDate(), 0, 0, 0)
  const end = new Date(now.getFullYear(), now.getMonth(), now.getDate(), 23, 59, 59)
  return { from: start.toISOString(), to: end.toISOString() }
}

onMounted(() => {
  void appStore.loadHealth()
  for (const key of ['pending', 'memory', 'docs', 'today']) void loadStat(key)
})

/** 状态标签颜色：UP 走成功色，其余走危险色 */
const tagType = computed<'success' | 'danger'>(() => (health.value?.status === 'UP' ? 'success' : 'danger'))

/** 能力卡片：图标 + 标题 + 一句说明，点击进对应模块 */
const capabilities = [
  {
    route: '/chat',
    icon: ChatDotRound,
    title: '对话',
    description: '随时提问，继续推进正在处理的事',
  },
  {
    route: '/tasks',
    icon: Tickets,
    title: '任务',
    description: '按项目与优先级推进待办事项',
  },
  {
    route: '/knowledge',
    icon: Notebook,
    title: '知识库',
    description: '收集资料、检索答案与管理长期记忆',
  },
  {
    route: '/calendar',
    icon: Calendar,
    title: '日程与提醒',
    description: '安排时间，接收重要事项提醒',
  },
  {
    route: '/workflows',
    icon: Document,
    title: '定时报告',
    description: '定期汇总晨报、周报与资料巡检',
  },
  {
    route: '/activity',
    icon: DataLine,
    title: '运行中心',
    description: '回看工具执行结果与模型用量',
  },
]

/**
 * 欢迎语（阶段 23）：按时段给一句问候 + 当天需要关注的事。
 *
 * 只在「有值得说的」时给副标题 —— 零待办时硬凑一句「今天没有待办」是噪音，
 * 不如让副标题空着。
 */
const greeting = computed(() => {
  const hour = new Date().getHours()
  if (hour < 6) return '夜深了'
  if (hour < 11) return '早上好'
  if (hour < 14) return '中午好'
  if (hour < 18) return '下午好'
  return '晚上好'
})

const today = new Date().toLocaleDateString('zh-CN', {
  year: 'numeric',
  month: 'long',
  day: 'numeric',
  weekday: 'long',
})

/** 概览副标题：数字未加载时返回空串，避免闪现「0 个待办」再跳成 12 */
const overviewLine = computed(() => {
  const pending = stats.value.find((item) => item.key === 'pending')?.value
  const overdue = stats.value.find((item) => item.key === 'overdue')?.value
  const todayCount = stats.value.find((item) => item.key === 'today')?.value
  if (pending === null && overdue === null && todayCount === null) {
    return ''
  }
  const parts: string[] = []
  if (pending) parts.push(`${pending} 个待办`)
  if (overdue) parts.push(`${overdue} 个已逾期`)
  if (todayCount) parts.push(`今天 ${todayCount} 项日程`)
  return parts.length > 0 ? `你有 ${parts.join('，')}。` : '今天没有待办与日程，清清爽爽。'
})
</script>

<template>
  <div class="home">
    <!-- 欢迎头区（阶段 23）：原版一进页面就是一排统计卡，没有「这是哪、今天什么状况」的锚点 -->
    <header class="home__hero">
      <div class="home__hero-text">
        <h1 class="home__hero-title">{{ greeting }}</h1>
        <p v-if="overviewLine" class="home__hero-subtitle">{{ overviewLine }}</p>
        <p v-else class="home__hero-subtitle">今天 {{ today }}</p>
      </div>
      <el-button class="home__hero-cta" type="primary" @click="router.push('/chat')">
        <el-icon><ChatDotRound /></el-icon>
        开始对话
      </el-button>
    </header>

    <TodayWorkspace @tasks-changed="loadStat('pending')" />

    <!-- 统计卡行（FR-009）：单卡失败显示 —，不阻塞其他卡 -->
    <div class="home__stats">
      <button
        v-for="card in stats"
        :key="card.key"
        type="button"
        class="home__stat"
        :class="`home__stat--${card.tone}`"
        :title="card.status === 'error' ? '加载失败，点击重试' : card.hint"
        :aria-busy="card.status === 'loading'"
        @click="activateStat(card)"
      >
        <div class="home__stat-top">
          <span class="home__stat-label">{{ card.label }}</span>
          <span class="home__stat-icon">
            <el-icon :size="15"><component :is="card.icon" /></el-icon>
          </span>
        </div>
        <span class="home__stat-value" :class="{ 'home__stat-value--empty': card.value === null }">
          {{ card.value ?? '—' }}
        </span>
        <span v-if="card.status === 'error'" class="home__stat-status">加载失败，点击重试</span>
        <span v-else-if="card.status === 'loading'" class="home__stat-status">加载中…</span>
      </button>
    </div>

    <!-- 能力入口：替代原版的纯文字 bullet list -->
    <el-card shadow="never">
      <template #header>
        <PageHeader title="快捷入口" />
      </template>
      <div class="home__capabilities">
        <button
          v-for="item in capabilities"
          :key="item.route"
          type="button"
          class="home__capability"
          @click="router.push(item.route)"
        >
          <div class="home__capability-icon">
            <el-icon :size="18"><component :is="item.icon" /></el-icon>
          </div>
          <div class="home__capability-text">
            <span class="home__capability-title">{{ item.title }}</span>
            <span class="home__capability-description">{{ item.description }}</span>
          </div>
        </button>
      </div>
    </el-card>

    <div class="home__system-strip">
      <span v-if="healthError" role="status">服务暂时不可用</span
      ><span v-else
        ><el-tag :type="tagType" size="small" effect="plain">{{
          health?.status === 'UP' ? '服务正常' : healthLoading ? '检查服务中' : '待检查'
        }}</el-tag
        ><span v-if="health?.version">版本 {{ health.version }}</span></span
      >
      <div>
        <el-button text :loading="healthLoading" @click="appStore.loadHealth()">刷新状态</el-button
        ><el-button text @click="router.push('/settings?section=data')">数据与维护</el-button
        ><el-button text @click="router.push('/settings?section=about')">系统详情</el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.home {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-5);
}

/* ---- 欢迎头区 ---- */

.home__hero {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--os-space-4);
  flex-wrap: wrap;
  /* 底部多留一点：统计卡行紧接其下，需要拉开层级 */
  padding-bottom: var(--os-space-1);
}

.home__hero-text {
  min-width: 0;
}

.home__hero-title {
  margin: 0;
  /* 全站唯一一处 3xl：它是「今天」这一屏的主题句 */
  font-size: var(--os-font-3xl);
  font-weight: 650;
  letter-spacing: -0.4px;
  line-height: 1.25;
}

.home__hero-subtitle {
  margin: var(--os-space-2) 0 0;
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-md);
  line-height: 1.6;
}

.home__hero-cta {
  flex: none;
}

/* ---- 统计卡行 ---- */

.home__stats {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: var(--os-space-3);
}

/*
 * 统计卡改版（阶段 23）：原来「图标在左 + 数字/标签竖排在右」，
 * 五个卡的数字基线各自对齐但视觉重心散在左侧。现在改为
 * 「标签在上一行（右侧带小图标）+ 大数字在下」的纵向结构：
 * 五个数字自然形成一条基线，扫视时能直接横向比较大小。
 */
.home__stat {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-2);
  padding: var(--os-space-4);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--os-radius-xl);
  background: var(--el-bg-color);
  box-shadow: var(--os-shadow-card);
  cursor: pointer;
  text-align: left;
  transition:
    transform var(--os-duration-fast) var(--os-ease),
    box-shadow var(--os-duration-fast) var(--os-ease),
    border-color var(--os-duration-fast) var(--os-ease);
}

.home__stat:hover {
  transform: translateY(-2px);
  border-color: var(--el-color-primary-light-7);
  box-shadow: var(--os-shadow-card-hover);
}

.home__stat-status {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-sm);
}

.home__stat-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--os-space-2);
}

.home__stat-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border-radius: var(--os-radius-sm);
  flex: none;
}

.home__stat--primary .home__stat-icon {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}

.home__stat--danger .home__stat-icon {
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
}

.home__stat--warning .home__stat-icon {
  background: var(--el-color-warning-light-9);
  color: var(--el-color-warning);
}

.home__stat--success .home__stat-icon {
  background: var(--el-color-success-light-9);
  color: var(--el-color-success);
}

.home__stat--info .home__stat-icon {
  background: var(--el-color-info-light-9);
  color: var(--el-color-info);
}

.home__stat-value {
  font-size: var(--os-font-stat);
  font-weight: 650;
  /* 等宽数字：五个卡的数字纵向比较时不会因「1」比「8」窄而晃 */
  font-variant-numeric: tabular-nums;
  letter-spacing: -1px;
  line-height: 1.1;
  color: var(--el-text-color-primary);
}

/* 「—」占位用弱化色：它是加载失败态，不该和真实数字同样醒目 */
.home__stat-value--empty {
  color: var(--el-text-color-placeholder);
}

.home__stat-label {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-sm);
  font-weight: 500;
}

/* ---- 能力卡片网格 ---- */

.home__capabilities {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--os-space-3);
}

.home__capability {
  display: flex;
  align-items: flex-start;
  gap: var(--os-space-3);
  padding: var(--os-space-4);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--os-radius-lg);
  background: var(--el-bg-color);
  cursor: pointer;
  text-align: left;
  transition:
    transform var(--os-duration-fast) var(--os-ease),
    box-shadow var(--os-duration-fast) var(--os-ease),
    border-color var(--os-duration-fast) var(--os-ease);
}

.home__capability:hover {
  transform: translateY(-1px);
  border-color: var(--el-color-primary-light-7);
  box-shadow: var(--os-shadow-card-hover);
}

.home__capability:hover .home__capability-icon {
  background: var(--el-color-primary);
  color: #fff;
}

.home__capability-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: var(--os-radius-md);
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  flex: none;
  transition:
    background var(--os-duration-fast) var(--os-ease),
    color var(--os-duration-fast) var(--os-ease);
}

.home__capability-text {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-1);
  min-width: 0;
}

.home__capability-title {
  font-size: var(--os-font-md);
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.home__capability-description {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
  line-height: 1.6;
}

/* 窄屏：统计卡 2 列、能力卡 1 列（FR-014） */
@media (max-width: 900px) {
  .home__hero-title {
    font-size: var(--os-font-2xl);
  }

  .home__stats {
    grid-template-columns: repeat(2, 1fr);
  }

  .home__capabilities {
    grid-template-columns: 1fr;
  }
}

/* 极窄屏：统计卡 1 列，避免「今日日程」这种 4 字标签被挤成两行 */
@media (max-width: 520px) {
  .home__stats {
    grid-template-columns: 1fr;
  }
}
.home__system-strip {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--os-space-3);
  padding: var(--os-space-3) 0;
  border-top: 1px solid var(--el-border-color-lighter);
  font-size: var(--os-font-xs);
  color: var(--el-text-color-secondary);
}
.home__system-strip > span,
.home__system-strip > div {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--os-space-2);
}
.home__system-strip :deep(.el-button + .el-button) {
  margin-left: 0;
}
</style>
