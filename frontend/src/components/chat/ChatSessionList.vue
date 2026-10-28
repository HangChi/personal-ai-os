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
import { Delete, Download, EditPen } from '@element-plus/icons-vue'
import { computed, nextTick, ref, watch } from 'vue'

import AppPagination from '@/components/AppPagination.vue'
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
  /** 行内重命名完成：带上新标题（已 trim、已校验），由父组件落库 */
  (e: 'rename', session: ChatSession, title: string): void
  /** 请求删除：父组件负责二次确认 */
  (e: 'remove', conversationId: string): void
  /** 导出该会话为 Markdown（父组件负责下载与提示） */
  (e: 'export', session: ChatSession): void
  (e: 'refresh'): void
  (e: 'create'): void
  (e: 'page-change', page: number): void
  (e: 'search'): void
}>()

/**
 * 行内重命名状态。
 *
 * 改造前用 ElMessageBox.prompt 弹窗：样式与页面割裂，且「改个名字要弹一层模态」
 * 本身就很重。改成行内编辑 —— 双击标题（或点编辑图标）就地改，Enter 保存、Esc 取消。
 *
 * 三个必须处理的边界：
 * 1. 点输入框不能触发「选中会话」（item 上有 click，需 stopPropagation）；
 * 2. 失焦保存，但不能在 Esc 取消后又被失焦保存一次（用 editCancelled 标记）；
 * 3. 空标题视为取消，不把会话名清空。
 */
const editingId = ref<string | null>(null)
const editingValue = ref('')
const editCancelled = ref(false)

function startEdit(session: ChatSession): void {
  editingId.value = session.conversationId
  editingValue.value = session.title
  editCancelled.value = false
}

function commitEdit(session: ChatSession): void {
  if (editCancelled.value) {
    return
  }
  const trimmed = editingValue.value.trim()
  editingId.value = null
  if (trimmed.length === 0 || trimmed.length > 200 || trimmed === session.title) {
    return
  }
  emit('rename', session, trimmed)
}

function cancelEdit(): void {
  editCancelled.value = true
  editingId.value = null
}

/**
 * 行内删除确认状态。
 *
 * 用 deletingId 而不是布尔量：同一时刻只应有一行处于待确认状态，
 * 点开另一行时上一行要自动收回（否则侧栏里同时出现两行「删除？」很混乱）。
 */
const deletingId = ref<string | null>(null)

function startDelete(session: ChatSession): void {
  editingId.value = null
  editCancelled.value = true
  deletingId.value = session.conversationId
}

function cancelDelete(): void {
  deletingId.value = null
}

function confirmDelete(session: ChatSession): void {
  deletingId.value = null
  emit('remove', session.conversationId)
}

/**
 * 重命名输入框的引用。
 *
 * 改造前是 `document.querySelector('.chat-sessions__rename')` —— 用全局选择器
 * 找一个属于本组件的元素。平时能用，但它有两个真问题：
 * 1. 组件被渲染两次（如将来的分栏视图）时拿到的是**第一个**输入框，
 *    焦点会跳到一个用户没在编辑的会话上；
 * 2. 查询时机依赖 `nextTick` 恰好晚于 DOM 更新，任何把输入框放进
 *    `v-if` 之外的地方（如改为 `v-show`）都会静默拿到 null。
 * 用 ref 后范围天然限定在本组件内，且 ref 绑定与 DOM 更新同步。
 */
const renameInput = ref<HTMLInputElement | null>(null)

