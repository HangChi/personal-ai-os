<script setup lang="ts">
/**
 * 本会话的工具调用轨迹（从 ChatView 拆出，2026-10-26；阶段 22 末改版）。
 *
 * <h2>为什么从「折叠面板」改成「内联活动行」</h2>
 * 改造前它是一个带顶部分隔线的折叠面板，视觉上是**独立于对话的一块区域** ——
 * 用户读到助手回复后，底部突然横着一根线和一行「本会话工具调用（1）」，
 * 既打断阅读流，也不成一组。
 *
 * <p>参考主流 agent（Codex / Claude / Cursor）的做法：工具调用是**对话流的一部分**，
 * 以紧凑的状态行内联出现，一眼能看出「助手做了什么、成没成」，不占独立区块。
 * 因此这里去掉外框与分隔线，改为「小标题 + 每条一行」的清单，
 * 每条用状态色点 + 工具名 + 结果 + 耗时表达。
 *
 * <p>耗时字段可能是 null（未真正执行，例如停在待确认），显示为「—」而不是 0ms ——
 * 两者语义完全不同。
 */
import { ArrowRight, Operation } from '@element-plus/icons-vue'
import { computed } from 'vue'

import StatusTag from '@/components/StatusTag.vue'
import { relativeTime } from '@/lib/relative-time'
import type { ToolInvocation } from '@/types/api'

const props = defineProps<{ items: ToolInvocation[] }>()

/** 汇总行要能一眼看出「有没有失败的」：失败数是唯一需要主动提醒的信号 */
const failedCount = computed(() => props.items.filter((item) => item.outcome === 'FAILED').length)

/**
 * 汇总文案：带上工具名，最多 3 个。
 *
 * 只写「已调用 1 个工具」信息量太低 —— 用户得展开才知道做了什么。
 * 带上名字后，多数情况不用展开就已经看明白了。
 */
const summaryText = computed(() => {
  const names = props.items.map((item) => item.operation)
  const shown = names.slice(0, 3).join('、')
  const suffix = names.length > 3 ? ` 等 ${names.length} 项` : ''
  return `${shown}${suffix}`
})

/** 工具调用结果徽标映射（StatusTag 的 map） */
const OUTCOME_MAP: Record<string, { label: string; type: 'success' | 'danger' | 'warning' | 'info' }> = {
  SUCCESS: { label: '成功', type: 'success' },
  FAILED: { label: '失败', type: 'danger' },
  REJECTED: { label: '被拒绝', type: 'warning' },
  REQUIRES_CONFIRMATION: { label: '待确认', type: 'warning' },
  DUPLICATE: { label: '已去重', type: 'info' },
  IN_PROGRESS: { label: '执行中', type: 'info' },
}

/** 状态色点配色：与 OUTCOME_MAP 的语义色一一对应（成功绿 / 失败红 / 等待与拒绝琥珀 / 其余灰蓝） */
const OUTCOME_DOT: Record<string, string> = {
  SUCCESS: 'success',
  FAILED: 'danger',
  REJECTED: 'warning',
  REQUIRES_CONFIRMATION: 'warning',
  DUPLICATE: 'info',
  IN_PROGRESS: 'info',
}

/** 工具耗时可读化：null 表示未真正执行（待确认），与 0ms 区分开 */
function formatDuration(durationMs: number | null): string {
  return durationMs === null ? '—' : `${durationMs} ms`
}
</script>

<template>
  <!--
    默认折叠：工具调用是「想深究时再看」的过程信息，全部展开会挤占对话本身。
    标题行只给一句汇总（调了几个、成败如何），点一下才展开明细 ——
    与 WorkBuddy / Codex 一类 agent 的做法一致。
  -->
  <el-collapse v-if="items.length > 0" class="chat-trail">
    <el-collapse-item>
      <template #title>
        <span class="chat-trail__summary">
          <el-icon :size="12"><Operation /></el-icon>
          <span class="chat-trail__summary-label">已调用 {{ items.length }} 个工具</span>
          <span class="chat-trail__summary-names">{{ summaryText }}</span>
          <span v-if="failedCount > 0" class="chat-trail__summary-fail">{{ failedCount }} 个失败</span>
          <el-icon class="chat-trail__chevron"><ArrowRight /></el-icon>
        </span>
      </template>
      <ul class="chat-trail__list">
        <li v-for="item in items" :key="item.id" class="chat-trail__item">
          <span class="chat-trail__dot" :class="`chat-trail__dot--${OUTCOME_DOT[item.outcome] ?? 'info'}`" />
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
/*
 * 内联活动行：不设外框、不加分隔线，靠左侧缩进与弱化配色「沉」到对话流里。
 * 标题用 11px 弱化色，读作「这批动作的说明」，而不是一个新面板的标题。
 */
