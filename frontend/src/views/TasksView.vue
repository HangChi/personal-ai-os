<script setup lang="ts">
/**
 * 任务页（阶段 18 改造，User Story 6）。
 *
 * 本版改动：
 * - 表格/看板双视图（选择持久化到 localStorage，FR-010）
 * - 看板三列：TODO/DOING/DONE，空列有空态；卡片操作按钮，**不做拖拽**（明确不含）
 * - 批量操作：多选后批量完成/删除，逐条调用现有接口，汇总报告成败（C-05）
 * - 表格默认按截止时间升序（无截止排最后）
 * - 套用公共组件 PageHeader / FilterBar / AppPagination / StatusTag
 */
import { ElMessage, ElMessageBox } from 'element-plus'
import { storeToRefs } from 'pinia'
import { computed, onMounted, ref } from 'vue'

import AppPagination from '@/components/AppPagination.vue'
import EmptyState from '@/components/EmptyState.vue'
import FilterBar from '@/components/FilterBar.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import { groupTasksByStatus, sortByDueAsc } from '@/lib/board'
import { useTaskStore } from '@/stores/task'
import { useUiStore } from '@/stores/ui'
import type { UpdateTaskPayload } from '@/api/tasks'
import type { TaskItem, TaskPriority, TaskStatus } from '@/types/api'

const taskStore = useTaskStore()
const uiStore = useUiStore()
const {
  tasks,
  loading,
  error,
  statusFilter,
  keyword,
  tagFilter,
  tags,
  pendingCount,
  overdueCount,
  total,
  page,
  size,
} = storeToRefs(taskStore)
const { tasksView } = storeToRefs(uiStore)

const newTitle = ref('')
const newPriority = ref<TaskPriority>('MEDIUM')
const newDueDate = ref<Date | null>(null)
const newTags = ref<string[]>([])
const creating = ref(false)

const statusOptions: Array<{ label: string; value: TaskStatus | '' }> = [
  { label: '全部', value: '' },
  { label: '待办', value: 'TODO' },
  { label: '进行中', value: 'DOING' },
  { label: '已完成', value: 'DONE' },
]

const priorityOptions: Array<{ label: string; value: TaskPriority }> = [
  { label: '低', value: 'LOW' },
  { label: '中', value: 'MEDIUM' },
  { label: '高', value: 'HIGH' },
]

/** 状态/优先级徽标映射（StatusTag 的 map） */
const STATUS_MAP: Record<string, { label: string; type: 'success' | 'info' | 'warning' | 'danger' }> = {
  TODO: { label: '待办', type: 'info' },
  DOING: { label: '进行中', type: 'warning' },
  DONE: { label: '已完成', type: 'success' },
}
const PRIORITY_MAP: Record<string, { label: string; type: 'success' | 'info' | 'warning' | 'danger' }> = {
  LOW: { label: '低', type: 'info' },
  MEDIUM: { label: '中', type: 'warning' },
  HIGH: { label: '高', type: 'danger' },
}

function formatDateTime(value: string | null): string {
  if (!value) {
    return '—'
  }
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString('zh-CN', { hour12: false })
}

onMounted(() => {
  void taskStore.load()
  void taskStore.refreshTags()
})

async function handleCreate(): Promise<void> {
  const title = newTitle.value.trim()
  if (!title) {
    ElMessage.warning('请先填写任务标题')
    return
  }
  creating.value = true
  try {
    await taskStore.create(title, newPriority.value, newDueDate.value?.toISOString(), newTags.value)
    if (!error.value) {
      newTitle.value = ''
      newDueDate.value = null
      newPriority.value = 'MEDIUM'
      newTags.value = []
    }
  } finally {
    creating.value = false
  }
}

const editing = ref(false)
const editSaving = ref(false)
/** 正在编辑的任务的**原始快照**：用于判断截止时间是否被用户主动清空 */
const editOriginal = ref<TaskItem | null>(null)
const editForm = ref<{
  title: string
  description: string
  priority: TaskPriority
  dueAt: Date | null
  status: TaskStatus
  tags: string[]
}>({ title: '', description: '', priority: 'MEDIUM', dueAt: null, status: 'TODO', tags: [] })

