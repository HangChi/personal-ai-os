<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { computed, onMounted, ref } from 'vue'

import AppPagination from '@/components/AppPagination.vue'
import PageHeader from '@/components/PageHeader.vue'
import { formatDateTime } from '@/lib/format'
import { toolInvocationMeta } from '@/lib/tool-invocation-status'
import { useActivityStore } from '@/stores/activity'
import type { ToolInvocationOutcome } from '@/types/api'

const store = useActivityStore()
const { invocations, page, size, total, outcome, loading, error } = storeToRefs(store)

const OUTCOME_OPTIONS: { value: ToolInvocationOutcome; label: string }[] = [
  { value: 'SUCCESS', label: '成功' },
  { value: 'FAILED', label: '失败' },
  { value: 'REJECTED', label: '被拒绝' },
  { value: 'REQUIRES_CONFIRMATION', label: '需人工确认' },
  { value: 'DUPLICATE', label: '已去重' },
]

onMounted(async () => {
  await store.loadInvocations(0)
})

/**
 * 空表文案必须区分「一条都没有」与「筛掉了」。
 * 筛选后仍说「还没有调用过工具」会让人以为审计坏了，而实际只是过滤条件太窄。
 */
const emptyText = computed(() => (outcome.value === null ? '助手还没有调用过工具' : '没有符合筛选条件的记录'))

/**
 * 时间：走 lib/format 的统一入口。
 *
 * <p>本地版本对非法时间会原样返回 ISO 串（避免 "Invalid Date"），
 * 但那样一来同一列里可能混着「2026/10/29 10:00:00」与「2026-10-29T02:00:00Z」
 * 两种格式，扫读时无法比较。统一版一律给破折号，格式不混。
 */
function formatTime(iso: string): string {
  return formatDateTime(iso)
}

/** null 表示「没有真正执行」（如待确认），与 0ms 区分开 */
function formatDuration(durationMs: number | null): string {
  return durationMs === null ? '—' : `${durationMs} ms`
}

/** 会话 ID 截断展示（阶段 18，T036）：UUID 原样显示会挤爆列宽 */
function shortConversationId(id: string | null): string {
  return id ? `${id.slice(0, 8)}…` : '—'
}

