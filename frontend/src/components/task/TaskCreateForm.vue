<script setup lang="ts">
/**
 * 新建任务表单（从 TasksView 拆出，2026-10-26）。
 *
 * <p>表单自己的输入状态放在自己身上：父组件只接收「一条可提交的任务」，
 * 不再需要维护 4 个零散 ref 与重置逻辑。
 */
import { ArrowRight, Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, ref, useId } from 'vue'

import TaskOrganizationFields from '@/components/task/TaskOrganizationFields.vue'
import type { TaskOrganization } from '@/components/task/TaskOrganizationFields.vue'
import type { ProjectItem } from '@/api/workspace'
import type { CreateTaskPayload } from '@/api/tasks'
import PageHeader from '@/components/PageHeader.vue'
import { PRIORITY_OPTIONS } from '@/lib/task-meta'
import { recurrenceLabel } from '@/lib/today'
import type { TaskPriority } from '@/types/api'

const props = defineProps<{
  /** 已有标签，供下拉选择 */
  embedded?: boolean
  projects?: ProjectItem[]
  tags: string[]
  /** 待办/逾期统计，显示在卡片头部 */
  pendingCount: number
  overdueCount: number
  /** 提交中：按钮转圈并禁止重复提交 */
  creating: boolean
}>()

const emit = defineEmits<{
  (e: 'create', payload: CreateTaskPayload): void
  (e: 'cancel'): void
}>()

const organization = ref<TaskOrganization>({
  projectId: '',
  parentTaskId: '',
  recurrenceType: 'NONE',
  recurrenceTimezone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC',
})
const title = ref('')
const priority = ref<TaskPriority>('MEDIUM')
const dueDate = ref<Date | null>(null)
const pickedTags = ref<string[]>([])
const formId = useId()
const organizationExpanded = ref(false)
const organizationSummary = computed(() => {
  const { projectId, parentTaskId, recurrenceType } = organization.value
  return [
    projectId ? (props.projects?.find((project) => project.id === projectId)?.name ?? '已选项目') : '',
    parentTaskId ? '子任务' : '',
    recurrenceType !== 'NONE' ? recurrenceLabel(recurrenceType) : '',
  ]
    .filter(Boolean)
    .join(' · ')
})

function submit(): void {
  // 防连点（审查 P2）：回车与按钮都能触发，提交在途时直接返回，
  // 否则连按 Enter 会创建多条同名任务
  if (props.creating) {
    return
  }
  const trimmed = title.value.trim()
  if (!trimmed) {
    ElMessage.warning('请先填写任务标题')
    return
  }
  if (organization.value.recurrenceType !== 'NONE' && !dueDate.value) {
    ElMessage.warning('周期任务请先设置截止时间')
    document.getElementById(`${formId}-due`)?.focus()
    return
  }
  emit('create', {
    ...(organization.value.projectId ? { projectId: organization.value.projectId } : {}),
    ...(organization.value.parentTaskId ? { parentTaskId: organization.value.parentTaskId } : {}),
    ...(organization.value.recurrenceType !== 'NONE'
      ? { recurrenceType: organization.value.recurrenceType, recurrenceTimezone: organization.value.recurrenceTimezone }
      : {}),
    title: trimmed,
    priority: priority.value,
    dueAt: dueDate.value?.toISOString(),
    tags: pickedTags.value,
  })
}

/**
 * 清空表单。由父组件在提交成功后调用 ——
 * 表单不知道自己是否成功，交由调用方决定，避免「提交失败却清空了输入」。
 */
function reset(): void {
  title.value = ''
  dueDate.value = null
  priority.value = 'MEDIUM'
  pickedTags.value = []
  organization.value = { ...organization.value, parentTaskId: '', recurrenceType: 'NONE' }
  organizationExpanded.value = false
}

function startSubtask(task: import('@/types/api').TaskItem): void {
  reset()
  organization.value = { ...organization.value, projectId: task.projectId ?? '', parentTaskId: task.id }
  organizationExpanded.value = true
}
function focus(): void {
  document.getElementById(`${formId}-title`)?.focus()
}
defineExpose({ reset, startSubtask, focus })
</script>

