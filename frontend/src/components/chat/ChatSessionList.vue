<script setup lang="ts">
/**
 * 会话列表（对话页左栏）。
 *
 * <p>从 ChatView 拆出（2026-10-26）：这里只有「展示 + 转发意图」，
 * 会话的增删改查全部由父组件调 store 完成 —— 子组件不持有业务状态，
 * 因此切换页面时不会留下半截状态。
 *
 * <p>分组在组件内计算：`groupSessionsByDay` 是纯函数，放这里能少一层 props，
 * 也让「列表长什么样」这件事只有一个出处。
 */
import { computed } from 'vue'

import { groupSessionsByDay } from '@/lib/session-group'
import { relativeTime } from '@/lib/relative-time'
import type { ChatSession } from '@/types/api'

const props = defineProps<{
  sessions: ChatSession[]
  currentId: string | null
  keyword: string
  total: number
  page: number
  size: number
  loading: boolean
}>()

const emit = defineEmits<{
  (e: 'update:keyword', value: string): void
  (e: 'select', conversationId: string): void
  (e: 'refresh'): void
  (e: 'create'): void
  (e: 'rename', session: ChatSession): void
  (e: 'remove', conversationId: string): void
  (e: 'page-change', page: number): void
  (e: 'search'): void
}>()

const groups = computed(() => groupSessionsByDay(props.sessions))
</script>

<template>
  <el-card class="chat-sessions" shadow="never">
    <template #header>
      <div class="chat-sessions__header">
        <span class="chat-sessions__title">会话</span>
        <div class="chat-sessions__actions">
          <el-button size="small" :loading="loading" @click="emit('refresh')">刷新</el-button>
          <el-button type="primary" size="small" @click="emit('create')">新建</el-button>
        </div>
      </div>
      <el-input
        :model-value="keyword"
        class="chat-sessions__filter"
        clearable
        size="small"
        placeholder="按标题搜索会话"
        @update:model-value="(value: string) => emit('update:keyword', value)"
        @keydown.enter="emit('search')"
        @clear="emit('search')"
      />
    </template>

    <el-empty v-if="sessions.length === 0" :description="keyword ? '没有匹配的会话' : '还没有会话'" :image-size="60" />

    <div v-else class="chat-sessions__groups">
      <section v-for="group in groups" :key="group.key" class="chat-sessions__group">
        <div class="chat-sessions__group-label">{{ group.label }}</div>
        <ul class="chat-sessions__list">
          <li
            v-for="session in group.items"
            :key="session.conversationId"
            :class="['chat-sessions__item', { 'chat-sessions__item--active': session.conversationId === currentId }]"
            @click="emit('select', session.conversationId)"
          >
            <div class="chat-sessions__body">
              <div class="chat-sessions__title-text">{{ session.title }}</div>
              <div class="chat-sessions__meta">
                {{ session.messageCount }} 条 · {{ relativeTime(session.updatedAt) }}
              </div>
            </div>
            <div class="chat-sessions__item-actions">
              <el-button link type="primary" size="small" @click.stop="emit('rename', session)"> 重命名 </el-button>
              <el-button link type="danger" size="small" @click.stop="emit('remove', session.conversationId)">
                删除
              </el-button>
            </div>
          </li>
        </ul>
      </section>
    </div>

    <div v-if="total > 0" class="chat-sessions__pagination">
      <span class="chat-sessions__total">共 {{ total }} 个</span>
      <el-pagination
        layout="prev, pager, next"
        small
        background
        :total="total"
        :current-page="page + 1"
        :page-size="size"
        @current-change="(next: number) => emit('page-change', next - 1)"
      />
    </div>
  </el-card>
</template>

<style scoped>
/* ---- 会话列表卡（全高，内部滚动） ---- */

.chat-sessions {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.chat-sessions :deep(.el-card__body) {
  flex: 1;
  overflow-y: auto;
  min-height: 0;
  padding: var(--os-space-3);
}

.chat-sessions__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.chat-sessions__title {
  font-size: var(--os-font-sm);
  font-weight: 500;
}

.chat-sessions__actions {
  display: flex;
  align-items: center;
  gap: var(--os-space-1);
}

.chat-sessions__filter {
  margin-top: var(--os-space-2);
}

.chat-sessions__groups {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-4);
}

.chat-sessions__group-label {
  margin-bottom: var(--os-space-1);
  padding: 0 var(--os-space-1);
  color: var(--el-text-color-placeholder);
  font-size: 11px;
}

.chat-sessions__list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.chat-sessions__item {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--os-space-2);
  padding: var(--os-space-2) 10px;
  border-radius: var(--os-radius-md);
  cursor: pointer;
  transition: background var(--os-duration-fast) var(--os-ease);
}

.chat-sessions__item:hover {
  background: var(--el-fill-color);
}

/* 当前会话：主色左边条 + 浅底（阶段 20，FR-009） */
.chat-sessions__item--active {
  background: var(--el-color-primary-light-9);
}

.chat-sessions__item--active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 20%;
  bottom: 20%;
  width: 3px;
  border-radius: 2px;
  background: var(--el-color-primary);
}

.chat-sessions__item-actions {
  position: absolute;
  right: var(--os-space-2);
  top: 50%;
  transform: translateY(-50%);
  display: flex;
  align-items: center;
  gap: var(--os-space-1);
  padding-left: var(--os-space-3);
  background: inherit;
  opacity: 0;
  transition: opacity var(--os-duration-fast) var(--os-ease);
}

.chat-sessions__item:hover .chat-sessions__item-actions,
.chat-sessions__item:focus-within .chat-sessions__item-actions {
  opacity: 1;
}

@media (hover: none) {
  .chat-sessions__item-actions {
    position: static;
    transform: none;
    opacity: 1;
    background: transparent;
    padding-left: 0;
  }
}

.chat-sessions__body {
  min-width: 0;
  flex: 1;
}

.chat-sessions__title-text {
  font-size: var(--os-font-xs);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat-sessions__meta {
  margin-top: 1px;
  font-size: 11px;
  color: var(--el-text-color-placeholder);
}

.chat-sessions__pagination {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--os-space-2);
  margin-top: var(--os-space-2);
}

.chat-sessions__total {
  color: var(--el-text-color-secondary);
  font-size: 11px;
}

/* 窄屏：列表改为横向可滚动的固定高度区块，不与对话区争高度 */
@media (max-width: 900px) {
  .chat-sessions {
    max-height: 300px;
  }
}
</style>
