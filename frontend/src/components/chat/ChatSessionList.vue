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
import { Delete, Download, EditPen, Expand, Fold, Plus } from '@element-plus/icons-vue'
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
  /** 折叠态：收成 48px 竖轨（2026-10-30）。状态归父组件，这里只负责渲染 */
  collapsed: boolean
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
  /** 折叠态请求展开：传 false 是「展开」，将来若要「收起」也走这里 */
  (e: 'toggle-collapse', collapsed: boolean): void
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

/*
 * 折叠态（2026-10-30）。
 *
 * <h3>为什么收成「竖条」而不是整个隐藏</h3>
 *
 * 会话列表是对话页的**主导航** —— 隐藏后用户既看不到「还有几个会话」，
 * 也找不到回来的入口。所以在折叠态保留一条 48px 的竖轨：
 * 顶部一个「展开」按钮 + 一个新建按钮，中间是会话数量，下面是当前会话的
 * 首字头像。宽度从 260 降到 48，主列净增 212px。
 *
 * <p>实测各视口主列宽度（会话栏 260 时）：1440→920、1280→760、1100→580、
 * 1024→504。1024 下气泡从设计值 680 被压到 426（63%）—— 折叠后回到 638。
 */
const collapsed = computed(() => props.collapsed)

/** 当前会话的首字，作为折叠轨上的定位标记；无会话时退回一个通用标记 */
const activeInitial = computed(() => {
  const active = props.sessions.find((s) => s.conversationId === props.currentId)
  return active ? active.title.trim().charAt(0) || '·' : null
})
</script>

<template>
  <!--
    折叠态走独立的 template 分支，而不是给展开态加一堆 v-show：
    两态的 DOM 结构差异太大（列表 / 搜索框 / 分页全都不存在），
    用 v-show 会留下一个 260px 宽的隐藏盒子，宽度根本降不下来。
  -->
  <el-card v-if="collapsed" class="chat-sessions chat-sessions--rail" shadow="never">
    <div class="chat-sessions__rail">
      <el-button
        link
        class="chat-sessions__rail-btn"
        title="展开会话列表"
        aria-label="展开会话列表"
        @click="emit('toggle-collapse', false)"
      >
        <el-icon :size="16"><Expand /></el-icon>
      </el-button>
      <!--
        两个按钮同构（都是 link、都无 type）：折叠轨只有 48px 宽，一列图标里
        出现「主色 + 灰色」两种语气，「新建」会显得比「展开」重要得多 ——
        而它俩其实是平级操作。
        ⚠️ 曾经给「新建」加 `type="primary"`，实测它因边框多 2px，与上面那个
        按钮的中心相差 4px，整列看着是歪的。**结构上同构比事后对尺寸更可靠。**
      -->
      <el-button link class="chat-sessions__rail-btn" title="新建会话" aria-label="新建会话" @click="emit('create')">
        <el-icon :size="16"><Plus /></el-icon>
      </el-button>

      <div class="chat-sessions__rail-spacer" />

      <!--
        会话数量：折叠态唯一能表达「你有几个会话」的地方。
        用 title 补全语义，读屏靠 aria-label。
      -->
      <span class="chat-sessions__rail-count" :title="`共 ${total} 个会话`" :aria-label="`共 ${total} 个会话`">
        {{ total }}
      </span>

      <!--
        当前会话的首字标记：折叠轨上唯一带内容的锚点。
        有它才看得出「我现在在哪」，否则只剩两个按钮悬在一条空轨上。
      -->
      <span
        v-if="activeInitial"
        class="chat-sessions__rail-active"
        :title="`当前会话：${sessions.find((s) => s.conversationId === currentId)?.title ?? ''}`"
      >
        {{ activeInitial }}
      </span>
      <div class="chat-sessions__rail-spacer" />
    </div>
  </el-card>

  <el-card v-else class="chat-sessions" shadow="never">
    <template #header>
      <div class="chat-sessions__header">
        <span class="chat-sessions__title">会话</span>
        <div class="chat-sessions__actions">
          <el-button size="small" :loading="loading" @click="emit('refresh')">刷新</el-button>
          <el-button type="primary" size="small" @click="emit('create')">新建</el-button>
          <!--
            收起按钮放在头部的动作组里，而不是卡片边缘：
            这一行本来就是「会话 + 三个动作」的语义分组，收起属于同一族。
            图标用 Fold（与全站侧栏折叠一致），title 与 aria-label 都写全，
            因为纯图标按钮没有可读文本。
          -->
          <el-button
            link
            class="chat-sessions__collapse"
            title="收起会话列表"
            aria-label="收起会话列表"
            @click="emit('toggle-collapse', true)"
          >
            <el-icon :size="15"><Fold /></el-icon>
          </el-button>
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
 * 收起按钮的可点区（2026-10-30）。
 *
 * `el-button link` 的高度只由内容决定（15px 图标 → 约 20px 高），
 * 而同排的「刷新」「新建」是 24px（EP small）—— 三个动作里两个高一个矮，
 * 底边对不齐。这里补到 24px 并保持图标居中：可点区与同排按钮同高，
 * 视觉上才是「一组」，也更好点中。
 *
 * 宽度同样给足 24：纯图标按钮不显式定宽时宽度由图标撑开（15px），
 * 比 24 窄 9px，该是 24×24 的热区会变成 15×24 的窄条。
 */
