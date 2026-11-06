<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listTasks, completeTask } from '@/api/tasks'
import { listOccurrences } from '@/api/calendar'
import { listConfirmations } from '@/api/confirmations'
import { unfinishedRuns } from '@/api/workspace'
import type { RunSummary } from '@/api/workspace'
import type { CalendarOccurrence, TaskConfirmation, TaskItem } from '@/types/api'
import { localDayWindow, RUN_STATUS } from '@/lib/today'
import { formatTaskDateTime } from '@/lib/task-meta'

type SectionKey = 'today' | 'overdue' | 'events' | 'confirmations' | 'runs'
interface Section {
  key: SectionKey
  title: string
  route: string
  loading: boolean
  error: string
  total: number
}
const emit = defineEmits<{ (e: 'tasksChanged'): void }>()
const router = useRouter()
const groups = ref<Section[]>([
  { key: 'today', title: '今日任务', route: '/tasks', loading: true, error: '', total: 0 },
  { key: 'overdue', title: '往日逾期', route: '/tasks', loading: true, error: '', total: 0 },
  { key: 'events', title: '今日日程', route: '/calendar', loading: true, error: '', total: 0 },
  { key: 'confirmations', title: '待确认操作', route: '/activity', loading: true, error: '', total: 0 },
  { key: 'runs', title: '未结束执行', route: '/chat', loading: true, error: '', total: 0 },
])
const today = ref<TaskItem[]>([])
const overdue = ref<TaskItem[]>([])
const events = ref<CalendarOccurrence[]>([])
const confirmations = ref<TaskConfirmation[]>([])
const runs = ref<RunSummary[]>([])
const completing = ref(new Set<string>())
const date = ref(new Date())
const dateLabel = computed(() =>
  date.value.toLocaleDateString('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' }),
)
let active = true
let generation = 0
const requests = new Map<SectionKey, number>()
async function load(key: SectionKey): Promise<void> {
  const group = groups.value.find((s) => s.key === key)!
  const seq = (requests.get(key) ?? 0) + 1
  requests.set(key, seq)
  const dayGeneration = generation
  group.loading = true
  group.error = ''
  const window = localDayWindow(date.value)
  const current = (): boolean => active && dayGeneration === generation && requests.get(key) === seq
  try {
    if (key === 'today' || key === 'overdue') {
      const page = await listTasks({
        incomplete: true,
        size: 20,
        page: 0,
        dueBefore: key === 'today' ? window.before : window.from,
        dueFrom: key === 'today' ? window.from : undefined,
      })
      if (!current()) return
      if (key === 'today') today.value = page.items
      else overdue.value = page.items
      group.total = page.total
    } else if (key === 'events') {
      const page = await listOccurrences(window.from, window.calendarTo, 0, 20)
      if (!current()) return
      events.value = page.items
      group.total = page.total
    } else if (key === 'confirmations') {
      const items = await listConfirmations()
      if (!current()) return
      const pending = items.filter(
        (item) => item.status === 'PENDING' && new Date(item.expiresAt).getTime() > Date.now(),
      )
      confirmations.value = pending.slice(0, 20)
      group.total = pending.length
    } else {
      const page = await unfinishedRuns()
      if (!current()) return
      runs.value = page.items
      group.total = page.total
    }
  } catch (error) {
    if (current()) group.error = error instanceof Error ? error.message : '加载失败，请重试'
  } finally {
    if (current()) group.loading = false
  }
}
function refresh(): void {
  const now = new Date()
  if (localDayWindow(now).key !== localDayWindow(date.value).key) generation++
  date.value = now
  for (const s of groups.value) void load(s.key)
}
async function complete(task: TaskItem): Promise<void> {
  if (completing.value.has(task.id)) return
  completing.value.add(task.id)
  try {
    await completeTask(task.id)
    ElMessage.success(task.recurrenceType && task.recurrenceType !== 'NONE' ? '本期已完成，下一期已生成' : '任务已完成')
    emit('tasksChanged')
    await Promise.all([load('today'), load('overdue')])
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '完成任务失败')
  } finally {
    completing.value.delete(task.id)
  }
}
function openGroup(group: Section): void {
  if (group.key === 'today' || group.key === 'overdue')
    void router.push({ path: '/tasks', query: { agenda: group.key } })
  else void router.push(group.route)
}
function openTask(task: TaskItem): void {
  void router.push({ path: '/tasks', query: { task: task.id } })
}
function openChat(conversationId?: string | null): void {
  void router.push(conversationId ? { path: '/chat', query: { conversationId } } : '/activity')
}
let timer: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  refresh()
  window.addEventListener('focus', refresh)
  timer = setInterval(() => {
    if (localDayWindow().key !== localDayWindow(date.value).key) refresh()
  }, 60_000)
})
onUnmounted(() => {
  active = false
  clearInterval(timer)
  window.removeEventListener('focus', refresh)
})
</script>

