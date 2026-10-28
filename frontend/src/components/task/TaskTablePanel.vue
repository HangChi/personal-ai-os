<script setup lang="ts">
/**
 * 任务表格视图（从 TasksView 拆出，2026-10-26）。
 *
 * <p>只负责渲染与转发操作：选中行由 v-model 式事件交给父组件，
 * 因为批量操作栏在父层，需要同时知道「选了哪些」。
 */
import { ref } from 'vue'

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

/**
 * 标题是否真的被截断。
 *
 * <p>只在截断时才弹提示：标题短的时候悬停弹出一个和屏幕上完全一样的文字，
 * 是纯噪音，还会挡住下面的描述。
 *
 * <p>判断放在 mouseenter 而不是每次渲染：`scrollWidth > clientWidth` 会强制布局，
 * 而表格一次渲染几十行，逐行测量会把渲染成本推高。用户悬停的只有一行，
 * 那时量一次代价可以忽略。
 */
const isTruncated = ref(false)

function rememberTruncation(event: MouseEvent): void {
  const el = event.currentTarget as HTMLElement | null
  isTruncated.value = el ? el.scrollWidth > el.clientWidth + 1 : false
}
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
          <!--
            标题用 el-tooltip 而不是原生 title：后者延迟约 1 秒、样式无法控制，
            与本系统其他提示（如知识库的列头说明）不是一套。长标题必须能看全，
            这条路径要可靠。
          -->
          <el-tooltip :content="row.title" placement="top-start" :show-after="400" :disabled="!isTruncated">
            <span
              class="task-table__title-text"
              :class="{ 'task-table__title--done': isDone(row) }"
              @mouseenter="rememberTruncation"
            >
              {{ row.title }}
            </span>
          </el-tooltip>
          <el-tag v-if="row.overdue" type="danger" size="small" effect="plain">逾期</el-tag>
          <el-tag v-for="tag in row.tags" :key="tag" class="task-table__tag" size="small" effect="plain">
            {{ tag }}
          </el-tag>
        </div>
        <!--
          描述单行截断 + 悬停看全文。
          原先整段铺开：一条 354 字的记录把行高撑到 273px，表格里出现一行高过十行，
          纵向节奏断掉，还把其他任务全挤出首屏。截断后行高收敛到 45~58px 的两档
          （有描述 / 无描述），扫读成本恒定。
          完整内容仍有两个入口：悬停看提示、点「编辑」看弹窗。
        -->
        <el-tooltip
          v-if="row.description"
          :content="row.description"
          placement="bottom-start"
          :show-after="400"
          popper-class="task-table__desc-popper"
        >
          <div class="task-table__description">{{ row.description }}</div>
        </el-tooltip>
      </template>
    </el-table-column>

    <el-table-column label="优先级" width="90">
      <template #default="{ row }">
        <StatusTag :status="row.priority" :map="PRIORITY_MAP" />
      </template>
    </el-table-column>

    <el-table-column label="截止时间" width="170" sortable>
      <template #default="{ row }">{{ formatTaskDateTime(row.dueAt) }}</template>
    </el-table-column>

    <el-table-column label="状态" width="90">
      <template #default="{ row }">
        <StatusTag :status="row.status" :map="STATUS_MAP" />
      </template>
    </el-table-column>

    <el-table-column label="操作" width="168" fixed="right">
      <template #default="{ row }">
        <div class="task-table__actions">
          <el-button link type="primary" size="small" @click="emit('edit', row)">编辑</el-button>
          <el-button link type="primary" size="small" @click="emit('toggle', row)">
            {{ isDone(row) ? '取消完成' : '完成' }}
          </el-button>
          <el-button link type="danger" size="small" @click="emit('remove', row)">删除</el-button>
        </div>
      </template>
    </el-table-column>
  </el-table>
</template>

<style scoped>
/*
 * 标题行：标题 + 标签同排。
 *
 * 标签原先单独占一行（`task-table__tags` 带 margin-top），于是每条任务固定占两行，
 * 行高被抬到 60px 以上 —— 而标签只有零星几个字。改成同排后，
 * 有标签的任务也只比无标签的高一点，行高基本恒定。
 */
.task-table__title {
  display: flex;
  align-items: center;
  gap: var(--os-space-1);
  min-width: 0;
}

/* 标题是等宽分配的主角：先占满，标签按需收缩；超长时截断 */
.task-table__title-text {
  flex: 0 1 auto;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 500;
}

.task-table__title--done {
  color: var(--el-text-color-secondary);
  text-decoration: line-through;
}

/* 标签不参与伸缩：宁可标题截断，也不要把标签挤成半个字 */
.task-table__tag {
  flex: none;
}

.task-table__description {
  margin-top: 2px;
  overflow: hidden;
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: help;
}

/*
 * 操作列：三个 link 按钮横向排布。
 *
 * 用 flex + gap 而不是依赖 EP 的 `margin-left: 12px`：
 * 后者让「编辑 完成 删除」看起来间隔比同页其他按钮组更松，
 * 而三者在语义上是同一组操作，应当更紧。
 */
.task-table__actions {
  display: flex;
  align-items: center;
  gap: var(--os-button-gap);
}

/* 整行内不再让 EP 叠加额外左边距（否则第一个按钮会凭空缩进） */
.task-table__actions .el-button + .el-button {
  margin-left: 0;
}

/*
 * 描述提示的浮层。
 *
 * 不加 scoped 是因为 el-tooltip 的 popper 渲染在 body 下、不在本组件的作用域内，
 * scoped 选择器根本选不到它（写了也是白写）。
 *
 * 限宽 + 自动换行：描述可能几百字，不限宽会拉成一条横贯屏幕的长条。
 */
:global(.task-table__desc-popper) {
  max-width: 420px;
  line-height: 1.6;
  white-space: normal;
  word-break: break-word;
}
</style>
