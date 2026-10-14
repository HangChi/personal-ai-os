<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { storeToRefs } from 'pinia'
import { onMounted, ref } from 'vue'

import { useTaskStore } from '@/stores/task'
import type { UpdateTaskPayload } from '@/api/tasks'
import type { TaskItem, TaskPriority, TaskStatus } from '@/types/api'

const taskStore = useTaskStore()
const { tasks, loading, error, statusFilter, keyword, pendingCount, overdueCount, total, page, size } =
  storeToRefs(taskStore)

const newTitle = ref('')
const newPriority = ref<TaskPriority>('MEDIUM')
const newDueDate = ref<Date | null>(null)
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

function priorityTagType(priority: TaskPriority): 'info' | 'warning' | 'danger' {
  switch (priority) {
    case 'HIGH':
      return 'danger'
    case 'LOW':
      return 'info'
    default:
      return 'warning'
  }
}

function priorityLabel(priority: TaskPriority): string {
  return priorityOptions.find((option) => option.value === priority)?.label ?? priority
}

function statusLabel(status: TaskStatus): string {
  switch (status) {
    case 'DONE':
      return '已完成'
    case 'DOING':
      return '进行中'
    default:
      return '待办'
  }
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
})

async function handleCreate(): Promise<void> {
  const title = newTitle.value.trim()
  if (!title) {
    ElMessage.warning('请先填写任务标题')
    return
  }
  creating.value = true
  try {
    await taskStore.create(title, newPriority.value, newDueDate.value?.toISOString())
    if (!error.value) {
      newTitle.value = ''
      newDueDate.value = null
      newPriority.value = 'MEDIUM'
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
}>({ title: '', description: '', priority: 'MEDIUM', dueAt: null, status: 'TODO' })

function openEdit(task: TaskItem): void {
  editOriginal.value = task
  editForm.value = {
    title: task.title,
    description: task.description ?? '',
    priority: task.priority,
    dueAt: task.dueAt ? new Date(task.dueAt) : null,
    status: task.status,
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
  const payload: UpdateTaskPayload = {
    title,
    description: editForm.value.description.trim(),
    priority: editForm.value.priority,
    status: editForm.value.status,
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
</script>

<template>
  <div class="tasks">
    <el-card shadow="never">
      <template #header>
        <div class="tasks__header">
          <span>新建任务</span>
          <span class="tasks__stats">
            待办 {{ pendingCount }} 项<template v-if="overdueCount > 0">
              · <span class="tasks__overdue">逾期 {{ overdueCount }} 项</span>
            </template>
          </span>
        </div>
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
        <el-button type="primary" :loading="creating" @click="handleCreate">新建</el-button>
      </div>
    </el-card>

    <el-card class="tasks__list-card" shadow="never">
      <template #header>
        <div class="tasks__header">
          <span>任务列表</span>
          <div class="tasks__filters">
            <el-select v-model="statusFilter" class="tasks__filter" @change="taskStore.reload()">
              <el-option
                v-for="option in statusOptions"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
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
          </div>
        </div>
      </template>

      <el-alert
        v-if="error"
        class="tasks__error"
        type="error"
        :closable="false"
        show-icon
        :title="error"
      />

      <el-table v-loading="loading" :data="tasks" empty-text="还没有任务">
        <el-table-column label="任务" min-width="220">
          <template #default="{ row }">
            <div class="tasks__title">
              <span :class="{ 'tasks__title--done': row.status === 'DONE' }">{{ row.title }}</span>
              <el-tag v-if="row.overdue" type="danger" size="small" effect="plain">逾期</el-tag>
            </div>
            <div v-if="row.description" class="tasks__description">{{ row.description }}</div>
          </template>
        </el-table-column>

        <el-table-column label="优先级" width="90">
          <template #default="{ row }">
            <el-tag :type="priorityTagType(row.priority)" size="small">
              {{ priorityLabel(row.priority) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="截止时间" width="180">
          <template #default="{ row }">{{ formatDateTime(row.dueAt) }}</template>
        </el-table-column>

        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <span :class="{ 'tasks__title--done': row.status === 'DONE' }">
              {{ statusLabel(row.status) }}
            </span>
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

      <div v-if="total > 0" class="tasks__pagination">
        <span class="tasks__total">共 {{ total }} 条</span>
        <el-pagination
          layout="prev, pager, next"
          :total="total"
          :current-page="page + 1"
          :page-size="size"
          background
          @current-change="(next: number) => taskStore.goToPage(next - 1)"
        />
      </div>
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
  gap: 16px;
}

.tasks__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.tasks__stats {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.tasks__overdue {
  color: var(--el-color-danger);
}

.tasks__create {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
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

.tasks__filters {
  display: flex;
  gap: 8px;
}

.tasks__pagination {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 16px;
  margin-top: 12px;
}

.tasks__total {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.tasks__filter {
  width: 160px;
}

.tasks__error {
  margin-bottom: 12px;
}

.tasks__title {
  display: flex;
  align-items: center;
  gap: 6px;
}

.tasks__title--done {
  color: var(--el-text-color-secondary);
  text-decoration: line-through;
}

.tasks__description {
  margin-top: 2px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
