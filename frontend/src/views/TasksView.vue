<script setup lang="ts">
/**
 * 任务页（阶段 18 改造，User Story 6；2026-10-26 按区块拆分为子组件）。
 *
 * <p>本文件只保留编排与跨区块状态：
 * - 表格/看板双视图（选择持久化到 localStorage，FR-010）
 * - 批量操作：多选后批量完成/删除，逐条调用现有接口，汇总报告成败（C-05）
 * - 表格默认按截止时间升序（无截止排最后）
 *
 * <p>具体渲染在 `components/task/`：新建表单、表格、看板、编辑弹窗各一个组件。
 */
import { ElMessage } from 'element-plus'
import { storeToRefs } from 'pinia'
import { computed, onMounted, ref } from 'vue'

import AppPagination from '@/components/AppPagination.vue'
import FilterBar from '@/components/FilterBar.vue'
import PageHeader from '@/components/PageHeader.vue'
import TaskBoardPanel from '@/components/task/TaskBoardPanel.vue'
import TaskCreateForm from '@/components/task/TaskCreateForm.vue'
import TaskEditDialog from '@/components/task/TaskEditDialog.vue'
import TaskTablePanel from '@/components/task/TaskTablePanel.vue'
import { sortByDueAsc } from '@/lib/board'
import { confirmDanger } from '@/lib/confirm-box'
import { useTaskStore } from '@/stores/task'
import { useUiStore } from '@/stores/ui'
import type { UpdateTaskPayload } from '@/api/tasks'
import type { TaskItem, TaskPriority, TaskStatus } from '@/types/api'

const taskStore = useTaskStore()
const uiStore = useUiStore()
const { tasks, loading, error, statusFilter, keyword, tagFilter, tags, pendingCount, overdueCount, total, page, size } =
  storeToRefs(taskStore)
const { tasksView } = storeToRefs(uiStore)

const createForm = ref<InstanceType<typeof TaskCreateForm> | null>(null)
const creating = ref(false)

const statusOptions: Array<{ label: string; value: TaskStatus | '' }> = [
  { label: '全部', value: '' },
  { label: '待办', value: 'TODO' },
  { label: '进行中', value: 'DOING' },
  { label: '已完成', value: 'DONE' },
]

onMounted(() => {
  void taskStore.load()
  void taskStore.refreshTags()
  void taskStore.loadStats()
})

async function handleCreate(payload: {
  title: string
  priority: TaskPriority
  dueAt: string | undefined
  tags: string[]
}): Promise<void> {
  creating.value = true
  try {
    await taskStore.create(payload.title, payload.priority, payload.dueAt, payload.tags)
    // 失败时不清空输入：用户刚填的内容不该因为一次网络抖动就没了
    if (!error.value) {
      createForm.value?.reset()
    }
  } finally {
    creating.value = false
  }
}

const editing = ref(false)
const editSaving = ref(false)
/** 正在编辑的任务快照：弹窗据此判断截止时间是否被主动清空 */
const editTarget = ref<TaskItem | null>(null)

function openEdit(task: TaskItem): void {
  editTarget.value = task
  editing.value = true
}

async function submitEdit(payload: UpdateTaskPayload): Promise<void> {
  const original = editTarget.value
  if (!original) {
    return
  }
  if (!payload.title?.trim()) {
    ElMessage.warning('标题不能为空')
    return
  }

  editSaving.value = true
  try {
    const ok = await taskStore.edit(original.id, payload)
    if (ok) {
      ElMessage.success('已保存')
      editing.value = false
    } else {
      // 失败必须**就地**提示：store.error 渲染在页面顶部的 alert 里，
      // 而弹窗盖在它上面 —— 用户只会觉得「点了没反应」
      ElMessage.error(error.value ?? '保存失败')
    }
  } finally {
    editSaving.value = false
  }
}

async function handleToggle(task: TaskItem): Promise<void> {
  await taskStore.toggleCompleted(task)
}

