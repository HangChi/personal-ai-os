<script setup lang="ts">
/**
 * 新建任务表单（从 TasksView 拆出，2026-10-26）。
 *
 * <p>表单自己的输入状态放在自己身上：父组件只接收「一条可提交的任务」，
 * 不再需要维护 4 个零散 ref 与重置逻辑。
 */
import { Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { ref, useId } from 'vue'

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
const formId = useId()

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

    <el-form class="task-create" :disabled="creating" @submit.prevent="submit">
      <div class="task-create__field task-create__field--title">
        <label class="task-create__label" :for="`${formId}-title`">任务标题</label>
        <el-input :id="`${formId}-title`" v-model="title" maxlength="200" placeholder="例如：周五前交周报" />
      </div>

      <div class="task-create__field">
        <label class="task-create__label" :for="`${formId}-tags`">
          标签<span class="task-create__optional">可选，最多 5 个</span>
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
          placeholder="选择或输入标签"
        >
          <el-option v-for="tag in tags" :key="tag" :label="tag" :value="tag" />
        </el-select>
      </div>

      <div class="task-create__field">
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

      <div class="task-create__field">
        <label class="task-create__label" :for="`${formId}-due`">
          截止时间<span class="task-create__optional">可选</span>
        </label>
        <el-date-picker
          :id="`${formId}-due`"
          v-model="dueDate"
          type="datetime"
          format="YYYY-MM-DD HH:mm"
          placeholder="选择截止时间"
          class="task-create__control"
        />
      </div>

      <div class="task-create__footer">
        <span class="task-create__hint">填写标题后即可创建任务</span>
        <el-button type="primary" native-type="submit" :icon="Plus" :loading="creating" :disabled="!title.trim()">
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
  display: grid;
  grid-template-columns: minmax(0, 1.5fr) minmax(120px, 0.65fr) minmax(210px, 1fr);
  gap: var(--os-space-4) var(--os-space-5);
}

.task-create__field {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-2);
  min-width: 0;
}

.task-create__field--title {
  grid-column: 1 / -1;
}

.task-create__label {
  color: var(--el-text-color-regular);
  font-size: var(--os-font-sm);
  line-height: var(--os-space-6);
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

.task-create__footer {
  grid-column: 1 / -1;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--os-space-4);
  padding-top: var(--os-space-4);
  border-top: 1px solid var(--el-border-color-lighter);
}

.task-create__hint {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}

.task-create__footer > .el-button {
  flex: 0 0 auto;
}

@media (max-width: 600px) {
  .task-create {
    grid-template-columns: minmax(0, 1fr);
  }

  .task-create__field--title,
  .task-create__footer {
    grid-column: auto;
  }

  .task-create__footer {
    align-items: stretch;
    flex-direction: column;
    gap: var(--os-space-3);
  }

  .task-create__footer > .el-button {
    width: 100%;
  }
}
</style>