/*
 * 内联紧凑块：宽度随内容收缩，不做通栏。
 * 通栏会让这一行看起来像「又一个面板」，而它只是这条回复的过程记录。
 */
.chat-trail {
  display: inline-block;
  max-width: 100%;
  vertical-align: top;
  /* 去掉 EP 折叠面板自带的边框与底色：它是对话流里的一行，不是设置面板 */
  border-top: none;
  --el-collapse-border-color: transparent;
  --el-collapse-header-bg-color: transparent;
  --el-collapse-content-bg-color: transparent;
}

/*
 * 折叠头实测只有 23px 高（全局给的是 30px，但组件内的 :deep 更具体，把它压回来了）。
 * 这里显式给 26px：对话流里要紧凑，但 22px 以下的命中区太难点。
 */
.chat-trail :deep(.el-collapse-item__header) {
  min-height: 26px;
  padding: 4px 6px 4px 4px;
  border-bottom: none;
  border-radius: var(--os-radius-sm);
  font-size: var(--os-font-xs);
  line-height: 1.5;
}

/* hover 才出底色：默认是一行弱化小字，不抢回复本身的注意力 */
.chat-trail :deep(.el-collapse-item__header):hover {
  background: var(--el-fill-color-lighter);
}

.chat-trail :deep(.el-collapse-item__content) {
  padding-bottom: var(--os-space-1);
}

.chat-trail :deep(.el-collapse-item__wrap) {
  background: transparent;
}

/* 汇总行：弱化到「一行小字」，箭头提示可展开 */
.chat-trail__summary {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--os-font-xs);
  color: var(--el-text-color-secondary);
  white-space: nowrap;
}

/* 「已调用 N 个工具」是标签，弱化；工具名是要读的信息，用常规色 */
.chat-trail__summary-label {
  color: var(--el-text-color-placeholder);
}

.chat-trail__summary-names {
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  color: var(--el-text-color-regular);
  font-family: 'SFMono-Regular', Consolas, monospace;
}

.chat-trail__summary .el-icon {
  color: var(--el-text-color-placeholder);
}

.chat-trail__chevron {
  transition: transform var(--os-duration-fast) var(--os-ease);
}

/* EP 给展开项加 is-active，箭头随之转向 */
.chat-trail :deep(.el-collapse-item.is-active) .chat-trail__chevron {
  transform: rotate(90deg);
}

.chat-trail__summary-fail {
  color: var(--el-color-danger);
}

.chat-trail__list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.chat-trail__item {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--os-space-2);
  padding: 3px var(--os-space-2);
  border-radius: var(--os-radius-sm);
  font-size: var(--os-font-xs);
  line-height: 1.6;
  transition: background var(--os-duration-fast) var(--os-ease);
}

.chat-trail__item:hover {
  background: var(--el-fill-color-lighter);
}

/* 状态点：6px 圆点，颜色即结果，省掉每行一个徽标的重量 */
.chat-trail__dot {
  flex: none;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--el-text-color-placeholder);
}

.chat-trail__dot--success {
  background: var(--el-color-success);
}

.chat-trail__dot--danger {
  background: var(--el-color-danger);
}

.chat-trail__dot--warning {
  background: var(--el-color-warning);
}

.chat-trail__dot--info {
  background: var(--el-color-info);
}

.chat-trail__operation {
  font-family: 'SFMono-Regular', Consolas, monospace;
  color: var(--el-text-color-regular);
}

.chat-trail__duration {
  color: var(--el-text-color-secondary);
  font-variant-numeric: tabular-nums;
}

.chat-trail__time {
  margin-left: auto;
  color: var(--el-text-color-placeholder);
}

@media (max-width: 900px) {
  /* 窄屏时间戳让位给主要信息 */
  .chat-trail__time {
    display: none;
  }
}
</style>