// 进入编辑态后聚焦并全选，省得用户再手动清空原标题
watch(editingId, async (id) => {
  if (!id) {
    return
  }
  await nextTick()
  // 再用可选链兜底：重命名态可能在 nextTick 之前就被取消（快速双击）
  renameInput.value?.focus()
  renameInput.value?.select()
})

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
            <!--
              可点击的会话项必须是键盘可达的。
              原来只有 @click：<li> 不在 tab 序列里，键盘用户 Tab 到这里会直接跳过，
              会话切换（本页最核心的操作）对他们完全不存在 —— 这不是「体验稍差」，
              而是功能不可用。
              补三件事：tabindex 让它能聚焦、role=button 让读屏知道它可操作、
              Enter/Space 触发（Space 要 preventDefault，否则页面会同时下滚）。
            -->
            <li
              v-for="session in group.items"
              :key="session.conversationId"
              :class="['chat-sessions__item', { 'chat-sessions__item--active': session.conversationId === currentId }]"
              tabindex="0"
              role="button"
              :aria-current="session.conversationId === currentId ? 'true' : undefined"
              @click="emit('select', session.conversationId)"
              @keydown.enter.prevent="emit('select', session.conversationId)"
              @keydown.space.prevent="emit('select', session.conversationId)"
            >
              <div class="chat-sessions__body" @dblclick="startEdit(session)">
                <input
                  v-if="editingId === session.conversationId"
                  ref="renameInput"
                  :value="editingValue"
                  class="chat-sessions__rename"
                  maxlength="200"
                  @click.stop
                  @dblclick.stop
                  @input="editingValue = ($event.target as HTMLInputElement).value"
                  @keydown.enter="commitEdit(session)"
                  @keydown.esc="cancelEdit"
                  @blur="commitEdit(session)"
                />
                <div v-else class="chat-sessions__title-text" :title="session.title + '（双击重命名）'">
                  {{ session.title }}
                </div>
                <div class="chat-sessions__meta">
                  {{ session.messageCount }} 条 · {{ relativeTime(session.updatedAt) }}
                </div>
              </div>
              <!--
                行内删除确认（2026-10-27 取代 ElMessageBox 弹窗）：
                模态框样式与页面割裂、又重又丑。改成这一行就地展开成
                「删除？[取消][删除]」—— 上下文就在眼前，不会有「突然弹出个什么东西」的断裂感。
                仍然保留了「要点两下」这个防误触的核心。
              -->
              <div v-if="deletingId === session.conversationId" class="chat-sessions__confirm" @click.stop>
                <span class="chat-sessions__confirm-text">删除？消息一并清空</span>
                <button type="button" class="chat-sessions__confirm-btn" @click.stop="cancelDelete">取消</button>
                <button
                  type="button"
                  class="chat-sessions__confirm-btn chat-sessions__confirm-btn--danger"
                  @click.stop="confirmDelete(session)"
                >
                  删除
                </button>
              </div>
              <!--
              图标按钮而不是文字按钮：两个文字按钮（重命名/删除）在 260px 的侧栏里
              既挤占标题宽度，hover 时还会盖在标题上。图标体积固定、语义靠 title 兜底。
              容器常驻占位、只切换透明度 —— 避免 hover 时标题被挤压位移。
            -->
              <div v-else class="chat-sessions__item-actions">
                <!--
                  aria-label 带上会话标题（P3）：列表里每一行都有「重命名」「删除」，
                  只写动作名时读屏用户分不清要操作哪一条 —— title 不足以承担这个职责，
                  它不保证被朗读，也不适合作为唯一来源。
                -->
                <el-button
                  link
                  type="primary"
                  size="small"
                  title="重命名"
                  :aria-label="`重命名会话：${session.title}`"
                  :icon="EditPen"
                  @click.stop="startEdit(session)"
                />
                <el-button
                  link
                  type="primary"
                  size="small"
                  title="导出为 Markdown"
                  :aria-label="`导出会话：${session.title}`"
                  :icon="Download"
                  @click.stop="emit('export', session)"
                />
                <el-button
                  link
                  type="danger"
                  size="small"
                  title="删除"
                  :aria-label="`删除会话：${session.title}`"
                  :icon="Delete"
                  @click.stop="startDelete(session)"
                />
              </div>
            </li>
          </ul>
        </section>
      </div>
    </div>

    <!--
      紧凑分页：会话论「个」不谈「条」，量词由 totalLabel 传进去。
      改造前这里手写了一遍容器 + 一句「共 N 个」，与另外三处分页写法不同。
    -->
    <AppPagination
      :total="total"
      :page="page"
      :size="size"
      total-label="共 {n} 个"
      compact
      @change="(next: number) => emit('page-change', next)"
    />
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
 * 键盘焦点环。
 *
 * 会话项现在可 Tab 聚焦（tabindex="0"），但浏览器默认焦点环在这个元素上几乎看不见
 * ——而「看不见焦点」等于键盘用户不知道自己在哪，可达性只做了一半。
 * 用 :focus-visible 而不是 :focus：鼠标点击不该留下一个环（那会让界面显得脏），
 * 只有键盘导航才显示。
 */
