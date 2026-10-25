<script setup lang="ts">
/**
 * 总览页（阶段 18 Dashboard 化，User Story 5）。
 *
 * 与原版的区别：从「三张说明卡」变成「统计卡行 + 能力入口 + 系统状态」。
 * 统计数据各自独立加载、各自容错——单卡失败显示「—」，不阻塞其他卡（FR-009）。
 *
 * 数据来源取舍：任务 / 记忆 / 知识库走各自 store（加载的就是各页默认视图，顺带预热）；
 * 今日日程走直连接口而不是 calendarStore——store 的 loadOccurrences 会改写全局查询
 * 窗口，首页一次统计会把日程页的默认窗口冲掉（跨页污染，且毫无提示）。
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
import { ElMessage } from 'element-plus'
import { storeToRefs } from 'pinia'
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { downloadExport } from '@/api/export'
import { listOccurrences } from '@/api/calendar'
import { useAppStore } from '@/stores/app'
import { useKnowledgeStore } from '@/stores/knowledge'
import { useMemoryStore } from '@/stores/memory'
import { useTaskStore } from '@/stores/task'

const appStore = useAppStore()
const taskStore = useTaskStore()
const memoryStore = useMemoryStore()
const knowledgeStore = useKnowledgeStore()

const { health, loading: healthLoading, error: healthError } = storeToRefs(appStore)
const { pendingCount, overdueCount } = storeToRefs(taskStore)
const { total: documentTotal } = storeToRefs(knowledgeStore)

const router = useRouter()

/** 统计卡：值 / 加载态 / 失败态三态；失败显示 — */
interface StatCard {
  key: string
  label: string
  icon: typeof Calendar
  value: number | null
  tone: 'primary' | 'danger' | 'warning' | 'success' | 'info'
  route: string
  hint: string
}

const stats = ref<StatCard[]>([
  { key: 'pending', label: '待办任务', icon: Tickets, value: null, tone: 'primary', route: '/tasks', hint: '未完成的任务数' },
  { key: 'overdue', label: '已逾期', icon: WarningFilled, value: null, tone: 'danger', route: '/tasks', hint: '超过截止时间未完成' },
  { key: 'today', label: '今日日程', icon: Calendar, value: null, tone: 'warning', route: '/calendar', hint: '今天到明天的全部发生' },
  { key: 'memory', label: '长期记忆', icon: Memo, value: null, tone: 'success', route: '/memory', hint: '注入每次对话的条数' },
  { key: 'docs', label: '知识文档', icon: Document, value: null, tone: 'info', route: '/knowledge', hint: '已建立向量索引的篇数' },
])

function setStat(key: string, value: number | null): void {
  const card = stats.value.find((item) => item.key === key)
  if (card) {
    card.value = value
  }
}

/** 今日日程：本地时区的当天 00:00 ~ 23:59:59 */
function todayRange(): { from: string; to: string } {
  const now = new Date()
  const start = new Date(now.getFullYear(), now.getMonth(), now.getDate(), 0, 0, 0)
  const end = new Date(now.getFullYear(), now.getMonth(), now.getDate(), 23, 59, 59)
  return { from: start.toISOString(), to: end.toISOString() }
}

onMounted(async () => {
  void appStore.loadHealth()

  // 五个统计互不依赖，并发加载；各自的失败只把自己那一卡打成 —
  const tasks = taskStore.load().then(() => {
    setStat('pending', pendingCount.value)
    setStat('overdue', overdueCount.value)
  })
  const memories = memoryStore.load().then(() => {
    setStat('memory', memoryStore.memories.length)
  })
  const documents = knowledgeStore.loadDocuments().then(() => {
    setStat('docs', documentTotal.value)
  })
  const calendar = (async () => {
    const { from, to } = todayRange()
    const result = await listOccurrences(from, to, 0, 100)
    setStat('today', result.total)
  })()

  await Promise.allSettled([tasks, memories, documents, calendar])
})

/** 状态标签颜色：UP 走成功色，其余走危险色 */
const tagType = computed<'success' | 'danger'>(() =>
  health.value?.status === 'UP' ? 'success' : 'danger',
)

/** 依赖分量颜色：UP/CONFIGURED 成功，NOT_CONFIGURED 信息（可选依赖未装配），DOWN 危险 */
function componentTagType(value: string): 'success' | 'info' | 'danger' {
  if (value === 'UP' || value === 'CONFIGURED') return 'success'
  if (value === 'NOT_CONFIGURED') return 'info'
  return 'danger'
}

/** 导出是页面级的一次性动作，不进 store（没有任何其它页面需要它） */
const exporting = ref(false)
const exportError = ref<string | null>(null)

async function handleExport(): Promise<void> {
  exporting.value = true
  exportError.value = null
  try {
    const filename = await downloadExport()
    ElMessage.success(`已导出 ${filename}`)
  } catch (cause) {
    exportError.value = cause instanceof Error ? cause.message : '导出失败，请稍后重试'
  } finally {
    exporting.value = false
  }
}

/** 能力卡片：图标 + 标题 + 一句说明，点击进对应模块 */
const capabilities = [
  {
    route: '/chat',
    icon: ChatDotRound,
    title: '对话',
    description: '多轮上下文、SSE 流式输出、Markdown 渲染、工具调用轨迹',
  },
  {
    route: '/tasks',
    icon: Tickets,
    title: '任务',
    description: '自然语言增删改查、标签归类、看板视图、批量操作',
  },
  {
    route: '/knowledge',
    icon: Notebook,
    title: '知识库',
    description: '上传文档建立向量索引，问答附带引用来源',
  },
  {
    route: '/calendar',
    icon: Calendar,
    title: '日程与提醒',
    description: '重复规则、到点提醒（浏览器通知 + 站内提示）',
  },
  {
    route: '/memory',
    icon: Memo,
    title: '长期记忆',
    description: '在对话里说「记住…」即跨会话生效，可在此管理',
  },
  {
    route: '/activity',
    icon: DataLine,
    title: '活动',
    description: '查看助手调用过哪些工具、每个 MCP server 当前是否连上',
  },
]
</script>

