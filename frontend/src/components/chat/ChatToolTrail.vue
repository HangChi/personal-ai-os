<script setup lang="ts">
/**
 * 本会话的工具调用轨迹（从 ChatView 拆出，2026-10-26）。
 *
 * <p>数据源是审计接口按 conversationId 过滤的结果。默认折叠：
 * 它是「想深究时再看」的信息，展开会挤占对话本身的空间。
 *
 * <p>耗时字段可能是 null（未真正执行，例如停在待确认），显示为「—」而不是 0ms ——
 * 两者语义完全不同。
 */
import { QuestionFilled } from '@element-plus/icons-vue'

import StatusTag from '@/components/StatusTag.vue'
import { relativeTime } from '@/lib/relative-time'
import type { ToolInvocation } from '@/types/api'

defineProps<{ items: ToolInvocation[] }>()

/** 工具调用结果徽标映射（StatusTag 的 map） */
const OUTCOME_MAP: Record<string, { label: string; type: 'success' | 'danger' | 'warning' | 'info' }> = {
  SUCCESS: { label: '成功', type: 'success' },
  FAILED: { label: '失败', type: 'danger' },
  REJECTED: { label: '被拒绝', type: 'warning' },
  REQUIRES_CONFIRMATION: { label: '待确认', type: 'warning' },
  DUPLICATE: { label: '已去重', type: 'info' },
  IN_PROGRESS: { label: '执行中', type: 'info' },
}

/** 工具耗时可读化：null 表示未真正执行（待确认），与 0ms 区分开 */
function formatDuration(durationMs: number | null): string {
  return durationMs === null ? '—' : `${durationMs} ms`
}
</script>

<template>
  <el-collapse class="chat-trail">
    <el-collapse-item>
      <template #title>
        <span class="chat-trail__title">
          本会话工具调用（{{ items.length }}）
          <el-tooltip content="来自审计记录，展示助手在这个会话里真实调用过的工具" placement="top">
            <el-icon class="chat-trail__help"><QuestionFilled /></el-icon>
          </el-tooltip>
        </span>
      </template>
      <ul class="chat-trail__list">
        <li v-for="item in items" :key="item.id" class="chat-trail__item">
          <span class="chat-trail__operation">{{ item.operation }}</span>
          <StatusTag :status="item.outcome" :map="OUTCOME_MAP" />
          <span class="chat-trail__duration">{{ formatDuration(item.durationMs) }}</span>
          <span class="chat-trail__time">{{ relativeTime(item.createdAt) }}</span>
        </li>
      </ul>
    </el-collapse-item>
  </el-collapse>
</template>

<style scoped>
.chat-trail {
  margin-top: var(--os-space-3);
  border-top: 1px solid var(--el-border-color-lighter);
}

.chat-trail__title {
  display: inline-flex;
  align-items: center;
  gap: var(--os-space-1);
  font-size: 11px;
  color: var(--el-text-color-secondary);
}

.chat-trail__help {
  cursor: help;
}

.chat-trail__list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--os-space-1);
}

.chat-trail__item {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--os-space-2);
  font-size: 11px;
}

.chat-trail__operation {
  font-family: 'SFMono-Regular', Consolas, monospace;
  color: var(--el-text-color-regular);
}

.chat-trail__duration,
.chat-trail__time {
  color: var(--el-text-color-secondary);
}

.chat-trail__time {
  margin-left: auto;
}
</style>
