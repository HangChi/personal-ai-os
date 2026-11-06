<script setup lang="ts">
import { ref, watch } from 'vue'
import { listTasks, getTask } from '@/api/tasks'
import type { ProjectItem } from '@/api/workspace'
import type { TaskItem, TaskRecurrence } from '@/types/api'
import { TASK_RECURRENCES } from '@/lib/today'

export interface TaskOrganization {
  projectId: string
  parentTaskId: string
  recurrenceType: TaskRecurrence
  recurrenceTimezone: string
}
const model = defineModel<TaskOrganization>({ required: true })
const props = defineProps<{ projects: ProjectItem[]; taskId?: string }>()
const parents = ref<TaskItem[]>([])
const loading = ref(false)
const error = ref('')
let sequence = 0
async function searchParents(keyword = ''): Promise<void> {
  const seq = ++sequence
  loading.value = true
  error.value = ''
  try {
    const page = await listTasks({
      keyword: keyword || undefined,
      projectId: model.value.projectId || undefined,
      incomplete: true,
      size: 100,
      page: 0,
    })
    let selected: TaskItem | undefined
    if (model.value.parentTaskId) selected = await getTask(model.value.parentTaskId)
    if (seq !== sequence) return
    const candidates = page.items.filter(
      (task) =>
        task.id !== props.taskId &&
        !task.parentTaskId &&
        (task.projectId ?? '') === model.value.projectId &&
        (!task.recurrenceType || task.recurrenceType === 'NONE'),
    )
    if (selected && !candidates.some((t) => t.id === selected.id)) candidates.unshift(selected)
    parents.value = candidates
  } catch (cause) {
    if (seq === sequence) error.value = cause instanceof Error ? cause.message : '父任务加载失败'
  } finally {
    if (seq === sequence) loading.value = false
  }
}
function changeProject(): void {
  model.value.parentTaskId = ''
  void searchParents()
}
function changeParent(): void {
  if (model.value.parentTaskId) model.value.recurrenceType = 'NONE'
}
watch(
  () => [model.value.projectId, model.value.parentTaskId, props.taskId],
  () => {
    if (model.value.parentTaskId) void searchParents()
    else {
      sequence++
      error.value = ''
      parents.value = []
      loading.value = false
    }
  },
  { immediate: true },
)
</script>
<template>
  <div class="task-organization">
    <el-form-item label="所属项目">
      <el-select v-model="model.projectId" clearable placeholder="不属于项目" @change="changeProject">
        <el-option
          v-for="project in projects.filter((p) => !p.archived || p.id === model.projectId)"
          :key="project.id"
          :value="project.id"
          :label="project.name + (project.archived ? '（已归档）' : '')"
        />
      </el-select>
    </el-form-item>
    <el-form-item label="父任务（可选，仅支持一级）">
      <el-select
        v-model="model.parentTaskId"
        clearable
        filterable
        remote
        :remote-method="searchParents"
        :loading="loading"
        placeholder="输入标题搜索同项目父任务"
        @visible-change="
          (visible: boolean) => {
            if (visible) searchParents()
          }
        "
        @change="changeParent"
      >
        <el-option v-for="task in parents" :key="task.id" :value="task.id" :label="task.title" />
      </el-select>
      <p v-if="error" role="alert">{{ error }} <el-button link @click="searchParents()">重试</el-button></p>
    </el-form-item>
    <el-form-item label="重复规则">
      <el-select v-model="model.recurrenceType" :disabled="Boolean(model.parentTaskId)">
        <el-option v-for="rule in TASK_RECURRENCES" :key="rule.value" :value="rule.value" :label="rule.label" />
      </el-select>
    </el-form-item>
    <el-form-item v-if="model.recurrenceType !== 'NONE'" label="重复时区">
      <el-input v-model="model.recurrenceTimezone" maxlength="100" placeholder="例如 Asia/Shanghai" />
    </el-form-item>
    <p v-if="model.recurrenceType !== 'NONE'" class="task-organization__hint">
      请设置截止时间。完成本期后按原截止时间生成下一期；每月保留原始日期，遇短月取月末。
    </p>
  </div>
</template>
<style scoped>
.task-organization {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--os-space-2) var(--os-space-4);
  width: 100%;
}
.task-organization :deep(.el-select) {
  width: 100%;
}
.task-organization :deep(.el-form-item) {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  margin-bottom: var(--os-space-2);
}
.task-organization :deep(.el-form-item__label) {
  justify-content: flex-start;
}
.task-organization__hint,
p {
  grid-column: 1/-1;
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
  line-height: 1.6;
}
@media (max-width: 600px) {
  .task-organization {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
