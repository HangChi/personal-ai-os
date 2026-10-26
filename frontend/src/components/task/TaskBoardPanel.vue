<script setup lang="ts">
/**
 * 任务看板视图（从 TasksView 拆出，2026-10-26）。
 *
 * <p>三列（待办 / 进行中 / 已完成），空列有空态。**不做拖拽**：
 * 移动状态要走编辑弹窗或「完成」按钮，避免误拖改状态却没有撤销入口。
 */
import { computed } from 'vue'

import EmptyState from '@/components/EmptyState.vue'
import StatusTag from '@/components/StatusTag.vue'
import { groupTasksByStatus } from '@/lib/board'
import { PRIORITY_MAP, formatTaskDateTime, isDone } from '@/lib/task-meta'
import type { TaskItem } from '@/types/api'

const props = defineProps<{
  tasks: TaskItem[]
  loading: boolean
}>()

const emit = defineEmits<{
  (e: 'toggle', task: TaskItem): void
  (e: 'edit', task: TaskItem): void
  (e: 'remove', task: TaskItem): void
}>()

const columns = computed(() => groupTasksByStatus(props.tasks))
</script>

<template>
  <div v-loading="loading" class="task-board">
    <section v-for="column in columns" :key="column.key" class="task-board__column">
      <header class="task-board__header">
        <span class="task-board__title">{{ column.label }}</span>
        <span class="task-board__count">{{ column.items.length }}</span>
      </header>
      <EmptyState v-if="column.items.length === 0" description="暂无任务" />
      <ul v-else class="task-board__list">
        <li v-for="task in column.items" :key="task.id" class="task-board__card">
          <div class="task-board__card-title" :class="{ 'task-board__card-title--done': isDone(task) }">
            {{ task.title }}
          </div>
          <div v-if="task.description" class="task-board__card-description">{{ task.description }}</div>
          <div class="task-board__card-meta">
            <StatusTag :status="task.priority" :map="PRIORITY_MAP" />
            <span class="task-board__card-due">{{ formatTaskDateTime(task.dueAt) }}</span>
            <el-tag v-if="task.overdue" type="danger" size="small" effect="plain">逾期</el-tag>
          </div>
          <div v-if="task.tags.length > 0" class="task-board__tags">
            <el-tag v-for="tag in task.tags" :key="tag" size="small" effect="plain">{{ tag }}</el-tag>
          </div>
          <div class="task-board__card-actions">
            <el-button link type="primary" size="small" @click="emit('toggle', task)">
              {{ isDone(task) ? '取消完成' : '完成' }}
            </el-button>
            <el-button link type="primary" size="small" @click="emit('edit', task)">编辑</el-button>
            <el-button link type="danger" size="small" @click="emit('remove', task)">删除</el-button>
          </div>
        </li>
      </ul>
    </section>
  </div>
</template>

<style scoped>
/* ---- 看板（三列，卡片轻阴影） ---- */

.task-board {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--os-space-3);
  align-items: start;
}

.task-board__column {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-2);
  min-height: 120px;
  padding: var(--os-space-2);
  border-radius: var(--os-radius-lg);
  background: var(--el-fill-color-lighter);
}

.task-board__header {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
  padding: 0 var(--os-space-1);
}

.task-board__title {
  font-size: var(--os-font-xs);
  font-weight: 600;
}

.task-board__count {
  padding: 0 6px;
  border-radius: 999px;
  background: var(--el-bg-color);
  color: var(--el-text-color-secondary);
  font-size: 11px;
}

.task-board__list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--os-space-2);
}

.task-board__card {
  padding: var(--os-space-2) var(--os-space-3);
  border-radius: var(--os-radius-md);
  background: var(--el-bg-color);
  box-shadow: var(--os-shadow-card);
}

.task-board__card-title {
  font-size: var(--os-font-xs);
  font-weight: 600;
  line-height: 1.5;
}

.task-board__card-title--done {
  color: var(--el-text-color-secondary);
  text-decoration: line-through;
}

.task-board__card-description {
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

.task-board__card-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--os-space-2);
  margin-top: var(--os-space-2);
}

.task-board__card-due {
  color: var(--el-text-color-secondary);
  font-size: 11px;
}

.task-board__tags {
  display: flex;
  flex-wrap: wrap;
  gap: var(--os-space-1);
  margin-top: var(--os-space-1);
}

.task-board__card-actions {
  display: flex;
  gap: var(--os-space-1);
  margin-top: var(--os-space-1);
}

/* 窄屏：看板三列纵向堆叠（spec Edge Cases） */
@media (max-width: 900px) {
  .task-board {
    grid-template-columns: 1fr;
  }
}
</style>
