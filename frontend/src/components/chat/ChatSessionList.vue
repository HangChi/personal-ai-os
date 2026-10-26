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
import { Delete, EditPen } from '@element-plus/icons-vue'
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

    <!--
      滚动只包住列表：分页是「我在第几页」的常驻信息，应该钉在卡片底部。
      改造前它跟列表一起滚，会话多时要滚到最底才能翻页。
    -->
    <div class="chat-sessions__scroll">
      <el-empty
        v-if="sessions.length === 0"
        :description="keyword ? '没有匹配的会话' : '还没有会话'"
        :image-size="60"
      />

      <div class="chat-sessions__groups">
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
              <!--
              图标按钮而不是文字按钮：两个文字按钮（重命名/删除）在 260px 的侧栏里
              既挤占标题宽度，hover 时还会盖在标题上。图标体积固定、语义靠 title 兜底。
              容器常驻占位、只切换透明度 —— 避免 hover 时标题被挤压位移。
            -->
              <div class="chat-sessions__item-actions">
                <el-button
                  link
                  type="primary"
                  size="small"
                  title="重命名"
                  :icon="EditPen"
                  @click.stop="emit('rename', session)"
                />
                <el-button
                  link
                  type="danger"
                  size="small"
                  title="删除"
                  :icon="Delete"
                  @click.stop="emit('remove', session.conversationId)"
                />
              </div>
            </li>
          </ul>
        </section>
      </div>
    </div>

    <div v-if="total > 0" class="chat-sessions__pagination">
      <span class="chat-sessions__total">共 {{ total }} 个</span>
      <!--
        去掉 background：实心蓝底的页码块在 260px 侧栏里过重，且与列表选中态的
        浅主色底撞色。居中排列，左右不再一边空一边挤。
      -->
      <el-pagination
        layout="prev, pager, next"
        small
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
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: 0;
}

/* 滚动只发生在列表容器里；分页与空态不参与滚动 */
.chat-sessions__scroll {
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
  font-size: var(--os-font-xs);
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
  transition:
    background var(--os-duration-fast) var(--os-ease),
    color var(--os-duration-fast) var(--os-ease);
}

.chat-sessions__item:hover {
  background: var(--el-fill-color);
}

/*
 * 当前会话：主色左边条 + 浅底（阶段 20，FR-009）。
 * 标题同时加重、meta 提到 secondary：选中态不能只靠背景色 ——
 * 浅主色底在白底上很淡，弱视/暗色模式下几乎看不出来。
 */
.chat-sessions__item--active {
  background: var(--el-color-primary-light-9);
}

.chat-sessions__item--active .chat-sessions__title-text {
  font-weight: 500;
  color: var(--el-color-primary);
}

.chat-sessions__item--active .chat-sessions__meta {
  color: var(--el-text-color-secondary);
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
  /* 常驻占位、只切换透明度：absolute 定位会让按钮盖在标题上，且 hover 时标题被挤压 */
  flex: none;
  display: flex;
  align-items: center;
  gap: var(--os-space-1);
  opacity: 0;
  transition: opacity var(--os-duration-fast) var(--os-ease);
}

.chat-sessions__item-actions :deep(.el-button) {
  margin-left: 0;
  padding: 4px;
}

.chat-sessions__item:hover .chat-sessions__item-actions,
.chat-sessions__item:focus-within .chat-sessions__item-actions {
  opacity: 1;
}

@media (hover: none) {
  .chat-sessions__item-actions {
    opacity: 1;
  }
}

.chat-sessions__body {
  min-width: 0;
  flex: 1;
}

.chat-sessions__title-text {
  font-size: var(--os-font-xs);
  font-weight: 400;
  color: var(--el-text-color-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  transition: color var(--os-duration-fast) var(--os-ease);
}

.chat-sessions__meta {
  margin-top: 2px;
  font-size: var(--os-font-xs);
  color: var(--el-text-color-placeholder);
}

.chat-sessions__pagination {
  /* 钉在卡片底部：flex: none + 自身内边距，不随列表滚动 */
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: var(--os-space-2);
  padding: var(--os-space-3);
  border-top: 1px solid var(--el-border-color-lighter);
}

.chat-sessions__total {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}

/* 窄屏：列表改为横向可滚动的固定高度区块，不与对话区争高度 */
@media (max-width: 900px) {
  .chat-sessions {
    max-height: 300px;
  }
}
</style>