async function handleDelete(task: TaskItem): Promise<void> {
  // 人工直连删除无需服务端确认流程 —— 这里的二次确认就是「人做出的决定」
  const ok = await confirmDanger({
    title: '删除任务',
    message: `确定删除任务「${task.title}」？删除后不可恢复。`,
    confirmText: '删除',
  })
  if (!ok) {
    return
  }
  await taskStore.remove(task.id)
}

// ------------------------------------------------------------------ 看板与批量（阶段 18）

/** 表格数据：默认按截止升序；看板列内顺序即该排序结果 */
const sortedTasks = computed(() => sortByDueAsc(tasks.value))

/** 多选批量操作（仅表格视图；看板用卡片按钮） */
const selection = ref<TaskItem[]>([])
const batchWorking = ref(false)

function clearSelection(): void {
  selection.value = []
}

async function batchComplete(): Promise<void> {
  // 已完成的行不需要「再完成一遍」；批量完成的语义是推进未完成的行
  const targets = selection.value.filter((item) => item.status !== 'DONE')
  if (targets.length === 0) {
    ElMessage.info('选中的任务都已完成')
    return
  }
  batchWorking.value = true
  try {
    const { succeeded, failed } = await taskStore.batchComplete(targets.map((item) => item.id))
    reportBatch('完成', succeeded, failed)
    clearSelection()
  } finally {
    batchWorking.value = false
  }
}

async function batchDelete(): Promise<void> {
  if (selection.value.length === 0) {
    return
  }
  // 批量删除的后果比单条重得多，文案要给出条数，并说明「不可恢复」
  const ok = await confirmDanger({
    title: '批量删除任务',
    message: `确定删除选中的 ${selection.value.length} 条任务？删除后不可恢复。`,
    confirmText: '删除',
  })
  if (!ok) {
    return
  }
  batchWorking.value = true
  try {
    const { succeeded, failed } = await taskStore.batchDelete(selection.value.map((item) => item.id))
    reportBatch('删除', succeeded, failed)
    clearSelection()
  } finally {
    batchWorking.value = false
  }
}

/** 批量结果汇总报告：一条 message 说完，不逐条弹 toast（R-11） */
function reportBatch(action: string, succeeded: number, failed: number): void {
  if (failed === 0) {
    ElMessage.success(`已${action} ${succeeded} 条`)
  } else {
    ElMessage.warning(`${action}成功 ${succeeded} 条，失败 ${failed} 条`)
  }
}

function switchView(next: 'table' | 'board'): void {
  // 直接写 ref：持久化由 uiStore 内的 watch 负责（v-model 与命令式两条路径都覆盖）
  uiStore.tasksView = next
  clearSelection()
}
</script>