<template>
  <el-card shadow="never" :class="{ 'os-form-card--embedded': embedded }">
    <template v-if="!embedded" #header>
      <PageHeader title="新建任务">
        <template #default>
          <span class="task-create__stats">
            待办 {{ pendingCount }} 项<template v-if="overdueCount > 0">
              · <span class="task-create__overdue">逾期 {{ overdueCount }} 项</span>
            </template>
          </span>
        </template>
      </PageHeader>
    </template>

    <el-form class="task-create" :disabled="creating" @submit.prevent="submit">
      <div class="task-create__fields">
        <div class="task-create__field task-create__field--title">
          <label class="task-create__label" :for="`${formId}-title`">任务标题</label>
          <el-input
            :id="`${formId}-title`"
            v-model="title"
            class="task-create__title"
            maxlength="200"
            aria-required="true"
            placeholder="准备做什么？"
          />
        </div>

        <div class="task-create__field task-create__field--due">
          <label class="task-create__label" :for="`${formId}-due`">截止时间</label>
          <el-date-picker
            :id="`${formId}-due`"
            v-model="dueDate"
            type="datetime"
            format="YYYY-MM-DD HH:mm"
            placeholder="不设截止时间"
            class="task-create__control"
            popper-class="task-create-date-picker"
            :popper-options="{ modifiers: [{ name: 'preventOverflow', options: { altAxis: true, padding: 12 } }] }"
          />
        </div>

        <div class="task-create__field task-create__field--priority">
          <label class="task-create__label" :for="`${formId}-priority`">优先级</label>
          <el-select :id="`${formId}-priority`" v-model="priority" class="task-create__control">
            <el-option
              v-for="option in PRIORITY_OPTIONS"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </div>

        <div class="task-create__field task-create__field--tags">
          <label class="task-create__label" :for="`${formId}-tags`">
            标签<span class="task-create__optional">最多 5 个</span>
          </label>
          <el-select
            :id="`${formId}-tags`"
            v-model="pickedTags"
            class="task-create__control"
            multiple
            filterable
            allow-create
            default-first-option
            :multiple-limit="5"
            placeholder="选择或输入标签，回车添加"
          >
            <el-option v-for="tag in tags" :key="tag" :label="tag" :value="tag" />
          </el-select>
        </div>
      </div>

      <details
        class="task-create__more"
        :open="organizationExpanded"
        @toggle="organizationExpanded = ($event.target as HTMLDetailsElement).open"
      >
        <summary class="task-create__more-toggle">
          <el-icon class="task-create__chevron" aria-hidden="true"><ArrowRight /></el-icon>
          <span>更多设置</span>
          <span class="task-create__summary" :title="organizationSummary || undefined">
            {{ organizationSummary || '项目 · 父任务 · 重复' }}
          </span>
        </summary>
        <TaskOrganizationFields v-model="organization" :projects="projects ?? []" class="task-create__organization" />
      </details>

      <div class="task-create__footer">
        <el-button v-if="embedded" :disabled="creating" @click="emit('cancel')">取消</el-button
        ><el-button type="primary" native-type="submit" :icon="Plus" :loading="creating" :disabled="!title.trim()">
          新建任务
        </el-button>
      </div>
    </el-form>
  </el-card>
</template>

<style scoped>
.task-create__stats {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}

.task-create__overdue {
  color: var(--el-color-danger);
}

.task-create {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-5);
}

.task-create__fields {
  display: grid;
  grid-template-columns: minmax(0, 1.5fr) minmax(0, 1fr);
  gap: var(--os-space-5) var(--os-space-4);
}

.task-create__field {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-2);
  min-width: 0;
}

.task-create__field--title,
.task-create__field--tags {
  grid-column: 1 / -1;
}

.task-create__title :deep(.el-input__wrapper) {
  min-height: 44px;
}

.task-create__label {
  color: var(--el-text-color-regular);
  font-size: var(--os-font-sm);
  line-height: 20px;
  font-weight: 500;
}

.task-create__optional {
  margin-left: var(--os-space-2);
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
  font-weight: 400;
}

.task-create__control,
.task-create :deep(.el-date-editor.el-input) {
  width: 100%;
  min-width: 0;
}

.task-create__more {
  min-width: 0;
  border-top: 1px solid var(--el-border-color-lighter);
}

.task-create__more-toggle {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
  min-height: 44px;
  color: var(--el-text-color-regular);
  font-size: var(--os-font-sm);
  font-weight: 500;
  cursor: pointer;
  list-style: none;
}

.task-create__more-toggle::-webkit-details-marker {
  display: none;
}

.task-create__more-toggle:hover {
  color: var(--el-color-primary);
}

.task-create__more-toggle:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 2px;
  border-radius: var(--os-radius-sm);
}

.task-create__chevron {
  flex: none;
}

.task-create__more[open] .task-create__chevron {
  transform: rotate(90deg);
}

.task-create__summary {
  min-width: 0;
  flex: 1;
  text-align: right;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
  font-weight: 400;
}

.task-create__organization {
  padding-top: var(--os-space-3);
  gap: var(--os-space-4);
}

.task-create__organization :deep(.el-form-item) {
  min-width: 0;
  margin-bottom: 0;
}

.task-create__organization :deep(.el-form-item__label) {
  height: auto;
  padding-bottom: var(--os-space-2);
  line-height: 20px;
  font-size: var(--os-font-sm);
  font-weight: 500;
}

.task-create__footer {
  display: flex;
  justify-content: flex-end;
  gap: var(--os-space-2);
  padding-top: var(--os-space-4);
  border-top: 1px solid var(--el-border-color-lighter);
}

.task-create__footer > .el-button {
  min-width: 80px;
}

.task-create__footer > .el-button--primary {
  min-width: 120px;
}

.task-create__footer > .el-button + .el-button {
  margin-left: 0;
}

@media (max-width: 600px) {
  :global(.task-create-date-picker .el-date-picker) {
    width: min(328px, calc(100vw - 24px));
    max-height: calc(100dvh - 24px);
    overflow-y: auto;
  }

  :global(.task-create-date-picker .el-picker-panel__content) {
    width: auto;
  }

  .task-create__fields {
    grid-template-columns: minmax(0, 1fr);
  }

  .task-create .task-create__footer > .el-button {
    flex: 1;
    min-height: 44px;
  }
}

@media (pointer: coarse) {
  .task-create :deep(.el-input__wrapper),
  .task-create :deep(.el-select__wrapper) {
    min-height: 44px;
  }
}
</style>
