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
import { Delete, Download, EditPen, Plus, Refresh } from '@element-plus/icons-vue'
import { computed, nextTick, ref, watch, type ComponentPublicInstance } from 'vue'

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

/*
 * 切换会话时必须退出编辑态（2026-10-29）。
 *
 * <h3>为什么不能只靠 `@blur`</h3>
 *
 * 用户报的原话是「点修改图标后点其他会话，那一行还是修改标题的状态」。
 * 直接原因是：`@blur` 是编辑态**唯一**的退出通道，而它当时因为 ref 收集成数组
 * 的 bug 从来没触发过（详见 `renameInput` 的注释）。ref 修好之后 blur 能工作了，
 * 但**仍然不该只依赖它** —— blur 是「焦点去哪了」的副作用，不是「用户在切会话」
 * 的语义。任何让输入框保持焦点的路径都会漏：
 *
 * - 拖拽/键盘（`Tab` 到会话项后按 Enter）选中别的会话；
 * - 将来会话项改成 `pointerdown` 触发（blur 与 select 的先后顺序会变）；
 * - 输入框因某种原因没有真正拿到焦点（这次的真实故障）。
 *
 * 所以要有一条**声明式**的规则：只要「正在编辑的行」不是「当前选中的会话」，
 * 编辑态就必须退出。这条规则不关心焦点，只关心状态之间的一致性。
 *
 * <p>注意两个细节：
 * <ol>
 *   <li>**不能走 `commitEdit`**：切会话不是「确认改名」的动作，走 commit 会把
 *       用户随手敲了一半的文字落库 —— 他可能只是点错了行。</li>
 *   <li>⚠️ 与 `@blur` 的**先后顺序**：点别的会话时，浏览器先让输入框 blur、再派发
 *       click。若 blur 已经把改动 commit 掉，本 watch 看到的 `editingId` 已是 null，
 *       直接跳过 —— 也就是说**鼠标点击时「blur 即保存」仍然生效**（实测打字后
 *       点别处，改动确实落库）。这条 watch 兜的是 blur **不发生**的那些路径
 *       （键盘 Enter 切换、输入框未获焦点的故障态），此时才是「放弃改动」。</li>
 *   <li>`currentId` 初值是 `null`（会话还没加载完）。此时若 `editingId` 有值，
 *       说明列表正在重排，不该粗暴清掉 —— 所以只在 `currentId` 非空时判定。</li>
 * </ol>
 *
 * <p>这里**不需要**复位 `editCancelled`（曾加过，已删）：`startEdit` 每次进入编辑
 * 都会把它置回 false，所以「上一次的取消标记连坐下一次编辑」是不可能发生的。
 * 当时以为需要、并写了断言去守，注入该 bug 后测试照样全绿 —— 那就是一段死代码
 * 配一条假断言。**验证方式：把修复删掉，测试必须变红；不变红就不是修复。**
 */
watch(
  () => props.currentId,
  (id) => {
    if (!id || editingId.value === null) {
      return
    }
    if (editingId.value !== id) {
      editingId.value = null
    }
  },
)

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
 *
 * <h3>⚠️ 但用 ref 时踩了个更隐蔽的坑（2026-10-29 修）</h3>
 *
 * 这个 `ref` 写在 `v-for` **内部**。Vue 3 对这种写法有特殊处理：
 * 同名 ref 会被收集成**数组**，所以 `renameInput.value` 是 `[input]` 而不是 `input`，
 * `.focus()` 直接不存在 —— 运行时报 `renameInput.value?.focus is not a function`。
 *
 * 而 `?.` 只防 `null`、不防「有值但没有该方法」，所以它**没能兜住**这个错误。
 * 后果是连锁的、且完全静默（不在控制台附近就看不到）：
 * <ol>
 *   <li>输入框**从未获得焦点** → 用户点重命名图标后无法直接打字；</li>
 *   <li>既然没焦点，`@blur` 就**永远不会触发** → 编辑态没有任何退出通道；</li>
 *   <li>于是点别的会话时，上一行仍停在一个空的输入框里，标题「消失」了。</li>
 * </ol>
 * 实测确认：手动 `input.focus()` 之后再点别的会话，编辑态正常退出（输入框归 0）；
 * 不手动聚焦则残留 1 个。
 *
 * <p>修法是**不用 `ref` 属性收集**，而是用函数式 ref 只认「当前正在编辑的那一个」。
 * 这样无论 `v-for` 渲染多少行，`renameInput` 永远只指向 `editingId` 对应的元素。
 */