<template>
  <div class="tasks">
    <PageHeader
      size="page"
      title="任务"
      description="表格与看板两种视图，数据同一份；也可以在对话里用自然语言增删改。"
    />

    <TaskCreateForm
      ref="createForm"
      :tags="tags"
      :pending-count="pendingCount"
      :overdue-count="overdueCount"
      :creating="creating"
      @create="handleCreate"
    />

    <el-card class="tasks__list-card" shadow="never">
      <template #header>
        <PageHeader title="任务列表">
          <template #default>
            <div class="tasks__view-switch">
              <el-radio-group :model-value="tasksView" size="small" @change="switchView">
                <el-radio-button value="table">表格</el-radio-button>
                <el-radio-button value="board">看板</el-radio-button>
              </el-radio-group>
            </div>
          </template>
        </PageHeader>
        <FilterBar class="tasks__filters">
          <!-- 空串表示「全部」，Element Plus 会把它当作未选择而显示英文 placeholder，
               所以必须显式给中文 placeholder，否则界面里冒出一个 "Select" -->
          <el-select v-model="statusFilter" class="tasks__filter" placeholder="全部" @change="taskStore.reload()">
            <el-option
              v-for="option in statusOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
          <el-select
            v-model="tagFilter"
            class="tasks__filter tasks__filter-tag"
            clearable
            placeholder="按标签筛选"
            @change="taskStore.reload()"
          >
            <el-option v-for="tag in tags" :key="tag" :label="tag" :value="tag" />
          </el-select>
          <el-input
            v-model="keyword"
            class="tasks__filter"
            clearable
            placeholder="按标题/描述搜索"
            @keydown.enter="taskStore.reload()"
            @clear="taskStore.reload()"
          />
          <el-button :loading="loading" @click="taskStore.load()">刷新</el-button>
        </FilterBar>
      </template>

      <el-alert v-if="error" class="tasks__error" type="error" :closable="false" show-icon :title="error" />

      <!--
        批量操作栏：有选中才出现。
        三颗按钮去掉 size="small" —— 它们与页头「刷新」同为页面级动作，
        此前它们 24px、刷新 32px，同一块区域里两种高度。
        link 那颗保留小号：link 款有自己的行内高度（--os-button-height-inline），
        与实心按钮本就不同档，混排时视觉重量才平衡。
      -->
      <div v-if="selection.length > 0" class="tasks__batch">
        <span class="tasks__batch-count">已选 {{ selection.length }} 条</span>
        <div class="tasks__batch-actions">
          <el-button :loading="batchWorking" @click="batchComplete">批量完成</el-button>
          <el-button type="danger" :loading="batchWorking" @click="batchDelete">批量删除</el-button>
          <el-button link @click="clearSelection">取消选择</el-button>
        </div>
      </div>

      <!-- 表格视图 -->
      <TaskTablePanel
        v-if="tasksView === 'table'"
        :tasks="sortedTasks"
        :loading="loading"
        @toggle="handleToggle"
        @edit="openEdit"
        @remove="handleDelete"
        @selection-change="(rows: TaskItem[]) => (selection = rows)"
      />

      <!-- 看板视图：三列，空列有空态；无拖拽（明确不含） -->
      <TaskBoardPanel
        v-else
        :tasks="sortedTasks"
        :loading="loading"
        :total="total"
        @toggle="handleToggle"
        @edit="openEdit"
        @remove="handleDelete"
      />

      <!--
        分页对两种视图都生效。
        原先这里写着 v-if="tasksView === 'table'"：切到看板后**没有任何翻页入口**，
        而看板拿到的同样是「当前页」的数据（服务端分页，每页 20 条）。结果是任务一旦
        超过一页，看板里剩下的任务就再也看不到，用户也没有任何提示或操作能翻过去 ——
        看起来就像「任务丢了」。看板不是另一种数据源，它只是同一页数据的另一种排版。
      -->
      <AppPagination :total="total" :page="page" :size="size" @change="(next: number) => taskStore.goToPage(next)" />
    </el-card>

    <TaskEditDialog
      v-model:visible="editing"
      :task="editTarget"
      :tags="tags"
      :saving="editSaving"
      @submit="submitEdit"
    />
  </div>
</template>

<style scoped>
.tasks {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-5);
}

/*
 * 页头由 PageHeader 统一渲染（size="page"），原先这里的
 * `.tasks__head / __title / __subtitle` 三条规则已删除。
 */

.tasks__view-switch {
  display: flex;
  align-items: center;
}

.tasks__filters {
  margin-top: var(--os-space-4);
  padding-top: var(--os-space-4);
  border-top: 1px solid var(--el-border-color-lighter);
}

.tasks__filter {
  width: 160px;
}

.tasks__error {
  margin-bottom: var(--os-space-3);
}

/* ---- 批量操作栏 ---- */

.tasks__batch {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--os-space-3);
  margin-bottom: var(--os-space-3);
  padding: var(--os-space-2) var(--os-space-4);
  border: 1px solid var(--el-color-primary-light-8);
  border-radius: var(--os-radius-md);
  background: var(--el-color-primary-light-9);
  flex-wrap: wrap;
}

.tasks__batch-count {
  font-size: var(--os-font-sm);
  font-weight: 500;
  color: var(--el-color-primary);
}

.tasks__batch-actions {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
  flex-wrap: wrap;
}

.tasks__batch-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
</style>
