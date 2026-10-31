<script setup lang="ts">
/**
 * 任务编辑弹窗（从 TasksView 拆出，2026-10-26）。
 *
 * <p>表单状态与「原始快照」都放在这里：提交时的关键判断是
 * **截止时间原本有没有值**（决定发 `clearDueAt` 还是 `dueAt`），
 * 这个信息只在弹窗生命周期内有效，留在父组件里反而要额外维护一份副本。
 */
import { ref, watch } from 'vue'

import { PRIORITY_OPTIONS } from '@/lib/task-meta'
import type { UpdateTaskPayload } from '@/api/tasks'
import type { TaskItem, TaskPriority, TaskStatus } from '@/types/api'

const props = defineProps<{
  /** 弹窗可见性（v-model） */
  visible: boolean
  /** 正在编辑的任务；null 表示无 */
  task: TaskItem | null
  /** 已有标签，供下拉选择 */
  tags: string[]
  saving: boolean
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'submit', payload: UpdateTaskPayload): void
}>()

const form = ref<{
  title: string
  description: string
  priority: TaskPriority
  dueAt: Date | null
  status: TaskStatus
  tags: string[]
}>({ title: '', description: '', priority: 'MEDIUM', dueAt: null, status: 'TODO', tags: [] })

/** 打开新任务时用它的值重置表单（浅拷贝 tags，避免直接改到列表里的数组） */
watch(
  [() => props.task, () => props.visible],
  ([task, visible]) => {
    // 依赖 visible（审查 P2）：只 watch task 引用的话，关掉再编辑**同一个任务**时
    // 引用没变、watch 不触发，表单会残留上次未保存的修改。加 visible 保证每次打开都重置。
    if (!task || !visible) {
      return
    }
    form.value = {
      title: task.title,
      description: task.description ?? '',
      priority: task.priority,
      dueAt: task.dueAt ? new Date(task.dueAt) : null,
      status: task.status,
      tags: [...task.tags],
    }
  },
  { immediate: true },
)

function submit(): void {
  const original = props.task
  if (!original) {
    return
  }
  // 描述始终提交（空串表示清空）——若用 `|| undefined`，用户就永远清不掉已有描述
  // 标签同理：数组本身能表达「清空」（`[]`），所以也始终提交
  const payload: UpdateTaskPayload = {
    title: form.value.title.trim(),
    description: form.value.description.trim(),
    priority: form.value.priority,
    status: form.value.status,
    tags: form.value.tags,
  }
  // 截止时间：有值则设置；**原本有值而现在被清空**则显式请求清除。
  // 后端用 clearDueAt 区分「未提供」与「显式置空」，这正是需要那个字段的原因。
  if (form.value.dueAt) {
    payload.dueAt = form.value.dueAt.toISOString()
  } else if (original.dueAt) {
    payload.clearDueAt = true
  }
  emit('submit', payload)
}
</script>

<template>
  <el-dialog
    :model-value="visible"
    title="编辑任务"
    width="min(520px, 92vw)"
    :close-on-click-modal="false"
    :show-close="!saving"
    :close-on-press-escape="!saving"
    @update:model-value="(value: boolean) => emit('update:visible', value)"
  >
    <el-form label-position="top" :disabled="saving">
      <el-form-item label="标题" required>
        <el-input v-model="form.title" maxlength="200" placeholder="任务标题" />
      </el-form-item>
      <el-form-item label="描述">
        <el-input v-model="form.description" type="textarea" :rows="3" maxlength="2000" placeholder="留空即清空描述" />
      </el-form-item>
      <el-form-item label="优先级">
        <el-select v-model="form.priority">
          <el-option
            v-for="option in PRIORITY_OPTIONS"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="form.status">
          <el-option label="待办" value="TODO" />
          <el-option label="进行中" value="DOING" />
          <el-option label="已完成" value="DONE" />
        </el-select>
      </el-form-item>
      <el-form-item label="截止时间">
        <el-date-picker v-model="form.dueAt" type="datetime" placeholder="留空即清除截止时间" />
      </el-form-item>
      <el-form-item label="标签">
        <el-select
          v-model="form.tags"
          style="width: 100%"
          multiple
          filterable
          allow-create
          default-first-option
          :multiple-limit="5"
          placeholder="选择已有标签，或输入后回车创建"
        >
          <el-option v-for="tag in tags" :key="tag" :label="tag" :value="tag" />
        </el-select>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button :disabled="saving" @click="emit('update:visible', false)">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="!form.title.trim()" @click="submit">保存修改</el-button>
    </template>
  </el-dialog>
</template>