.chat-sessions__item:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: -2px;
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

/*
 * 图标按钮的可点区：统一到主题层的 28×28（`--os-button-height-inline`）。
 *
 * 这一段原先写的是 min-width/min-height 26px + padding 6px，是阶段 23
 * 「22px 太小、抬到 26px」时的临时值；后来主题层把**所有** link 款按钮
 * 统一抬到 28px，两处叠加的结果是 min-height 26px 加上 padding 撑出的
 * 27px —— 三档规格（28 行内 / 32 常规）里没有 27 这个数，实测比同一列表里
 * 的其它行内按钮矮 1px。这里只保留尺寸约束、不再自带高度档，
 * 让它跟着主题层走，以后调整只改一个地方。
 */
.chat-sessions__item-actions :deep(.el-button) {
  min-width: var(--os-button-height-inline);
  min-height: var(--os-button-height-inline);
  margin-left: 0;
  padding: 0;
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
  font-size: var(--os-font-sm);
  font-weight: 400;
  color: var(--el-text-color-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  transition: color var(--os-duration-fast) var(--os-ease);
}

/* 行内删除确认条：与列表项同底色系，但用边框和红字让它明显区别于普通行 */
.chat-sessions__confirm {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
  padding: 0 var(--os-space-2);
  border-radius: var(--os-radius-md);
  background: var(--el-bg-color);
  font-size: var(--os-font-xs);
}

.chat-sessions__confirm-text {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--el-text-color-regular);
}

.chat-sessions__confirm-btn {
  flex: none;
  padding: 2px 8px;
  border: 1px solid var(--el-border-color);
  border-radius: var(--os-radius-sm);
  background: var(--el-bg-color);
  color: var(--el-text-color-regular);
  font-size: var(--os-font-xs);
  cursor: pointer;
}

.chat-sessions__confirm-btn:hover {
  border-color: var(--el-color-primary-light-5);
  color: var(--el-color-primary);
}

.chat-sessions__confirm-btn--danger {
  border-color: var(--el-color-danger-light-5);
  color: var(--el-color-danger);
}

.chat-sessions__confirm-btn--danger:hover {
  border-color: var(--el-color-danger);
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
}

.chat-sessions__rename {
  width: 100%;
  padding: 1px 4px;
  border: 1px solid var(--el-color-primary);
  border-radius: var(--os-radius-sm);
  background: var(--el-bg-color);
  color: var(--el-text-color-primary);
  font-size: var(--os-font-xs);
  line-height: 1.5;
  outline: none;
}

/*
 * 会话元信息（条数 · 时间）是要读的内容，不是占位符。
 * placeholder 实测只有 3.95:1，读起来发糊；secondary 约 6:1。
 */
.chat-sessions__meta {
  margin-top: 2px;
  font-size: var(--os-font-xs);
  color: var(--el-text-color-secondary);
}

/*
 * 原先这里还有 `.chat-sessions__pagination` / `.chat-sessions__total` 两段样式，
 * 是手写分页时代的残留 —— 2026-10-29 收敛到 `AppPagination` 之后，
 * 模板里已不再有这两个类，样式成了死代码。
 *
 * <p>留着它有个实际危害：它写的 `padding: var(--os-space-3)` 看起来像
 * 「分页本来就有内边距」，于是后来发现分页贴边（2026-10-28）时，
 * 在 ChatSessionList 里遍寻不着原因 —— 真正的分页在 AppPagination 里、
 * 且那边只有 `margin-top`。**同一个 UI 元素只能有一处尺寸声明。**
 */

/* 窄屏：列表改为横向可滚动的固定高度区块，不与对话区争高度 */
@media (max-width: 900px) {
  .chat-sessions {
    max-height: 300px;
  }
}
</style>
