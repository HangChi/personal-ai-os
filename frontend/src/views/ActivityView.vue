<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { computed, onMounted } from 'vue'

import { useActivityStore } from '@/stores/activity'
import type { ToolInvocationOutcome } from '@/types/api'

/**
 * 活动页（阶段 11）。
 *
 * 两件事放在一页，因为它们回答的是同一类问题：「系统现在到底在干什么」。
 * 上半 MCP 接入状态（本来只出现在启动日志里，降级等于静默失效），
 * 下半工具调用记录（本来只有接口没有入口）。
 *
 * 刻意**不做轮询**：MCP 状态在进程生命周期内基本不变，刷新按钮足够；
 * 审计记录由用户主动查看，自动刷新只会把正在读的行顶走。
 */
const store = useActivityStore()
const { invocations, page, size, total, outcome, loading, error, mcp, mcpLoading, mcpError } =
  storeToRefs(store)

const OUTCOME_OPTIONS: { value: ToolInvocationOutcome; label: string }[] = [
  { value: 'SUCCESS', label: '成功' },
  { value: 'FAILED', label: '失败' },
  { value: 'REJECTED', label: '被拒绝' },
  { value: 'REQUIRES_CONFIRMATION', label: '待确认' },
  { value: 'DUPLICATE', label: '已去重' },
]

const OUTCOME_META: Record<
  ToolInvocationOutcome,
  { label: string; type: 'success' | 'danger' | 'warning' | 'info' }
> = {
  SUCCESS: { label: '成功', type: 'success' },
  FAILED: { label: '失败', type: 'danger' },
  REJECTED: { label: '被拒绝', type: 'warning' },
  REQUIRES_CONFIRMATION: { label: '待确认', type: 'warning' },
  DUPLICATE: { label: '已去重', type: 'info' },
  IN_PROGRESS: { label: '执行中', type: 'info' },
}

onMounted(async () => {
  // 两块互不依赖，各自失败不影响另一块
  await Promise.all([store.loadInvocations(0), store.loadMcp()])
})

/**
 * 空表文案必须区分「一条都没有」与「筛掉了」。
 * 筛选后仍说「还没有调用过工具」会让人以为审计坏了，而实际只是过滤条件太窄。
 */
const emptyText = computed(() =>
  outcome.value === null ? '助手还没有调用过工具' : '没有符合筛选条件的记录',
)

function outcomeMeta(value: ToolInvocationOutcome) {
  return OUTCOME_META[value] ?? { label: value, type: 'info' as const }
}

/** 后端返回 ISO 时间；解析失败时原样显示，而不是显示 "Invalid Date" */
function formatTime(iso: string): string {
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleString('zh-CN', { hour12: false })
}

/** null 表示「没有真正执行」（如待确认），与 0ms 区分开 */
function formatDuration(durationMs: number | null): string {
  return durationMs === null ? '—' : `${durationMs} ms`
}

function handleOutcome(value: string): void {
  // 'ALL' 而不是空串：Element Plus 把空串当作「未选择」而显示 placeholder，
  // 用一个显式取值能保证「全部结果」是一个真正的选项，而不是靠占位文案暗示。
  void store.setOutcome(value === 'ALL' ? null : (value as ToolInvocationOutcome))
}

/** el-pagination 从 1 开始，后端从 0 开始 */
function handlePage(target: number): void {
  void store.setPage(target - 1)
}
</script>

<template>
  <div class="activity">
    <el-card class="activity__card" shadow="never">
      <template #header>
        <div class="activity__card-header">
          <span>MCP 接入状态</span>
          <el-button size="small" :loading="mcpLoading" @click="store.loadMcp()">刷新</el-button>
        </div>
      </template>

      <el-alert
        v-if="mcpError"
        type="error"
        :closable="false"
        show-icon
        title="无法读取 MCP 状态"
        :description="mcpError"
      />

      <el-skeleton v-else-if="mcpLoading && !mcp" :rows="2" animated />

      <template v-else-if="mcp">
        <el-alert
          v-if="!mcp.enabled"
          type="info"
          :closable="false"
          show-icon
          title="MCP 未启用"
          description="需要接入外部工具时，在 .env 里设置 MCP_ENABLED=true 并按 README 声明 server，然后重启后端。"
        />

        <el-alert
          v-else-if="mcp.servers.length === 0"
          type="warning"
          :closable="false"
          show-icon
          title="已启用，但没有配置任何 server"
          description="MCP_SERVERS_<名字>_COMMAND 等配置项为空，助手暂时拿不到外部工具。"
        />

        <el-table v-else :data="mcp.servers" size="small">
          <el-table-column prop="name" label="Server" width="140" show-overflow-tooltip />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.available ? 'success' : 'danger'" size="small">
                {{ row.available ? '可用' : '不可用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="toolCount" label="工具数" width="80" />
          <el-table-column label="只读直行" min-width="200">
            <template #default="{ row }">
              <span v-if="row.autoTools.length === 0" class="activity__muted">
                无（全部需确认）
              </span>
              <el-tag
                v-for="tool in row.autoTools"
                :key="tool"
                class="activity__tool-tag"
                size="small"
                effect="plain"
              >
                {{ tool }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="说明" min-width="180">
            <template #default="{ row }">
              <span v-if="row.failureReason" class="activity__reason">{{ row.failureReason }}</span>
              <span v-else class="activity__muted">—</span>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </el-card>

    <el-card class="activity__card" shadow="never">
      <template #header>
        <div class="activity__card-header">
          <span>工具调用记录</span>
          <div class="activity__filters">
            <el-select
              :model-value="outcome ?? 'ALL'"
              size="small"
              class="activity__filter"
              @change="handleOutcome"
            >
              <el-option label="全部结果" value="ALL" />
              <el-option
                v-for="option in OUTCOME_OPTIONS"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
            <el-button size="small" :loading="loading" @click="store.loadInvocations()">
              刷新
            </el-button>
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
        <el-table
          v-loading="loading"
          :data="invocations"
          :empty-text="emptyText"
          size="small"
        >
          <el-table-column label="时间" width="180">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column prop="operation" label="工具" min-width="180" show-overflow-tooltip />
          <el-table-column label="结果" width="100">
            <template #default="{ row }">
              <el-tag :type="outcomeMeta(row.outcome).type" size="small">
                {{ outcomeMeta(row.outcome).label }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="耗时" width="90">
            <template #default="{ row }">{{ formatDuration(row.durationMs) }}</template>
          </el-table-column>
          <el-table-column label="会话" min-width="150" show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="row.conversationId" class="activity__muted">{{ row.conversationId }}</span>
              <span v-else class="activity__muted">—</span>
            </template>
          </el-table-column>
        </el-table>

        <div v-if="total > 0" class="activity__pagination">
          <el-pagination
            layout="total, prev, pager, next"
            :total="total"
            :page-size="size"
            :current-page="page + 1"
            @current-change="handlePage"
          />
        </div>
      </template>
    </el-card>
  </div>
</template>

<style scoped>
.activity {
  max-width: 1080px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.activity__card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.activity__filters {
  display: flex;
  align-items: center;
  gap: 8px;
}

.activity__filter {
  width: 130px;
}

.activity__tool-tag {
  margin: 0 4px 4px 0;
}

.activity__muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.activity__reason {
  color: var(--el-color-danger);
  font-size: 12px;
  word-break: break-all;
}

.activity__pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
</style>