.chat-sessions__collapse {
  width: 24px;
  height: 24px;
  padding: 0;
}

/* ---- 折叠态：48px 竖轨 ---- */

/*
 * 竖轨的宽度由父层（ChatView 的 grid 列宽）决定，这里只负责「撑满 + 纵向排布」。
 * 卡片在折叠态要把默认的 padding / 边框噪点压掉：48px 宽里再留 20px 内边距，
 * 装不下一个 24px 的按钮。
 */
.chat-sessions--rail :deep(.el-card__body) {
  padding: var(--os-space-2) 0;
  flex: 1;
  display: flex;
  min-height: 0;
}

.chat-sessions__rail {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--os-space-1);
  padding: 0 var(--os-space-1);
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
.chat-sessions__rail-btn {
  width: 32px;
  height: 32px;
  padding: 0;
  flex: none;
  margin-left: 0;
  color: var(--el-text-color-regular);
}

.chat-sessions__rail-btn:hover {
  color: var(--el-color-primary);
}

.chat-sessions__rail-spacer {
  flex: 1;
  min-height: var(--os-space-2);
}

/*
 * 会话数量：折叠态唯一能表达「你有几个会话」的地方。
 * 用等宽数字，数量从 9 变 10 时不会左右跳。
 */
.chat-sessions__rail-count {
  flex: none;
  padding: 2px 0;
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
  font-variant-numeric: tabular-nums;
  text-align: center;
  line-height: 1.2;
}

/*
 * 当前会话首字标记。
 *
 * 用主色浅底 + 主色字，与展开态那一行的 `--active` 底色同一套语义
 * （不是自己发明一个颜色）。圆形而非方形：折叠轨上它就是一枚「头像」，
 * 与消息区的头像形成同一种形状语言。
 */
.chat-sessions__rail-active {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  box-shadow: inset 0 0 0 1px var(--el-color-primary-light-7);
  font-size: var(--os-font-xs);
  font-weight: 600;
  line-height: 1;
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

/*
 * 窄屏折叠态：竖轨变成一条**横条**（2026-10-30）。
 *
 * <h3>为什么必须写在这里，而不是 ChatView</h3>
 *
 * 这几条最初写在 `ChatView.vue` 的 `@media (max-width: 900px)` 里，
 * 结果**一条都没生效**，而且没有任何报错：
 * `<style scoped>` 的 scope id 只加到子组件的**根元素**上，
 * 而 `.chat-sessions__rail` / `-spacer` / `-count` 全是本组件**内部**的元素，
 * 父组件的 `[data-v-parent]` 属性在它们身上根本不存在 → 选择器永不匹配。
 *
 * <p>更难发现的是**同批规则里有的生效了**：`.chat-sessions--rail :deep(.el-card__body)`
 * 作用于根元素，行为正常。于是症状看起来像「媒体查询没命中」，而不是「作用域穿不过去」。
 *
 * <p>实测退化形态（860px 折叠态，修前）：`dir=column`、卡片高 **156px**、
 * 按钮被 `align-items: center` 居中到 `l:526` —— 一条占满整行、
 * 里面只有两个小图标的高条。修后：`dir=row`、卡片高 44px、按钮靠左。
 *
 * <p>结论：**样式跟着元素走**。「页面级」的约束（这一栏占多高、多少列）留在页面；
 * 「组件内部长什么样」留在组件，哪怕它只在窄屏生效。
 */
@media (max-width: 900px) {
  /* 横条的气泡内边距：竖轨的 8px 0 换成横条的 4px 12px（左起图标、右收计数） */
  .chat-sessions--rail :deep(.el-card__body) {
    padding: var(--os-space-1) var(--os-space-3);
  }

  .chat-sessions__rail {
    flex-direction: row;
    justify-content: flex-start;
    gap: var(--os-space-2);
  }

  /*
   * 横条里撑杆要整个移除，不能只压 `flex: 0`（2026-10-30 实测修正）。
   *
   * <p>竖轨靠两个 `flex: 1` 的撑杆把「计数 + 首字」夹在正中；横条里改为
   * 「图标靠左、读数靠右」，撑杆没有存在的理由了。压成 `flex: 0` 后它的宽度是 0，
   * **但它仍然是一个 flex 项，仍然要吃掉一个 `gap`（8px）** ——
   * 实测：按钮左边缘距卡片 17px，而首字右边缘距卡片 25px，左右差正好 8px，
   * 就是尾部那个 0 宽撑杆的 gap。42px 高的条里，这点不对称看着像「右边没对齐」。
   *
   * <p>`display: none` 才是「不存在」。移除后两侧都是 17px（1px 边框 + 12px body + 4px rail）。
   */
  .chat-sessions__rail-spacer {
    display: none;
  }

  /* 「数量 + 首字」靠右收尾，中间的空隙由自动外边距吃掉 */
  .chat-sessions__rail-count {
    margin-left: auto;
    padding: 0;
  }
}
</style>