<template>
  <div class="home">
    <!-- 统计卡行（FR-009）：单卡失败显示 —，不阻塞其他卡 -->
    <div class="home__stats">
      <button
        v-for="card in stats"
        :key="card.key"
        type="button"
        class="home__stat"
        :class="`home__stat--${card.tone}`"
        @click="router.push(card.route)"
      >
        <div class="home__stat-icon">
          <el-icon :size="20"><component :is="card.icon" /></el-icon>
        </div>
        <div class="home__stat-body">
          <span class="home__stat-value">{{ card.value ?? '—' }}</span>
          <span class="home__stat-label">{{ card.label }}</span>
        </div>
      </button>
    </div>

    <!-- 能力入口：替代原版的纯文字 bullet list -->
    <el-card shadow="never">
      <template #header>
        <PageHeader title="已有能力" description="点击卡片进入对应模块" />
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
      <p class="home__hint">
        助手内置工具共 15 个（任务 8 + 知识库 1 + 日程提醒 5 + 长期记忆 1），另可经 MCP 接入外部工具；
        其中删除类操作与 MCP 变更类工具只会生成「待确认事项」，必须由你在界面上确认后才会真正生效。
      </p>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <PageHeader title="后端连通性">
          <el-button :loading="healthLoading" @click="appStore.loadHealth()">刷新</el-button>
        </PageHeader>
      </template>

      <el-alert
        v-if="healthError"
        type="error"
        :closable="false"
        show-icon
        title="无法连接后端服务"
        :description="healthError"
      />

      <el-descriptions v-else-if="health" :column="1" border>
        <el-descriptions-item label="状态">
          <el-tag :type="tagType">{{ health.status }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="应用">{{ health.application }}</el-descriptions-item>
        <el-descriptions-item label="版本">{{ health.version }}</el-descriptions-item>
        <el-descriptions-item label="服务端时间">{{ health.timestamp }}</el-descriptions-item>
        <el-descriptions-item v-if="health.components" label="依赖">
          <el-tag
            v-for="(value, key) in health.components"
            :key="key"
            :type="componentTagType(value)"
            size="small"
            style="margin-right: 6px"
          >
            {{ key }}: {{ value }}
          </el-tag>
        </el-descriptions-item>
      </el-descriptions>

      <el-skeleton v-else :rows="4" animated />
    </el-card>

    <el-card shadow="never">
      <template #header>
        <PageHeader title="数据备份">
          <el-button type="primary" :loading="exporting" @click="handleExport">导出数据</el-button>
        </PageHeader>
      </template>

      <p class="home__hint" style="margin-top: 0">
        把全部任务、日程、记忆、知识库与对话历史导出为一个 JSON 文件。
        这是一次性快照，不包含向量索引（可由分块文本重建）。
      </p>

      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="知识库导出的是分块文本，不含原始文件"
        description="上传的 PDF / Word / 图片本身不落库（只保留解析出的分块）。分块文本足以重建检索，但原始文件需要重新上传。"
      />

      <el-alert
        v-if="exportError"
        class="home__export-error"
        type="error"
        :closable="false"
        show-icon
        title="导出失败"
        :description="exportError"
      />
    </el-card>
  </div>
</template>

<style scoped>
.home {
  display: flex;
  flex-direction: column;
  gap: var(--os-stack-gap);
}

/* ---- 统计卡行 ---- */

.home__stats {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: var(--os-space-3);
}

.home__stat {
  display: flex;
  align-items: center;
  gap: var(--os-space-3);
  padding: var(--os-space-3) var(--os-space-4);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--os-radius-lg);
  background: var(--el-bg-color);
  box-shadow: var(--os-shadow-card);
  cursor: pointer;
  text-align: left;
  transition:
    transform var(--os-duration-fast) var(--os-ease),
    box-shadow var(--os-duration-fast) var(--os-ease);
}

.home__stat:hover {
  transform: translateY(-1px);
  box-shadow: var(--os-shadow-card-hover);
}

.home__stat-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: var(--os-radius-md);
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

.home__stat-body {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.home__stat-value {
  font-size: 22px;
  font-weight: 700;
  line-height: 1.2;
  font-variant-numeric: tabular-nums;
}

.home__stat-label {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
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
  padding: var(--os-space-3);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--os-radius-lg);
  background: var(--el-bg-color);
  cursor: pointer;
  text-align: left;
  transition:
    transform var(--os-duration-fast) var(--os-ease),
    box-shadow var(--os-duration-fast) var(--os-ease);
}

.home__capability:hover {
  transform: translateY(-1px);
  box-shadow: var(--os-shadow-card-hover);
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
}

.home__capability-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.home__capability-title {
  font-size: var(--os-font-sm);
  font-weight: 600;
}

.home__capability-description {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
  line-height: 1.5;
}

.home__hint {
  margin: var(--os-space-3) 0 0;
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
  line-height: 1.6;
}

.home__export-error {
  margin-top: var(--os-space-3);
}

/* 窄屏：统计卡 2 列、能力卡 1 列（FR-014） */
@media (max-width: 900px) {
  .home__stats {
    grid-template-columns: repeat(2, 1fr);
  }

  .home__capabilities {
    grid-template-columns: 1fr;
  }
}
</style>
