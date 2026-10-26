<script setup lang="ts">
/**
 * 任务表格视图（从 TasksView 拆出，2026-10-26）。
 *
 * <p>只负责渲染与转发操作：选中行由 v-model 式事件交给父组件，
 * 因为批量操作栏在父层，需要同时知道「选了哪些」。
 */
import StatusTag from '@/components/StatusTag.vue'
import { PRIORITY_MAP, STATUS_MAP, formatTaskDateTime, isDone } from '@/lib/task-meta'
import type { TaskItem } from '@/types/api'

defineProps<{
  /** 已按截止时间升序排好的任务 */
  tasks: TaskItem[]
  loading: boolean
}>()

const emit = defineEmits<{
  (e: 'toggle', task: TaskItem): void
  (e: 'edit', task: TaskItem): void
  (e: 'remove', task: TaskItem): void
  (e: 'selection-change', rows: TaskItem[]): void
}>()
</script>

<template>
  <el-table
    v-loading="loading"
    :data="tasks"
    empty-text="还没有任务"
    @selection-change="(rows: TaskItem[]) => emit('selection-change', rows)"
  >
    <el-table-column type="selection" width="42" />
    <el-table-column label="任务" min-width="220">
      <template #default="{ row }">
        <div class="task-table__title">
          <span :class="{ 'task-table__title--done': isDone(row) }">{{ row.title }}</span>
          <el-tag v-if="row.overdue" type="danger" size="small" effect="plain">逾期</el-tag>
        </div>
        <div v-if="row.description" class="task-table__description">{{ row.description }}</div>
        <div v-if="row.tags.length > 0" class="task-table__tags">
          <el-tag v-for="tag in row.tags" :key="tag" size="small" effect="plain">{{ tag }}</el-tag>
        </div>
      </template>
    </el-table-column>

    <el-table-column label="优先级" width="90">
      <template #default="{ row }">
        <StatusTag :status="row.priority" :map="PRIORITY_MAP" />
      </template>
    </el-table-column>

    <el-table-column label="截止时间" width="180" sortable>
      <template #default="{ row }">{{ formatTaskDateTime(row.dueAt) }}</template>
    </el-table-column>

    <el-table-column label="状态" width="90">
      <template #default="{ row }">
        <StatusTag :status="row.status" :map="STATUS_MAP" />
      </template>
    </el-table-column>

    <el-table-column label="操作" width="190" fixed="right">
      <template #default="{ row }">
        <el-button link type="primary" size="small" @click="emit('edit', row)">编辑</el-button>
        <el-button link type="primary" size="small" @click="emit('toggle', row)">
          {{ isDone(row) ? '取消完成' : '完成' }}
        </el-button>
        <el-button link type="danger" size="small" @click="emit('remove', row)">删除</el-button>
      </template>
    </el-table-column>
  </el-table>
</template>

<style scoped>
.task-table__title {
  display: flex;
  align-items: center;
  gap: var(--os-space-1);
}

.task-table__title--done {
  color: var(--el-text-color-secondary);
  text-decoration: line-through;
}

.task-table__description {
  margin-top: 2px;
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}

.task-table__tags {
  display: flex;
  flex-wrap: wrap;
  gap: var(--os-space-1);
  margin-top: var(--os-space-1);
}
</style>