function openEdit(task: TaskItem): void {
  editOriginal.value = task
  editForm.value = {
    title: task.title,
    description: task.description ?? '',
    priority: task.priority,
    dueAt: task.dueAt ? new Date(task.dueAt) : null,
    status: task.status,
    tags: [...task.tags],
  }
  editing.value = true
}

async function submitEdit(): Promise<void> {
  const original = editOriginal.value
  if (!original) {
    return
  }
  const title = editForm.value.title.trim()
  if (!title) {
    ElMessage.warning('标题不能为空')
    return
  }

  // 描述始终提交（空串表示清空）——若用 `|| undefined`，用户就永远清不掉已有描述
  // 标签同理：数组本身能表达「清空」（`[]`），所以也始终提交
  const payload: UpdateTaskPayload = {
    title,
    description: editForm.value.description.trim(),
    priority: editForm.value.priority,
    status: editForm.value.status,
    tags: editForm.value.tags,
  }
  // 截止时间：有值则设置；**原本有值而现在被清空**则显式请求清除。
  // 后端用 clearDueAt 区分「未提供」与「显式置空」，这正是需要那个字段的原因。
  if (editForm.value.dueAt) {
    payload.dueAt = editForm.value.dueAt.toISOString()
  } else if (original.dueAt) {
    payload.clearDueAt = true
  }

  editSaving.value = true
  try {
    const ok = await taskStore.edit(original.id, payload)
    if (ok) {
      ElMessage.success('已保存')
      editing.value = false
    } else {
      // 失败必须**就地**提示：store.error 渲染在页面顶部的 alert 里，
      // 而弹窗盖在它上面 —— 用户只会觉得「点了没反应」
      ElMessage.error(error.value ?? '保存失败')
    }
  } finally {
    editSaving.value = false
  }
}

async function handleToggle(task: TaskItem): Promise<void> {
  await taskStore.toggleCompleted(task)
}