/** 视图模式：表格 / 时间线（阶段 18，T036） */
const viewMode = ref<'table' | 'timeline'>('table')
/** 时间线按天分组（当天日期作键，组内保持接口顺序） */ const timelineGroups = computed(() => {
  const groups = new Map<string, typeof invocations.value>()
  for (const item of invocations.value) {
    const date = new Date(item.createdAt)
    const key = Number.isNaN(date.getTime())
      ? '未知时间'
      : `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
    const bucket = groups.get(key)
    if (bucket) {
      bucket.push(item)
    } else {
      groups.set(key, [item])
    }
  }
  return [...groups.entries()].map(([day, items]) => ({ day, items }))
})

function handleOutcome(value: string): void {
  // 'ALL' 而不是空串：Element Plus 把空串当作「未选择」而显示 placeholder，
  // 用一个显式取值能保证「全部结果」是一个真正的选项，而不是靠占位文案暗示。
  void store.setOutcome(value === 'ALL' ? null : (value as ToolInvocationOutcome))
}
</script>

<template>
  <div class="activity">
    <PageHeader size="page" title="活动" />

    <el-card class="activity__card" shadow="never">
      <template #header>
        <div class="activity__card-header">
          <span class="activity__card-title">工具调用记录</span>
          <div class="activity__filters">
            <el-select :model-value="outcome ?? 'ALL'" size="small" class="activity__filter" @change="handleOutcome">
              <el-option label="全部结果" value="ALL" />
              <el-option
                v-for="option in OUTCOME_OPTIONS"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
            <!-- 时间线视图（T036）：审计记录按天分组，比平铺表格更适合「回顾哪天发生了什么」 -->
            <el-radio-group v-model="viewMode" size="small">
              <el-radio-button value="table">表格</el-radio-button>
              <el-radio-button value="timeline">时间线</el-radio-button>
            </el-radio-group>
            <el-button :loading="loading" @click="store.loadInvocations()">刷新</el-button>
          </div>
        </div>
      </template>

      <el-alert
        v-if="error"
        type="error"
        :closable="false"
        show-icon
        title="无法读取工具调用记录"
        :description="error"
      />

      <template v-else>
        <el-table v-if="viewMode === 'table'" v-loading="loading" :data="invocations" :empty-text="emptyText">
          <!--
            列宽（2026-10-29 按 EP 源码重算）。

            这张表 5 列内容实测只需 562px，容器 998px —— 富余 436px 无法消除，
            只能决定「怎么分」。EP 的分配规则在 `table-layout.mjs:102`：

                if (flexColumns.length > 0 && fit) { 摊剩余 }
                else { bodyWidth.value = bodyMinWidth }   // 右侧整片空白

            `flexColumns` = 没有 `width` 的列。所以：
            · 全部写死 `width` → 不摊，innerW 停在 692，右侧空 306px、表头线画一半
            · 只留一个 `min-width` → 富余全堆那一列（实测间距 436px）
            · **多个 `min-width`** → 按 minWidth 比例**每列都变宽**，间距均匀

            故一律用 `min-width`。minWidth 的比例即分配比例，例如工具列给
            260 而结果列 88，工具列会拿到约 3 倍的富余，符合「内容长的列更该宽」。
          -->
          <el-table-column label="时间" min-width="190">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column prop="operation" label="工具" min-width="190" show-overflow-tooltip />
          <el-table-column label="结果" min-width="150" align="center">
            <template #default="{ row }">
              <el-tag :type="toolInvocationMeta(row).type" size="small">
                {{ toolInvocationMeta(row).label }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="耗时" min-width="150" align="right">
            <template #default="{ row }">{{ formatDuration(row.durationMs) }}</template>
          </el-table-column>
          <el-table-column label="会话" min-width="190" show-overflow-tooltip>
            <template #default="{ row }">
              <el-tooltip v-if="row.conversationId" :content="row.conversationId" placement="top">
                <span class="activity__muted">{{ shortConversationId(row.conversationId) }}</span>
              </el-tooltip>
              <span v-else class="activity__muted">—</span>
            </template>
          </el-table-column>
        </el-table>

        <!-- 时间线视图：按天分组，一天一串 -->
        <div v-else v-loading="loading" class="activity__timeline">
          <el-empty v-if="invocations.length === 0" :description="emptyText" :image-size="70" />
          <section v-for="group in timelineGroups" :key="group.day" class="activity__timeline-day">
            <header class="activity__timeline-day-label">{{ group.day }}</header>
            <ul class="activity__timeline-list">
              <li v-for="item in group.items" :key="item.id" class="activity__timeline-item">
                <span class="activity__timeline-time">{{ formatTime(item.createdAt).slice(11) }}</span>
                <span
                  class="activity__timeline-dot"
                  :class="`activity__timeline-dot--${toolInvocationMeta(item).type}`"
                />
                <span class="activity__timeline-operation">{{ item.operation }}</span>
                <el-tag :type="toolInvocationMeta(item).type" size="small" effect="plain">
                  {{ toolInvocationMeta(item).label }}
                </el-tag>
                <span class="activity__timeline-duration">{{ formatDuration(item.durationMs) }}</span>
                <el-tooltip v-if="item.conversationId" :content="item.conversationId" placement="top">
                  <span class="activity__muted">{{ shortConversationId(item.conversationId) }}</span>
                </el-tooltip>
              </li>
            </ul>
          </section>
        </div>

        <AppPagination :total="total" :page="page" :size="size" @change="(next: number) => store.setPage(next)" />
      </template>
    </el-card>
  </div>
</template>

<style scoped>
.activity {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: var(--os-space-5);
}

/* ---- 页头 ---- */

/*
 * 页头由 PageHeader 统一渲染（size="page"），原先这里的
 * `.activity__head / __title / __subtitle` 三条规则已删除 —— 它们与
 * 另外六个页面各自写了一份，字号/字重/行高逐页微调过，
 * 于是「同一层级的标题」在不同页面并不一样。
 */

.activity__card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--os-space-4);
  flex-wrap: wrap;
}

.activity__card-title {
  font-size: var(--os-font-xl);
  font-weight: 600;
}

.activity__filters {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--os-space-2);
}

.activity__filter {
  width: 130px;
}

/* ---- 时间线视图（T036）---- */

.activity__timeline {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-4);
}

.activity__timeline-day-label {
  margin-bottom: var(--os-space-2);
  padding-bottom: var(--os-space-1);
  border-bottom: 1px solid var(--el-border-color-lighter);
  color: var(--el-text-color-regular);
  font-size: var(--os-font-xs);
  font-weight: 600;
}

.activity__timeline-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--os-space-1);
}

.activity__timeline-item {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--os-space-2);
  padding: var(--os-space-1) 0;
  font-size: var(--os-font-xs);
}

.activity__timeline-time {
  flex: none;
  width: 56px;
  color: var(--el-text-color-secondary);
  font-variant-numeric: tabular-nums;
}

.activity__timeline-dot {
  flex: none;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--el-color-info);
}

.activity__timeline-dot--success {
  background: var(--el-color-success);
}

.activity__timeline-dot--danger {
  background: var(--el-color-danger);
}

.activity__timeline-dot--warning {
  background: var(--el-color-warning);
}

.activity__timeline-operation {
  font-family: 'SFMono-Regular', Consolas, monospace;
  color: var(--el-text-color-regular);
}

.activity__timeline-duration {
  color: var(--el-text-color-secondary);
}

.activity__muted {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}
</style>