const renameInput = ref<HTMLInputElement | null>(null)

/**
 * 绑定到重命名输入框的 ref 回调。
 *
 * <p>函数式 ref 在元素挂载时收到 DOM、卸载时收到 `null`，正好拿来做「只认当前编辑行」。
 * 必须在 `v-for` 里按行判断，否则最后一行会把引用覆盖掉。
 */
function setRenameInput(el: Element | ComponentPublicInstance | null, conversationId: string): void {
  if (conversationId !== editingId.value) {
    return
  }
  renameInput.value = (el as HTMLInputElement | null) ?? null
}

// 进入编辑态后聚焦并全选，省得用户再手动清空原标题
watch(editingId, async (id) => {
  if (!id) {
    return
  }
  await nextTick()
  // 这里不再是数组：setRenameInput 已经把引用收敛成单个元素。
  // 仍用 `?.` 兜底「快速双击导致 nextTick 前就被取消」的情况。
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
          <el-tooltip content="刷新会话" placement="top">
            <el-button
              text
              class="chat-sessions__action"
              :icon="Refresh"
              :loading="loading"
              :disabled="loading"
              aria-label="刷新会话"
              @click="emit('refresh')"
            />
          </el-tooltip>
          <el-tooltip content="新建会话" placement="top">
            <el-button
              text
              type="primary"
              class="chat-sessions__action"
              :icon="Plus"
              aria-label="新建会话"
              @click="emit('create')"
            />
          </el-tooltip>
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
                  :ref="(el) => setRenameInput(el, session.conversationId)"
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

/*
 * 头部统一为 28px 图标按钮，显式覆盖全局按钮的高度和相邻外边距。
 */
.chat-sessions__actions :deep(.el-button) {
  width: var(--os-button-height-inline);
  height: var(--os-button-height-inline);
  min-height: var(--os-button-height-inline);
  margin-left: 0;
  padding: 0;
  flex: none;
  border-radius: var(--os-radius-sm);
}

.chat-sessions__actions :deep(.el-button:focus-visible) {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 2px;
}

/*
 * 轨上按钮 32×32 而不是 24：折叠态只有这一列，横向没有别的可点区域，
 * 给足热区比省 8px 宽更重要（实测 24 在触控板上要瞄）。
 *
 * ⚠️ `margin-left: 0` 不是可有可无的：EP 有 `.el-button + .el-button { margin-left: 8px }`
 * —— 那是为**横向并排**的按钮排间距写的。折叠轨是纵向堆叠，这条规则照样命中，
 * 于是第二个按钮被推右 8px。实测两个 32px 按钮的中心一个在 260、一个在 264，
 * 整列是歪的（4px 偏移，肉眼不是明显歪，但叠在正中的计数与首字上就看得出不齐）。
 * 结构上说：**纵向堆叠里不能沿用为横向并排写的间距规则**，间距交给 gap。
 */
/*
 * 会话数量：折叠态唯一能表达「你有几个会话」的地方。
 * 用等宽数字，数量从 9 变 10 时不会左右跳。
 */
/*
 * 当前会话首字标记。
 *
 * 用主色浅底 + 主色字，与展开态那一行的 `--active` 底色同一套语义
 * （不是自己发明一个颜色）。圆形而非方形：折叠轨上它就是一枚「头像」，
 * 与消息区的头像形成同一种形状语言。
 */
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

/*
 * 窄屏：列表的**限高**不在这里，在 ChatView。
 *
 * <p>这里原先还有一条 `@media (max-width: 900px) { .chat-sessions { max-height: 300px } }`。
 * 它与 `ChatView.vue` 里的 `max-height: 200px` 指向**同一个元素**（本组件的根元素），
 * 特异性也相同，谁赢取决于打包后的 CSS 顺序 —— 实测赢的是 200px，
 * 也就是说这条 300px **从未生效过**，是一段看起来在管事、实际没有的死代码。
 *
 * <p>这条遵循本文件下面那条已写明的原则：**同一个 UI 元素只能有一处尺寸声明**。
 * 根元素尺寸属于「页面决定它占多高」（本组件只被 ChatView 使用一次），
 * 所以留在 ChatView。这里只保留组件内部元素（竖轨/按钮/卡片内容）的样式。
 */
</style>