async function handleDelete(task: TaskItem): Promise<void> {
  // 人工直连删除无需服务端确认流程 —— 这里的二次确认就是「人做出的决定」
  try {
    await ElMessageBox.confirm(`确定删除任务「${task.title}」？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  await taskStore.remove(task.id)
}

// ------------------------------------------------------------------ 看板与批量（阶段 18）

/** 表格数据：默认按截止升序；看板列内顺序即该排序结果 */
const sortedTasks = computed(() => sortByDueAsc(tasks.value))
const boardColumns = computed(() => groupTasksByStatus(sortedTasks.value))

/** 多选批量操作（仅表格视图；看板用卡片按钮） */
const selection = ref<TaskItem[]>([])
const batchWorking = ref(false)

function clearSelection(): void {
  selection.value = []
}

async function batchComplete(): Promise<void> {
  // 已完成的行不需要「再完成一遍」；批量完成的语义是推进未完成的行
  const targets = selection.value.filter((item) => item.status !== 'DONE')
  if (targets.length === 0) {
    ElMessage.info('选中的任务都已完成')
    return
  }
  batchWorking.value = true
  try {
    const { succeeded, failed } = await taskStore.batchComplete(targets.map((item) => item.id))
    reportBatch('完成', succeeded, failed)
    clearSelection()
  } finally {
    batchWorking.value = false
  }
}

async function batchDelete(): Promise<void> {
  if (selection.value.length === 0) {
    return
  }
  try {
    await ElMessageBox.confirm(
      `确定删除选中的 ${selection.value.length} 条任务？`,
      '批量删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  batchWorking.value = true
  try {
    const { succeeded, failed } = await taskStore.batchDelete(selection.value.map((item) => item.id))
    reportBatch('删除', succeeded, failed)
    clearSelection()
  } finally {
    batchWorking.value = false
  }
}

/** 批量结果汇总报告：一条 message 说完，不逐条弹 toast（R-11） */
function reportBatch(action: string, succeeded: number, failed: number): void {
  if (failed === 0) {
    ElMessage.success(`已${action} ${succeeded} 条`)
  } else {
    ElMessage.warning(`${action}成功 ${succeeded} 条，失败 ${failed} 条`)
  }
}

function switchView(next: 'table' | 'board'): void {
  // 直接写 ref：持久化由 uiStore 内的 watch 负责（v-model 与命令式两条路径都覆盖）
  uiStore.tasksView = next
  clearSelection()
}
</script>

<template>
  <div class="tasks">
    <el-card shadow="never">
      <template #header>
        <PageHeader title="新建任务">
          <template #default>
            <span class="tasks__stats">
              待办 {{ pendingCount }} 项<template v-if="overdueCount > 0">
                · <span class="tasks__overdue">逾期 {{ overdueCount }} 项</span>
              </template>
            </span>
          </template>
        </PageHeader>
      </template>

      <div class="tasks__create">
        <el-input
          v-model="newTitle"
          class="tasks__create-title"
          maxlength="200"
          placeholder="任务标题，例如：周五前交周报"
          @keydown.enter="handleCreate"
        />
        <el-select v-model="newPriority" class="tasks__create-priority">
          <el-option
            v-for="option in priorityOptions"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
        <el-date-picker
          v-model="newDueDate"
          type="datetime"
          placeholder="截止时间（可选）"
          class="tasks__create-due"
        />
        <el-select
          v-model="newTags"
          class="tasks__create-tags"
          multiple
          filterable
          allow-create
          default-first-option
          :multiple-limit="5"
          placeholder="标签（可选，最多 5 个）"
        >
          <el-option v-for="tag in tags" :key="tag" :label="tag" :value="tag" />
        </el-select>
        <el-button type="primary" :loading="creating" @click="handleCreate">新建</el-button>
      </div>
    </el-card>

    <el-card class="tasks__list-card" shadow="never">
      <template #header>
        <PageHeader title="任务列表">
          <template #default>
            <div class="tasks__view-switch">
              <el-radio-group :model-value="tasksView" size="small" @change="switchView">
                <el-radio-button value="table">表格</el-radio-button>
                <el-radio-button value="board">看板</el-radio-button>
              </el-radio-group>
            </div>
          </template>
        </PageHeader>
        <FilterBar class="tasks__filters">
          <!-- 空串表示「全部」，Element Plus 会把它当作未选择而显示英文 placeholder，
               所以必须显式给中文 placeholder，否则界面里冒出一个 "Select" -->
          <el-select
            v-model="statusFilter"
            class="tasks__filter"
            placeholder="全部"
            @change="taskStore.reload()"
          >
            <el-option
              v-for="option in statusOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
          <el-select
            v-model="tagFilter"
            class="tasks__filter tasks__filter-tag"
            clearable
            placeholder="按标签筛选"
            @change="taskStore.reload()"
          >
            <el-option v-for="tag in tags" :key="tag" :label="tag" :value="tag" />
          </el-select>
          <el-input
            v-model="keyword"
            class="tasks__filter"
            clearable
            placeholder="按标题/描述搜索"
            @keydown.enter="taskStore.reload()"
            @clear="taskStore.reload()"
          />
          <el-button :loading="loading" @click="taskStore.load()">刷新</el-button>
        </FilterBar>
      </template>

      <el-alert
        v-if="error"
        class="tasks__error"
        type="error"
        :closable="false"
        show-icon
        :title="error"
      />

      <!-- 批量操作栏：有选中才出现 -->
      <div v-if="selection.length > 0" class="tasks__batch">
        <span class="tasks__batch-count">已选 {{ selection.length }} 条</span>
        <div class="tasks__batch-actions">
          <el-button size="small" :loading="batchWorking" @click="batchComplete">批量完成</el-button>
          <el-button size="small" type="danger" :loading="batchWorking" @click="batchDelete">
            批量删除
          </el-button>
          <el-button size="small" link @click="clearSelection">取消选择</el-button>
        </div>
      </div>

      <!-- 表格视图 -->
      <template v-if="tasksView === 'table'">
        <el-table
          v-loading="loading"
          :data="sortedTasks"
          empty-text="还没有任务"
          @selection-change="(rows: TaskItem[]) => (selection = rows)"
        >
          <el-table-column type="selection" width="42" />
          <el-table-column label="任务" min-width="220">
            <template #default="{ row }">
              <div class="tasks__title">
                <span :class="{ 'tasks__title--done': row.status === 'DONE' }">{{ row.title }}</span>
                <el-tag v-if="row.overdue" type="danger" size="small" effect="plain">逾期</el-tag>
              </div>
              <div v-if="row.description" class="tasks__description">{{ row.description }}</div>
              <div v-if="row.tags.length > 0" class="tasks__tags">
                <el-tag v-for="tag in row.tags" :key="tag" size="small" effect="plain">
                  {{ tag }}
                </el-tag>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="优先级" width="90">
            <template #default="{ row }">
              <StatusTag :status="row.priority" :map="PRIORITY_MAP" />
            </template>
          </el-table-column>

          <el-table-column label="截止时间" width="180" sortable>
            <template #default="{ row }">{{ formatDateTime(row.dueAt) }}</template>
          </el-table-column>

          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <StatusTag :status="row.status" :map="STATUS_MAP" />
            </template>
          </el-table-column>

          <el-table-column label="操作" width="190" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
              <el-button link type="primary" size="small" @click="handleToggle(row)">
                {{ row.status === 'DONE' ? '取消完成' : '完成' }}
              </el-button>
              <el-button link type="danger" size="small" @click="handleDelete(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>

      <!-- 看板视图：三列，空列有空态；无拖拽（明确不含） -->
      <div v-else v-loading="loading" class="tasks__board">
        <section v-for="column in boardColumns" :key="column.key" class="tasks__board-column">
          <header class="tasks__board-header">
            <span class="tasks__board-title">{{ column.label }}</span>
            <span class="tasks__board-count">{{ column.items.length }}</span>
          </header>
          <EmptyState v-if="column.items.length === 0" description="暂无任务" />
          <ul v-else class="tasks__board-list">
            <li v-for="task in column.items" :key="task.id" class="tasks__board-card">
              <div class="tasks__board-card-title" :class="{ 'tasks__title--done': task.status === 'DONE' }">
                {{ task.title }}
              </div>
              <div v-if="task.description" class="tasks__board-card-description">
                {{ task.description }}
              </div>
              <div class="tasks__board-card-meta">
                <StatusTag :status="task.priority" :map="PRIORITY_MAP" />
                <span class="tasks__board-card-due">{{ formatDateTime(task.dueAt) }}</span>
                <el-tag v-if="task.overdue" type="danger" size="small" effect="plain">逾期</el-tag>
              </div>
              <div v-if="task.tags.length > 0" class="tasks__tags">
                <el-tag v-for="tag in task.tags" :key="tag" size="small" effect="plain">
                  {{ tag }}
                </el-tag>
              </div>
              <div class="tasks__board-card-actions">
                <el-button link type="primary" size="small" @click="handleToggle(task)">
                  {{ task.status === 'DONE' ? '取消完成' : '完成' }}
                </el-button>
                <el-button link type="primary" size="small" @click="openEdit(task)">编辑</el-button>
                <el-button link type="danger" size="small" @click="handleDelete(task)">删除</el-button>
              </div>
            </li>
          </ul>
        </section>
      </div>

      <AppPagination
        v-if="tasksView === 'table'"
        :total="total"
        :page="page"
        :size="size"
        @change="(next: number) => taskStore.goToPage(next)"
      />
    </el-card>

    <el-dialog v-model="editing" title="编辑任务" width="520px">
      <el-form label-width="80px">
        <el-form-item label="标题" required>
          <el-input v-model="editForm.title" maxlength="200" placeholder="任务标题" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input
            v-model="editForm.description"
            type="textarea"
            :rows="3"
            maxlength="2000"
            placeholder="留空即清空描述"
          />
        </el-form-item>
        <el-form-item label="优先级">
          <el-select v-model="editForm.priority">
            <el-option
              v-for="option in priorityOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="editForm.status">
            <el-option label="待办" value="TODO" />
            <el-option label="进行中" value="DOING" />
            <el-option label="已完成" value="DONE" />
          </el-select>
        </el-form-item>
        <el-form-item label="截止时间">
          <el-date-picker v-model="editForm.dueAt" type="datetime" placeholder="留空即清除截止时间" />
        </el-form-item>
        <el-form-item label="标签">
          <el-select
            v-model="editForm.tags"
            style="width: 100%"
            multiple
            filterable
            allow-create
            default-first-option
            :multiple-limit="5"
            placeholder="最多 5 个；全部删除即清空标签"
          >
            <el-option v-for="tag in tags" :key="tag" :label="tag" :value="tag" />
          </el-select>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="editing = false">取消</el-button>
        <el-button type="primary" :loading="editSaving" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.tasks {
  display: flex;
  flex-direction: column;
  gap: var(--os-stack-gap);
}

.tasks__stats {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}

.tasks__overdue {
  color: var(--el-color-danger);
}

.tasks__create {
  display: flex;
  flex-wrap: wrap;
  gap: var(--os-space-2);
}

.tasks__create-title {
  flex: 1;
  min-width: 220px;
}

.tasks__create-priority {
  width: 100px;
}

.tasks__create-due {
  width: 220px;
}

.tasks__create-tags {
  width: 240px;
}

.tasks__view-switch {
  display: flex;
  align-items: center;
}

.tasks__filters {
  margin-top: var(--os-space-2);
}

.tasks__filter {
  width: 160px;
}

.tasks__error {
  margin-bottom: var(--os-space-3);
}

/* ---- 批量操作栏 ---- */

.tasks__batch {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--os-space-3);
  margin-bottom: var(--os-space-3);
  padding: var(--os-space-2) var(--os-space-3);
  border-radius: var(--os-radius-md);
  background: var(--el-color-primary-light-9);
}

.tasks__batch-count {
  font-size: var(--os-font-xs);
  color: var(--el-color-primary);
}

.tasks__batch-actions {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
}

/* ---- 看板 ---- */

.tasks__board {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--os-space-3);
  align-items: start;
}

.tasks__board-column {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-2);
  min-height: 120px;
  padding: var(--os-space-2);
  border-radius: var(--os-radius-lg);
  background: var(--el-fill-color-lighter);
}

.tasks__board-header {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
  padding: 0 var(--os-space-1);
}

.tasks__board-title {
  font-size: var(--os-font-xs);
  font-weight: 600;
}

.tasks__board-count {
  padding: 0 6px;
  border-radius: 999px;
  background: var(--el-bg-color);
  color: var(--el-text-color-secondary);
  font-size: 11px;
}

.tasks__board-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--os-space-2);
}

.tasks__board-card {
  padding: var(--os-space-2) var(--os-space-3);
  border-radius: var(--os-radius-md);
  background: var(--el-bg-color);
  box-shadow: var(--os-shadow-card);
}

.tasks__board-card-title {
  font-size: var(--os-font-xs);
  font-weight: 600;
  line-height: 1.5;
}

.tasks__board-card-description {
  margin-top: 2px;
  color: var(--el-text-color-secondary);
  font-size: 11px;
  line-height: 1.5;
  /* 描述最多两行，超出省略——看板卡片要能一扫多张 */
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.tasks__board-card-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--os-space-2);
  margin-top: var(--os-space-2);
}

.tasks__board-card-due {
  color: var(--el-text-color-secondary);
  font-size: 11px;
}

.tasks__board-card-actions {
  display: flex;
  gap: var(--os-space-1);
  margin-top: var(--os-space-1);
}

.tasks__title {
  display: flex;
  align-items: center;
  gap: var(--os-space-1);
}

.tasks__title--done {
  color: var(--el-text-color-secondary);
  text-decoration: line-through;
}

.tasks__description {
  margin-top: 2px;
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}

.tasks__tags {
  display: flex;
  flex-wrap: wrap;
  gap: var(--os-space-1);
  margin-top: var(--os-space-1);
}

/* 窄屏：看板三列纵向堆叠（spec Edge Cases） */
@media (max-width: 900px) {
  .tasks__board {
    grid-template-columns: 1fr;
  }
}
</style>
