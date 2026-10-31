<script setup lang="ts">
/**
 * 新建任务表单（从 TasksView 拆出，2026-10-26）。
 *
 * <p>表单自己的输入状态放在自己身上：父组件只接收「一条可提交的任务」，
 * 不再需要维护 4 个零散 ref 与重置逻辑。
 */
import { ElMessage } from 'element-plus'
import { ref } from 'vue'

import PageHeader from '@/components/PageHeader.vue'
import { PRIORITY_OPTIONS } from '@/lib/task-meta'
import type { TaskPriority } from '@/types/api'

const props = defineProps<{
  /** 已有标签，供下拉选择 */
  tags: string[]
  /** 待办/逾期统计，显示在卡片头部 */
  pendingCount: number
  overdueCount: number
  /** 提交中：按钮转圈并禁止重复提交 */
  creating: boolean
}>()

const emit = defineEmits<{
  (e: 'create', payload: { title: string; priority: TaskPriority; dueAt: string | undefined; tags: string[] }): void
}>()

const title = ref('')
const priority = ref<TaskPriority>('MEDIUM')
const dueDate = ref<Date | null>(null)
const pickedTags = ref<string[]>([])

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
  emit('create', {
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
}

defineExpose({ reset })
</script>

<template>
  <el-card shadow="never">
    <template #header>
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

    <div class="task-create">
      <el-input
        v-model="title"
        aria-label="任务标题"
        class="task-create__title"
        maxlength="200"
        placeholder="任务标题，例如：周五前交周报"
        @keydown.enter="submit"
      />
      <el-select v-model="priority" class="task-create__priority" aria-label="优先级">
        <el-option v-for="option in PRIORITY_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
      </el-select>
      <el-date-picker
        v-model="dueDate"
        type="datetime"
        aria-label="截止时间"
        placeholder="截止时间（可选）"
        class="task-create__due"
      />
      <el-select
        v-model="pickedTags"
        aria-label="任务标签"
        class="task-create__tags"
        multiple
        filterable
        allow-create
        default-first-option
        :multiple-limit="5"
        placeholder="标签（可选，最多 5 个）"
      >
        <el-option v-for="tag in tags" :key="tag" :label="tag" :value="tag" />
      </el-select>
      <el-button type="primary" :loading="creating" :disabled="!title.trim()" @click="submit">新建任务</el-button>
    </div>
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
  display: grid;
  grid-template-columns: minmax(0, 1fr) 120px 220px auto;
  gap: var(--os-space-3);
  align-items: start;
}

.task-create__title {
  grid-column: 1 / -1;
  min-width: 0;
}

.task-create__priority,
.task-create__due,
.task-create__tags {
  width: 100%;
  min-width: 0;
}

.task-create__tags {
  grid-column: 1;
  grid-row: 2;
}

.task-create :deep(.el-date-editor.el-input) {
  width: 100%;
}

.task-create > .el-button {
  grid-column: 4;
  justify-self: end;
  margin-left: 0;
}

@media (max-width: 600px) {
  .task-create {
    grid-template-columns: minmax(0, 1fr);
  }

  .task-create__title,
  .task-create__tags,
  .task-create > .el-button {
    grid-column: auto;
    grid-row: auto;
  }

  .task-create > .el-button {
    width: 100%;
  }
}
</style>