<template>
  <section class="today-workspace" aria-labelledby="today-heading">
    <div class="today-workspace__header">
      <div>
        <h2 id="today-heading">今日工作台</h2>
        <p>{{ dateLabel }} · 从这里开始处理今天的事</p>
      </div>
      <el-button @click="refresh">刷新工作台</el-button>
    </div>
    <div class="today-workspace__grid">
      <el-card v-for="group in groups" :key="group.key" shadow="never" class="today-workspace__card">
        <template #header>
          <div class="today-workspace__header">
            <h3>
              {{ group.title }} <span v-if="!group.loading && !group.error">{{ group.total }}</span>
            </h3>
            <el-button link @click="openGroup(group)">查看全部</el-button>
          </div>
        </template>
        <div v-if="group.loading" role="status" class="today-workspace__state">加载中…</div>
        <div v-else-if="group.error" role="alert" class="today-workspace__state">
          <p>{{ group.error }}</p>
          <el-button @click="load(group.key)">重试{{ group.title }}</el-button>
        </div>
        <template v-else>
          <p v-if="group.total === 0" class="today-workspace__state">暂无{{ group.title }}</p>
          <ul v-else class="today-workspace__list">
            <template v-if="group.key === 'today' || group.key === 'overdue'">
              <li v-for="task in group.key === 'today' ? today : overdue" :key="task.id">
                <button class="today-workspace__link" @click="openTask(task)">
                  <strong>{{ task.title }}</strong
                  ><small>{{ formatTaskDateTime(task.dueAt) }}</small>
                </button>
                <el-button size="small" :loading="completing.has(task.id)" @click="complete(task)">完成</el-button>
              </li>
            </template>
            <template v-else-if="group.key === 'events'">
              <li v-for="event in events" :key="`${event.eventId}-${event.startsAt}`">
                <button class="today-workspace__link" @click="router.push('/calendar')">
                  <strong>{{ event.title }}</strong
                  ><small
                    >{{ event.allDay ? '全天' : formatTaskDateTime(event.startsAt)
                    }}<template v-if="event.location"> · {{ event.location }}</template></small
                  >
                </button>
              </li>
            </template>
            <template v-else-if="group.key === 'confirmations'">
              <li v-for="item in confirmations" :key="item.id">
                <button class="today-workspace__link" @click="openChat(item.conversationId)">
                  <strong>{{ item.summary }}</strong
                  ><small>影响 {{ item.affectedCount }} 项 · 点击查看并确认</small>
                </button>
              </li>
            </template>
            <template v-else>
              <li v-for="run in runs" :key="run.id">
                <button class="today-workspace__link" @click="openChat(run.conversationId)">
                  <strong>{{ run.title }}</strong
                  ><small>{{ RUN_STATUS[run.status] ?? run.status }} · 点击回到会话</small>
                </button>
              </li>
            </template>
          </ul>
          <p v-if="group.total > 20" class="today-workspace__state">
            显示前 20 项，共 {{ group.total }} 项；其余请查看全部。
          </p>
        </template>
      </el-card>
    </div>
  </section>
</template>

<style scoped>
.today-workspace__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--os-space-3);
  flex-wrap: wrap;
}
h2,
h3,
p {
  margin: 0;
}
h2 {
  font-size: var(--os-font-xl);
}
h3 {
  font-size: var(--os-font-md);
}
h3 span {
  margin-left: var(--os-space-2);
  color: var(--el-color-primary);
  font-variant-numeric: tabular-nums;
}
.today-workspace__header p {
  margin-top: var(--os-space-2);
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-sm);
}
.today-workspace__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--os-space-3);
  margin-top: var(--os-space-4);
}
.today-workspace__card:last-child {
  grid-column: 1/-1;
}
.today-workspace__list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.today-workspace__list li {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
  padding: var(--os-space-3) 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.today-workspace__list li:last-child {
  border-bottom: none;
}
.today-workspace__link {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
  gap: var(--os-space-1);
  border: 0;
  background: transparent;
  color: var(--el-text-color-primary);
  font: inherit;
  text-align: left;
  cursor: pointer;
  padding: var(--os-space-1);
  border-radius: var(--os-radius-sm);
}
.today-workspace__link:hover {
  background: var(--el-fill-color-light);
}
.today-workspace__link:focus-visible {
  outline: 2px solid var(--el-color-primary);
}
.today-workspace__link strong {
  font-weight: 500;
  overflow-wrap: anywhere;
}
.today-workspace__link small,
.today-workspace__state {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
  line-height: 1.7;
}
.today-workspace__state p {
  margin-bottom: var(--os-space-2);
}
@media (max-width: 700px) {
  .today-workspace__grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
