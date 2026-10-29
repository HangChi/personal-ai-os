<script setup lang="ts">
/**
 * 对话输入区（从 ChatView 拆出，2026-10-26；阶段 22 增加思考程度）。
 *
 * <p>盒内结构（自上而下，2026-10-29 第三版摆法）：
 * <ul>
 *   <li>输入框：textarea，单行起步、随内容长高；</li>
 *   <li>读数行：上下文仪表盘 + 字数合成一组靠右（恒定 24px，不随输入跳动）；</li>
 *   <li>工具行：附件、知识库增强（含条数）在左，模型、思考、语音、发送/停止在右。</li>
 * </ul>
 * 键盘行为在这里实现而不是父组件，因为它完全由「输入框聚焦时的按键」触发：
 *
 * <ul>
 *   <li>{@code enterSend=true}（默认）：Enter 发送，Shift+Enter 换行</li>
 *   <li>{@code enterSend=false}：Enter 换行，Ctrl/Cmd+Enter 发送</li>
 *   <li>输入法选词期间的 Enter 一律不发送（否则打不了中文）</li>
 * </ul>
 */
import {
  Close,
  Document,
  MagicStick,
  Paperclip,
  Promotion,
  Reading,
  UploadFilled,
  VideoPause,
} from '@element-plus/icons-vue'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { uploadAttachment } from '@/api/chat'
import { ElMessage } from 'element-plus'
import ChatModelPicker from '@/components/chat/ChatModelPicker.vue'
import ChatVoiceInput from '@/components/chat/ChatVoiceInput.vue'
import ContextMeter from '@/components/chat/ContextMeter.vue'
import { THINKING_HINTS, THINKING_LABELS, type ThinkingLevel } from '@/lib/settings'
import type { ContextUsage } from '@/api/chat'
import type { ChatAttachment } from '@/types/api'

const props = defineProps<{
  /** 输入内容（v-model） */
  modelValue: string
  /** 是否正在生成：决定显示「停止」还是「发送」 */
  streaming: boolean
  /** 知识库增强开关 */
  knowledgeOn: boolean
  /** 检索条数 */
  topK: number
  /** 模型思考程度 */
  thinkingLevel: ThinkingLevel
  /** Enter 是否直接发送（来自设置页偏好） */
  enterSend: boolean
  /** 本会话选中的模型；null = 跟随生效配置 */
  sessionModel: string | null
  /** 可选模型（生效配置的对话模型清单） */
  chatModels: string[]
  /** 生效配置的默认模型名（供「跟随配置」提示） */
  defaultModel: string
  /** 上下文用量（会话级读数，与字数并排） */
  contextUsage: ContextUsage
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
  (e: 'update:topK', value: number | null): void
  (e: 'update:thinkingLevel', value: ThinkingLevel): void
  (e: 'update:sessionModel', value: string | null): void
  (e: 'toggle-knowledge', next: boolean): void
  (e: 'send', attachments: ChatAttachment[]): void
  (e: 'stop'): void
  /** 改上下文预算（转发给 ContextMeter 的父级处理） */
  (e: 'change-budget', tokens: number): void
}>()

/** 字数上限，与输入框的 `maxlength` 同源，避免两处硬编码漂移 */
const MAX_LENGTH = 4000

/**
 * 待发送的附件（阶段 22）。
 *
 * <p>本地持有而不是提升到父组件：它只在本轮输入有效，发送后即清空。
 * 提升上去反而要让父组件多管一份「什么时候该清」的状态。
 */
const attachments = ref<ChatAttachment[]>([])
const uploading = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)

/** 单文件上限与后端一致（20MB）：前端先挡一道，省得传完才被拒 */
const MAX_SIZE = 20 * 1024 * 1024

// ------------------------------------------------------------------ 拖拽 / 粘贴上传

/**
 * 拖拽悬浮状态：控制「松开上传」蒙层的显隐。
 *
 * <h3>为什么用 enter/leave 计数器而不是一个布尔值</h3>
 * 拖拽过程中指针在盒内子元素之间移动时，每穿过一条子元素边界就会触发一次
 * `dragleave` 紧跟一次 `dragenter`。直接 `dragging = false` 再 `true` 虽然
 * 最终状态一样，但 `dragleave` 的 `relatedTarget` 可能是 null（移出窗口）、
 * 也可能是盒外元素 —— 用计数器只在「离开整个根元素」时归零，
 * 不猜 relatedTarget，也就不用在「移入子元素」和「真的移出」之间做区分。
 */
const dragging = ref(false)
let dragDepth = 0

function onDragEnter(event: DragEvent): void {
  // preventDefault 是「此地可放置」的声明：不做的话光标显示禁止图标，
  // 且 drop 事件根本不会派发
  event.preventDefault()
  dragDepth += 1
  dragging.value = true
}

function onDragOver(event: DragEvent): void {
  // dragover 必须持续 preventDefault，只在做 enter 时做一次不够
  event.preventDefault()
}

function onDragLeave(event: DragEvent): void {
  event.preventDefault()
  dragDepth -= 1
  if (dragDepth <= 0) {
    dragDepth = 0
    dragging.value = false
  }
}

function onDrop(event: DragEvent): void {
  event.preventDefault()
  dragDepth = 0
  dragging.value = false
  void pickFiles(event.dataTransfer?.files ?? null)
}

/**
 * 页面级兜底：拦住「把文件拖到页面别处」的浏览器默认行为。
 *
 * <p>浏览器对落在非输入区的文件，默认操作用文件 URL **替换当前文档** ——
 * 聊天页攒的会话、没发出去的草稿全部清零，且没有任何撤销余地。
 * 这条监听挂在 document 上，与盒子内的 onDrop 不冲突：盒子的 drop
 * 先处理上传（冒泡到 document 时文件已经在传了），document 这一层
 * 只负责消灭「替换文档」这个默认动作。
 */
function guardDocumentDrop(event: DragEvent): void {
  event.preventDefault()
}

/**
 * 粘贴上传：截图之后直接 Ctrl+V 进输入框，是比拖拽更高频的路径。
 *
 * <h3>为什么只拦 files、不拦 text</h3>
 * 剪贴板里同时可能有文字（复制了一段话）和文件（截了张图）。
 * 只有 files 非空时才 preventDefault —— 否则会把「粘贴文字」这个
 * 输入框的本职行为一起吞掉，用户复制的话打不出来。
 */
function onPaste(event: ClipboardEvent): void {
  const files = event.clipboardData?.files
  if (!files || files.length === 0) {
    return
  }
  event.preventDefault()
  void pickFiles(files)
}

async function pickFiles(files: FileList | null): Promise<void> {
  if (!files || files.length === 0) {
    return
  }
  uploading.value = true
  try {
    for (const file of Array.from(files)) {
      if (file.size > MAX_SIZE) {
        ElMessage.error(`${file.name} 超过 20MB`)
        continue
      }
      try {
        const uploaded = await uploadAttachment(file)
        attachments.value = [...attachments.value, uploaded]
      } catch {
        // 上传失败不影响已选中的其他文件，也不阻断输入
        ElMessage.error(`${file.name} 上传失败`)
      }
    }
  } finally {
    uploading.value = false
    // 清空 input 的 value，否则连续选同一个文件不会触发 change
    if (fileInput.value) {
      fileInput.value.value = ''
    }
  }
}

function removeAttachment(id: string): void {
  attachments.value = attachments.value.filter((item) => item.id !== id)
}

/** 发送后由父组件回调这里清空（父组件持有 draft，清空也归它） */
function handleSend(): void {
  /*
   * 上传未完成时不允许发送。
   *
   * <p>原先这里直接 emit + 清空 attachments：用户在附件还在上传时按回车，草稿被发出去
   * 而附件**不会跟着走** —— `pickFiles` 要等 await 上传返回后才把结果追加进列表，那时
   * 列表早已被清空，`[...attachments.value, uploaded]` 写进去的只是这一条，可消息已经
   * 发出去了。用户看到的是「附件没了」，而且没有任何提示解释为什么。上传失败至少还有
   * 一句错误提示，这条路径连提示都没有。
   *
   * <p>选择拦住而不是「等上传完再自动发送」：自动发送会让用户失去最后一次确认机会
   * （他可能只是想先把文字发出去）。拦一下、给一句提示，是这里最不容易出错的处理。
   */
  if (uploading.value) {
    ElMessage.warning('附件还在上传，请稍候再发送')
    return
  }
  emit('send', attachments.value)
  attachments.value = []
}

/** 附件内容地址：后端给的是相对路径，这里拼上 API 基路径 */
function attachmentUrl(item: ChatAttachment): string {
  const base = import.meta.env.VITE_API_BASE_URL ?? '/api/v1'
  return `${base}/chat/uploads/${item.id}/content`
}

/** 文件大小可读化：附件行里显示，避免一长串字节数 */
function formatSize(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(0)} KB`
  }
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

const thinkingTooltip = computed(
  () => `思考程度：${THINKING_LABELS[props.thinkingLevel]} — ${THINKING_HINTS[props.thinkingLevel]}`,
)

/**
 * 输入框占位文案。
 *
 * <h3>快捷键提示为什么不在这一行里了，也不进 placeholder</h3>
 * 它原先在第二行是一个 158.5px 的常驻 span（1440px 实测）。而第二行右段
 * 一共占 366px（提示 158.5 + 字数 48.2 + 动作组 144），第一行右段只有
 * 仪表盘 160px —— 两行「右段宽度」相差 206px，于是两条中缝分别落在
 * x≈712 与 x≈486，左右错开近 200px，整块读起来就是「交叉错位」。
 * 搬走后第二行右段降到 200px，两条中缝差 35px，视觉上连成一条竖线。
 *
 * <p>试过搬进 placeholder（「输入消息 · Enter 发送，Shift+Enter 换行」），
 * 实测 960px 下输入框内容宽只剩 372px，这句文案约 396px 会**折成两行**，
 * 两行占位文字比原来那个常驻 span 更难看。所以改成挂在**发送按钮的
 * `title`** 上：那是「按下去会发生什么」的说明，挂在动作按钮上语义最准，
 * 且完全不参与布局。窄屏看不到也不丢信息 —— 改造前 1180px 以下它本来
 * 就是隐藏的。
 */
const inputPlaceholder = computed(() => (props.knowledgeOn ? '输入消息，将基于知识库回答' : '输入消息'))

/**
 * 字数是否逼近上限（≥90% 转预警色）。
 *
 * <p>`0 / 4000` 绝大多数时候是没人看的背景信息，但贴着上限时「再打就要
 * 被截断」是用户必须知道的事 —— 此时转琥珀色，与上下文进度条的
 * warn/over 用同一套预警色系。阈值取 90% 而不是 99%：等看到红色再停，
 * 往往已经打超了（maxlength 是硬截断，超出的字直接消失）。
 */
const countNearLimit = computed(() => props.modelValue.length >= MAX_LENGTH * 0.9)

/** 发送按钮的提示：把快捷键从常驻布局里挪到这里（hover 才出现，不占位） */
const sendTooltip = computed(() =>
  props.enterSend ? 'Enter 发送（Shift+Enter 换行）' : 'Ctrl+Enter 发送（Enter 换行）',
)

/** 条数下拉的候选项：常用值 + 上限，避免用户为了填 20 而点十下步进器 */
const TOP_K_OPTIONS = [2, 4, 6, 8, 12, 20]

/** 思考档位（顺序即展示顺序） */
const THINKING_LEVELS: ThinkingLevel[] = ['off', 'standard', 'deep']

function handleKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Enter' || event.isComposing) {
    // isComposing：中文输入法选词期间的 Enter 不触发发送
    return
  }
  if (props.enterSend) {
    if (!event.shiftKey && !event.ctrlKey && !event.metaKey) {
      event.preventDefault()
      handleSend()
    }
  } else if (event.ctrlKey || event.metaKey) {
    event.preventDefault()
    handleSend()
  }
}

/**
 * 输入框随内容长高（上限 160px）。
 *
 * <p>固定行数有两个问题：短消息时输入框显得空荡，长消息时行数装不下、
 * 中途还得手动拖（已禁用 resize）。改成 2 行起步、按 scrollHeight 增长，
 * 超过上限后再内部滚动 —— 与主流聊天工具的手感一致。
 */
const root = ref<HTMLElement | null>(null)

function autosize(): void {
  const element = root.value?.querySelector('textarea')
  if (!element) {
    return
  }
  element.style.height = 'auto'
  element.style.height = `${Math.min(element.scrollHeight, 160)}px`
}

watch(
  () => props.modelValue,
  async () => {
    await nextTick()
    autosize()
  },
)

// 挂载时也算一次：从「点击建议问题」进来的内容可能一进来就不止两行
onMounted(() => {
  autosize()
  document.addEventListener('dragover', guardDocumentDrop)
  document.addEventListener('drop', guardDocumentDrop)
})

onBeforeUnmount(() => {
  document.removeEventListener('dragover', guardDocumentDrop)
  document.removeEventListener('drop', guardDocumentDrop)
})
</script>

<template>
  <!--
    根元素同时是**放置区**：拖拽监听挂在这里而不是里面的盒子上，
    附件条出现在盒子外面时，拖到那条区域上同样能松手上传。
  -->
  <div
    class="chat-composer"
    :class="{ 'chat-composer--dragging': dragging }"
    @dragenter="onDragEnter"
    @dragover="onDragOver"
    @dragleave="onDragLeave"
    @drop="onDrop"
  >
    <!-- 附件条：只在有附件时出现，避免常驻占位 -->
    <div v-if="attachments.length > 0" class="chat-composer__attachments">
      <div v-for="item in attachments" :key="item.id" class="chat-composer__attachment">
        <el-image
          v-if="item.contentType.startsWith('image/')"
          :src="attachmentUrl(item)"
          fit="cover"
          class="chat-composer__attachment-thumb"
        />
        <el-icon v-else class="chat-composer__attachment-icon"><Document /></el-icon>
        <span class="chat-composer__attachment-name" :title="item.filename">{{ item.filename }}</span>
        <span class="chat-composer__attachment-size">{{ formatSize(item.size) }}</span>
        <el-button
          link
          size="small"
          title="移除"
          :aria-label="`移除附件 ${item.filename}`"
          @click="removeAttachment(item.id)"
        >
          <el-icon :size="12"><Close /></el-icon>
        </el-button>
      </div>
    </div>

    <div ref="root" class="chat-composer__box">
      <input
        ref="fileInput"
        type="file"
        multiple
        class="chat-composer__file-input"
        @change="pickFiles(($event.target as HTMLInputElement).files)"
      />
      <el-input
        :model-value="modelValue"
        class="chat-composer__textarea"
        type="textarea"
        :rows="1"
        maxlength="4000"
        resize="none"
        :placeholder="inputPlaceholder"
        @update:model-value="(value: string) => emit('update:modelValue', value)"
        @keydown="handleKeydown"
        @paste="onPaste"
      />

      <!--
        ============ 读数行：上下文用量 + 字数，一组靠右 ============

        <h3>这一版的来历（2026-10-29，三轮反馈后的结果）</h3>
        用户最初的反馈是「上下文长度和 0/4000 的位置太丑了」。两轮尝试都被否：
        <ol>
          <li><b>分居两端</b>（最初）：上下文贴左、字数贴右，1440px 实测中间
              空 528px —— 两个 12px 灰字各站一头，读起来像没地方放的孤儿；</li>
          <li><b>字数移入输入区右下角</b>（上一版）：更丑，且打第一个字盒子
              长高 17px（高度会跳），仪表盘还留在原处没动；</li>
          <li><b>本版：两个读数合并成一组、整体靠右</b>。仪表盘从左端搬到右端，
              字数回到它旁边，一簇 24px 的读数贴着动作组上方 —— 与「工具在左、
              动作在右」的第二行形成同一套左右分工，不再有跨越大半个盒子的空跑。</li>
        </ol>

        <h3>为什么靠右、不靠左</h3>
        两个读数都是**状态**，不是**入口**：放在行的起点会抢在工具前面，而用户
        的操作顺序是先看工具再发送。靠右收尾与下方动作组（模型/思考/语音/发送）
        右对齐，右边缘成一条竖线，读起来是「这一列的收尾信息」。

        <h3>为什么两个字读数都常驻、不搞「有内容才显示」</h3>
        上一版把字数改成有条件渲染，高度随输入跳动（125 → 142），用户明确
        反馈「高度还会变」。读数行现在**恒定 24px**：无论空草稿、长草稿、
        有没有产生上下文用量，这一行都在、都一样高。代价是空态也显示
        「0 / 4000」—— 那是它本来的样子，为消掉两个字换来整盒跳动不划算。

        <h3>为什么两个读数之间只有 gap、没有分隔线</h3>
        同一族读数（12px / placeholder 灰 / tabular-nums），且仪表盘自带
        进度条这个图形锚点，与纯数字的字数已经分得开。再补一根竖线是把
        「已经分开的两组」再拆一次，纯噪音（这根线在更早的版本里加过又删）。
      -->
      <div class="chat-composer__meta-row">
        <span class="chat-composer__readings">
          <ContextMeter
            v-if="contextUsage.budgetTokens > 0"
            :usage="contextUsage"
            @change-budget="(tokens: number) => emit('change-budget', tokens)"
          />
          <span class="chat-composer__count" :class="{ 'chat-composer__count--warn': countNearLimit }">
            {{ modelValue.length }} / {{ MAX_LENGTH }}
          </span>
        </span>
      </div>

      <!--
        ============ 第二行：工具 + 发送 ============
      -->
      <div class="chat-composer__tool-row">
        <!--
          附件按钮**没有**选中态：它是个瞬时动作（打开文件选择器），不是开关。
          曾经误把知识库的 --on 类绑定复制到这里，导致一开知识库附件也变蓝。
        -->
        <button
          type="button"
          class="chat-composer__chip"
          title="添加附件（也可把文件拖到此处或直接粘贴）"
          aria-label="添加附件"
          @click="fileInput?.click()"
        >
          <el-icon :size="14"><Paperclip /></el-icon>
          <span class="chat-composer__chip-label">附件</span>
          <span v-if="uploading">…</span>
        </button>

        <!--
          ============ 知识库增强（含条数），一个胶囊 ============

          <h3>为什么是一个外层 span 包住 button + select，而不是让 select 当兄弟</h3>
          之前写成「button + 兄弟 span」时，注释说的是「同一个按钮内」，
          但 DOM 上它其实是独立 flex 项 —— 开知识库照旧**多一个 flex 项、
          多一个 gap**，只是宽度从 86 降到 59，根因没动。
          实测那版的胶囊宽度只按 button 算（108px），条数整个挂在胶囊外面。

          <p>也不能把 el-select **塞进** button 内部：button 内点任何东西都会
          冒泡成 button 的 click，点开下拉的同时会把知识库关掉。

          <p>所以拆成「容器负责胶囊的边框/底色/圆角，内部两段各自负责交互」：
          <ul>
            <li>外层 span：`height: 32` + 边框，是行里**唯一**的 flex 项；</li>
            <li>内部 button：只管开关，去边框去底色；</li>
            <li>内部 el-select：只管条数，同样去边框。</li>
          </ul>
          开启知识库只是让**这个容器**变宽（+59px），不新增 flex 项、
          不额外吃 gap。实测折行阈值：开/关都是 960px 仍 2 行、盒高恒定 133px。
        -->
        <span class="chat-composer__chip-group" :class="{ 'chat-composer__chip-group--on': knowledgeOn }">
          <button
            type="button"
            class="chat-composer__chip-trigger"
            :title="knowledgeOn ? '点击关闭知识库增强' : '开启后每条消息先检索知识库再回答'"
            :aria-pressed="knowledgeOn"
            @click="emit('toggle-knowledge', !knowledgeOn)"
          >
            <el-icon :size="14"><Reading /></el-icon>
            <span class="chat-composer__chip-label">知识库增强</span>
          </button>

          <!-- 开启后才出现的第二分段：竖线 + 条数下拉 -->
          <template v-if="knowledgeOn">
            <span class="chat-composer__chip-sep" aria-hidden="true" />
            <span class="chat-composer__topk">
              <el-select
                :model-value="topK"
                size="small"
                :teleported="true"
                title="每次检索的片段条数"
                aria-label="检索条数"
                @change="(value: number) => emit('update:topK', value)"
              >
                <el-option v-for="option in TOP_K_OPTIONS" :key="option" :label="`${option} 条`" :value="option" />
              </el-select>
            </span>
          </template>
        </span>

        <!--
          右半段：动作组。整体一个 flex 项，`margin-left: auto` 由样式段给出。

          <h3>为什么必须包一层</h3>
          不包的话 `margin-left: auto` 只能挂在某一个元素上，折行时其余元素
          会各自留在上一行，右半段被拆散。包成一个 span 后它是**一个**无法
          再分的项，折行时整体搬家。
        -->
        <span class="chat-composer__action-group">
          <!--
            ============ 模型选择 ============

            <h3>为什么模型在动作组里、且紧挨思考程度（2026-10-28 用户要求）</h3>
            用户的原话是「模型移动到思考程度旁边」。这不只是位置偏好 ——
            两者性质完全相同：都决定**这一发怎么生成**（谁来回、想多深），
            都在点发送的那一刻被读到，都有下拉菜单。放在一起之后，
            动作组的读法是「生成参数 → 填内容 → 发送」，一条因果链。

            <p>而它与左侧的附件/知识库是两类东西：那两个决定**喂什么进去**，
            这一簇决定**怎么生成**。动作组的 4px 紧凑间距正好画出这个边界。

            <h3>为什么它在思考的左边（而不是右边）</h3>
            越靠近发送按钮的越「按下即生效」：发送是终点，思考与其紧邻
            （2026-10-28 已按此调整过语音与思考的顺序）。模型是同一个层级里
            更「宏观」的设置（改它影响的是整段对话而不只是这一条），
            放最外侧符合「从粗到细」的阅读顺序。
          -->
          <ChatModelPicker
            :model-value="sessionModel"
            :models="chatModels"
            :default-model="defaultModel"
            :disabled="streaming"
            @update:model-value="(value: string | null) => emit('update:sessionModel', value)"
          />

          <!--
            ============ 思考程度 ============

            <h3>为什么在语音左边（2026-10-28 用户要求对调）</h3>
            两者性质不同：思考程度是**发送前就要定好**的生成参数（点发送那一刻
            生效，事后改不了）；语音输入是**填内容**的工具（用完就结束）。
            越靠近发送按钮的，越是「按下发送时会被读到的设置」——
            所以思考贴发送、语音往左让一位。
          -->
          <el-dropdown
            trigger="click"
            placement="top-end"
            @command="(value: ThinkingLevel) => emit('update:thinkingLevel', value)"
          >
            <!--
            纯图标触发器：不显示「思考」二字，也不显示档位文字 ——
            档位用颜色区分（灰=关闭 / 主色=标准 / 琥珀=深度），
            当前档位与代价由原生 title 提示。
            刻意**不用** el-tooltip 包裹：它会插在按钮外层抢走点击，
            实测导致下拉菜单打不开（点了只显示提示，选项弹不出来）。
          -->
            <el-button
              text
              size="small"
              class="chat-composer__thinking-trigger"
              :class="`chat-composer__thinking-trigger--${props.thinkingLevel}`"
              :title="thinkingTooltip"
              :aria-label="`思考程度：${THINKING_LABELS[props.thinkingLevel]}。${thinkingTooltip}`"
            >
              <el-icon :size="14"><MagicStick /></el-icon>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu class="chat-composer__thinking-menu">
                <el-dropdown-item
                  v-for="level in THINKING_LEVELS"
                  :key="level"
                  :command="level"
                  :title="THINKING_HINTS[level]"
                >
                  <!--
                  只放档位名，不放代价说明：说明放在原生 title 里（hover 才看），
                  菜单保持三行短标签 —— 原来把说明并排挤在一行里又长又吵。
                  当前档不再禁用：禁用会让用户以为点坏了，实际只是想关菜单而已。
                -->
                  <span class="chat-composer__thinking-option">
                    <span :class="{ 'chat-composer__thinking-option--on': level === thinkingLevel }">
                      {{ THINKING_LABELS[level] }}
                    </span>
                  </span>
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>

          <!--
            语音输入：产出的是「输入框内容」，与发送是同一件事的两个环节。
          -->
          <ChatVoiceInput
            :draft="modelValue"
            :disabled="streaming"
            @update:draft="(value: string) => emit('update:modelValue', value)"
          />

          <!--
            停止按钮用 danger 实底：打断生成是这一行里唯一的「中止」动作，
            白色默认按钮混在一排灰色图标里不够醒目，与主色的「发送」也拉不开
            距离。红色 + 停止图标，让「正在生成时该点哪儿」一眼可辨。
          -->
          <el-button v-if="streaming" type="danger" round @click="emit('stop')">
            <el-icon :size="12"><VideoPause /></el-icon>
            停止
          </el-button>
          <!-- 上传期间同时置灰：拦截逻辑在 handleSend 里，但按钮先给一个「现在不能点」的视觉信号 -->
          <el-button
            v-else
            type="primary"
            round
            :disabled="modelValue.trim().length === 0 || uploading"
            :loading="uploading"
            :title="sendTooltip"
            @click="handleSend"
          >
            <el-icon :size="13"><Promotion /></el-icon>
            发送
          </el-button>
        </span>
      </div>
    </div>

    <!--
      拖拽蒙层：盖住整个输入区（含附件条），把「现在可以松手」说清楚。

      <p>`pointer-events: none` 是必须的：蒙层若接走指针事件，dragover/drop
      就不再落到根元素上，计数器会立刻归零、蒙层闪烁后消失，文件也传不上去。
    -->
    <div v-if="dragging" class="chat-composer__drop-mask" aria-hidden="true">
      <el-icon :size="16"><UploadFilled /></el-icon>
      <span>松开上传到附件</span>
    </div>
  </div>
</template>

<style scoped>
.chat-composer {
  flex: none;
  /*
   * `position: relative` 是拖拽蒙层的定位锚：蒙层 `inset: 0` 要覆盖的是
   * 整个输入区（含附件条），不是里面那个盒子。
   */
  position: relative;
  /*
   * 左右内边距与消息区**完全同一个公式**（`--os-chat-band` 见 tokens.css）。
   *
   * 目标不是「输入框和气泡左右齐平」，而是**输入区和消息行属于同一列**：
   * 两边用同一个居中公式，于是 composer 的左沿 = 头像左沿、右沿 = 气泡右沿，
   * 气泡因为被头像推了一下多缩进 44px —— 这是结构必然，不是错位。
   *
   * 中途试过在左侧补 44px 让输入框和气泡严格齐平（宽屏实测 Δ左 0 / Δ右 0），
   * 但**窄屏必然退回**：带宽不够时 `max()` 取到 16px，那 44px 补不出来，
   * 结果宽屏齐平、窄屏差 44，同一套样式两种表现，比一开始就不齐更显错。
   * 所以不做特例，只对齐「列」。
   *
   * 最小 16 与消息区一致（不是原来输入区的 20）：两个 4px 的差会让窄屏下
   * 输入框比上面的气泡各宽 4px，边缘对齐不上。
   */
  padding: var(--os-space-3) max(var(--os-space-4), calc((100% - var(--os-chat-band)) / 2)) var(--os-space-4);
  border-top: 1px solid var(--el-border-color-lighter);
  background: var(--el-bg-color);
}

/* ---- 工具栏（读数行 / 工具+发送行） ---- */

/*
 * 行内所有控件统一 32px（`--os-button-height`）。
 *
 * <p>32 是 `theme.css` 里 `html .el-button--small:not(.is-link) { min-height: 32px }`
 * （0-2-1）定的全站按钮规格。压回 28 需要更高特异性，等于为局部推翻全局约定，
 * 所以在**同一行内**一律取 32：chip、条数分段、模型下拉、图标按钮、主按钮。
 * 不同行之间也统一 —— 上一版两行分别 28/32 造成「一矮一高」，这一版没有借口。
 *
 * <p>padding 用 `0 11px` + 固定 height：靠 padding 撑出来的高度会随字体行高
 * 浮动（12px 字体在不同缩放比下能差 1~2px），同一行里下拉是固定高度就对不上。
 */

/*
 * 垂直节奏（自上而下）：
 *
 *   盒顶 8 → 输入区(textarea 31 起，随内容长) → 读数行(24) → 8 → 工具行(32) → 盒底 12 → 边框
 *
 * <h3>为什么读数行与工具行之间是 8px</h3>
 * 8px 与「输入区 → 读数行」的 8px 相同。上一版曾刻意让两处不等（4/6）来
 * 「区分层级」，实测那是过度设计：两行都由同一族 12px 读数与 32px 控件构成，
 * 眼睛本来就把它们读成一组；给不等间距反而让人怀疑「是不是漏了什么」。
 * 现在只有**盒底 12 > 内部 8** 这一处不等 —— 它对应「内容结束」的收尾留白。
 */
.chat-composer__meta-row,
.chat-composer__tool-row {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
  min-width: 0;
}

/*
 * 读数行：flex 容器，但分布交给里面的读数组（组内两个读数排成一组，
 * 整组 `margin-left: auto` 推右）。
 *
 * <p>不写 `justify-content` —— 这行只有一个组，默认 flex-start 即可；
 * 推右是组自己的 margin，不是行的分布方式。写 space-between 会让
 * 「这里应该有两个组」的语义复活（2026-10-28 那版就是这么坏的）。
 */
.chat-composer__meta-row {
  margin-top: var(--os-space-2);
}

/*
 * 读数组：仪表盘 + 字数，一簇 24px 的状态信息，整体靠右收尾。
 *
 * <h3>为什么靠右（2026-10-29 第三版）</h3>
 * 前两版的坟都立在这行里：最初两个读数分居两端（中间空 528px，孤儿相）；
 * 上一版把字数挪进输入区右下角（更丑 + 高度跳动 + 仪表盘没动）。
 * 现在两个读数合成**一组**推到右端，与第二行右端的动作组上下对齐，
 * 右边缘成一条竖线 —— 「这一列的收尾信息」。仪表盘也就此从左端搬了过来。
 *
 * <p>常驻、不搞有条件渲染：这一行的高度必须恒定（用户反馈「高度还会变」），
 * 空草稿也显示「0 / 4000」是它本来的样子。
 *
 * <p>`flex: none` 防止窄屏被压缩：两个读数加起来约 190px，读数行在
 * 1024px 下也有 444px 可用，没有折叠的必要，也就不允许被压。
 */
.chat-composer__readings {
  display: inline-flex;
  align-items: center;
  gap: var(--os-space-2);
  flex: none;
  margin-left: auto;
  height: 24px;
}

.chat-composer__tool-row {
  margin-top: var(--os-space-2);
  /* 工具行在窄屏允许折行：知识库开启时多出「· 6 条」，极端窄屏下会挤 */
  flex-wrap: wrap;
}

/*
 * 字数统计：`0 / 4000`，读数组里的第二个元素。
 *
 * <p>字号、颜色与上下文读数完全一致（12px / placeholder 灰）——
 * 它们是同一族的读数，任何差异都会被读成「这两个数字重要性不同」，
 * 而实际上用户对两者的关心程度只取决于内容，不取决于画面。
 *
 * <p>常驻显示（不再「有内容才出现」）：读数行高度必须恒定，
 * 有条件渲染会让盒子在打第一个字时跳 17px（2026-10-29 被反馈「高度还会变」）。
 *
 * <p>它现在与仪表盘同组相邻，不再需要分隔竖线 —— 仪表盘自带进度条这个
 * 图形锚点，与纯数字已经分得开。
 */
.chat-composer__count {
  flex: none;
  color: var(--el-text-color-placeholder);
  font-size: var(--os-font-xs);
  font-variant-numeric: tabular-nums;
  /* 「0 / 4000」在窄屏被压成「0 /」+「4000」两行（实测 h=30、w=32） */
  white-space: nowrap;
}

/*
 * 逼近上限（≥90%）转琥珀：与上下文进度条的 warn 同一色系。
 *
 * <p>平时它是没人看的背景信息，贴着上限时「再打就要被 maxlength 硬截断」
 * 才必须被看见 —— 预警色只在需要预警时出现，不常驻。
 */
.chat-composer__count--warn {
  color: var(--el-color-warning);
}

/*
 * chip：胶囊按钮，32px。
 *
 * <p>字号从 12px 抬到 13px（`--os-font-sm`）配合「发送框高度太小」的反馈 ——
 * 整个输入区原先一起偏小（正文 14px、控件 12px、盒高 116px），
 * 只把盒子撑高而不动字，会得到「大盒子小字」。控件文字同步到 13px 后，
 * 12px 只留给真正的辅助读数（字数、上下文）。
 */
.chat-composer__chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex: none;
  height: var(--os-button-height);
  padding: 0 11px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 999px;
  background: var(--el-bg-color);
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-sm);
  cursor: pointer;
  transition:
    border-color var(--os-duration-fast) var(--os-ease),
    background var(--os-duration-fast) var(--os-ease),
    color var(--os-duration-fast) var(--os-ease);
}

.chat-composer__chip:hover {
  border-color: var(--el-color-primary-light-5);
  color: var(--el-color-primary);
}

/*
 * 知识库胶囊容器：行里**唯一**的 flex 项。
 *
 * <p>边框、圆角、底色、高度都长在这里 —— 内部的 button 和 el-select 一律
 * 只画文字，不画框。这样「开关 + 条数」在折行判断里永远是**一个**整体，
 * 条数不可能单独掉到下一行。
 *
 * <p>宽度随内容变（关 108 / 开 167），但因为它自身是一个项，多出来的 59px
 * 只影响这一项，不影响行里其他元素的位置关系。
 */
.chat-composer__chip-group {
  display: inline-flex;
  align-items: center;
  flex: none;
  height: var(--os-button-height);
  padding: 0 4px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 999px;
  background: var(--el-bg-color);
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-sm);
  transition:
    border-color var(--os-duration-fast) var(--os-ease),
    background var(--os-duration-fast) var(--os-ease),
    color var(--os-duration-fast) var(--os-ease);
}

.chat-composer__chip-group:hover {
  border-color: var(--el-color-primary-light-5);
  color: var(--el-color-primary);
}

/* 开启态必须有底色：只描边的话，在浅底上「开没开」基本看不出来 */
.chat-composer__chip-group--on {
  border-color: var(--el-color-primary-light-5);
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}

/*
 * 容器内的开关本体：不画框，只画图标 + 文字。
 *
 * <p>padding 左右各 6pa 与容器的 4px 合成 10px，与普通 chip 的「0 11px」对齐 ——
 * 关态时这个胶囊要和「附件」那个胶囊看起来完全一样。
 */
.chat-composer__chip-trigger {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex: none;
  height: 100%;
  padding: 0 6px;
  border: none;
  border-radius: 999px;
  background: transparent;
  color: inherit;
  font-size: inherit;
  font-family: inherit;
  cursor: pointer;
}

/* 点击区不额外画 focus 环：聚焦反馈由外层容器的 :focus-visible 承担 */
.chat-composer__chip-trigger:focus-visible {
  outline: none;
}

.chat-composer__chip-group:focus-within {
  border-color: var(--el-color-primary);
}

/* 分段之间的竖线：分隔「开关」与「参数」两半，比再加一个 gap 更省宽度 */
.chat-composer__chip-sep {
  width: 1px;
  height: 14px;
  flex: none;
  margin: 0 2px;
  background: currentColor;
  opacity: 0.25;
}

/*
 * 条数分段。
 *
 * <p>不再自己带 `margin-left: -2px`（那是当兄弟节点时的补偿），
 * 现在它在容器内部，位置由容器的 flex 排布决定。
 */
.chat-composer__topk {
  display: inline-flex;
  align-items: center;
  flex: none;
}

/*
 * 分段内的 el-select 必须**显式定宽**。
 *
 * <h3>不定宽会被压成 18px、文字整段消失</h3>
 * `.chat-composer__topk` 是 `inline-flex`，EP 的 `el-select` 在里面是一个
 * flex 项、`width` 仍是 `auto` → 按 flex 规范会被 shrink 到 min-content，
 * 而 `el-select__wrapper` 内部的 `position: absolute` 占位层不参与
 * min-content 计算，于是实测：
 *
 *   selW=18.3  wrapW=18.3  placeholderW=11  →  「6 条」一个字都看不见
 *
 * <p>定宽而不是 `auto`：选项是 2~20 条，宽度不同会让胶囊在切档时抖动。
 * 52px = 「20 条」(≈31) + 箭头(12) + 2px 间距 + 余量；所有档位共用同一宽度，
 * 换档时胶囊总宽恒定，折行点也就恒定。
 */
.chat-composer__topk :deep(.el-select) {
  width: 52px;
  flex: none;
}

/*
 * 分段内的条数下拉：透明、无边框、跟随 chip 文字色。
 *
 * <p>必须写 `:deep(.el-select__wrapper)`（平铺、不嵌套）：嵌套会让 Vue
 * 编译时丢掉宿主类名，特异性掉到 0-2-0，与 EP 打平后因加载顺序被静默覆盖。
 */
.chat-composer__topk :deep(.el-select__wrapper) {
  min-height: 26px;
  padding: 0;
  box-shadow: none;
  background: transparent;
  font-size: var(--os-font-sm);
  color: inherit;
}

/* 箭头收窄：它只提示「可点」，不需要 EP 默认那么大的视觉重量 */
.chat-composer__topk :deep(.el-select__caret) {
  margin-left: 2px;
  font-size: 12px;
  color: inherit;
  opacity: 0.7;
}

/* 分段内部不显示 EP 的聚焦描边：整个胶囊的 --on 已经表达了「在生效」 */
.chat-composer__topk :deep(.el-select__wrapper.is-focused) {
  box-shadow: none;
}

/*
 * 窄屏收起工具胶囊的文字，只留图标。
 *
 * <h3>为什么需要这一步（960px 差 21px）</h3>
 * 工具行需求宽度实测：
 *
 *   关：69(附件) + 8 + 106(知识库) + 8 + 145(动作组) = 336px
 *   开：69(附件) + 8 + 163(知识库含条数) + 8 + 145   = 393px
 *
 * 960px 视口（窄屏侧栏收起）下行宽只有 372px —— 开知识库差 **21px**，
 * 工具行折成两行、盒高从 133 涨到 173。
 *
 * <p>不靠压 padding 补这 21px：两个胶囊的 `0 11px` 与动作组是同一档视觉规格，
 * 单为某个断点改窄会得到「窄屏的按钮比宽屏瘦一圈」，而 21px 也不是靠
 * 微调能稳定覆盖的（换个字体、换台机器就变）。
 *
 * <p>改为**收起文字**：两个胶囊各保留图标 + 原生 title/aria-label。
 * 实测省下 52px（附件 69→39、知识库 163→111），开启状态下总需求降到 341px，
 * 落在 372px 里还有 31px 余量。这也是主流 Agent 在窄屏的通行做法。
 *
 * <p>阈值取 1080 而不是 960：372 只在「侧栏收起」时出现，侧栏展开时会再窄
 * 掉 200 多，1100 上下就已经贴边（实测 1024px 余量仅 4px）。
 * 提前一点收起，比压到折行临界再收更不容易出意外。
 */
@media (max-width: 1080px) {
  .chat-composer__chip-label {
    display: none;
  }

  /* 文字没了之后图标两侧的 gap 也没了作用，把 padding 收成对称的方框 */
  .chat-composer__chip {
    gap: 0;
    padding: 0 9px;
  }

  .chat-composer__chip-trigger {
    gap: 0;
    padding: 0 8px;
  }
}

/* footer 里的思考档位触发器：纯图标，颜色即档位 */
.chat-composer__thinking-trigger {
  color: var(--el-text-color-placeholder);
}

.chat-composer__thinking-trigger--standard {
  color: var(--el-color-primary);
}

.chat-composer__thinking-trigger--deep {
  color: var(--el-color-warning);
}

.chat-composer__thinking-trigger:hover {
  color: var(--el-color-primary);
}

/*
 * 下拉菜单是 EP teleport 到 body 的，**scoped 样式够不到它** ——
 * 必须用 :deep()，且选择器要能匹配到「带本组件 scope id 的菜单根元素」。
 * 改造前漏了 :deep，结果档名与说明裸奔成一行（"关闭不思考：最快、最省…"）。
 */
.chat-composer__thinking-menu :deep(.el-dropdown-menu__item) {
  padding: 7px 12px;
}

.chat-composer__thinking-option {
  display: flex;
  align-items: baseline;
  gap: var(--os-space-2);
  white-space: nowrap;
}

.chat-composer__thinking-option--on {
  color: var(--el-color-primary);
  font-weight: 500;
}

/* 思考档位的说明文字已并入菜单项的 title（hover 才显示），这里的样式随之删除 */

/* ---- 附件条 ---- */

.chat-composer__attachments {
  display: flex;
  flex-wrap: wrap;
  gap: var(--os-space-2);
  margin-bottom: var(--os-space-2);
}

.chat-composer__attachment {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 240px;
  padding: 4px 6px 4px 8px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--os-radius-md);
  background: var(--el-fill-color-lighter);
  font-size: var(--os-font-xs);
}

.chat-composer__attachment-thumb {
  width: 20px;
  height: 20px;
  border-radius: var(--os-radius-sm);
  flex: none;
}

.chat-composer__attachment-icon {
  color: var(--el-text-color-secondary);
}

.chat-composer__attachment-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--el-text-color-regular);
}

.chat-composer__attachment-size {
  flex: none;
  color: var(--el-text-color-placeholder);
}

/* 原生 file input 常驻隐藏但仍可被 click() 触发 */
.chat-composer__file-input {
  display: none;
}

/*
 * 垂直节奏（自上而下）：
 *
 *   盒顶 padding 8 → 输入框 → 8 → 参数行(32) → 8 → 工具行(32) → 盒底 padding 12 → 边框
 *
 * <h3>为什么盒内 padding 是 8 / 12 而不是上下各 8</h3>
 * 上下各 8 时，最后一行**贴底**：距底边 8px，而两行之间同样是 8px ——
 * 盒子内部唯一的「松间距」反而落在最需要收口的那一处，看起来像没留白
 * （视觉上被按钮的下边缘吃掉，实际感知比 8 更窄）。
 *
 * <p>更根本的原因是**行内控件自带视觉重心**：chip 与按钮是带边框/底色的实体，
 * 它们的下边缘在盒子里读起来像「内容结束」，而输入框上方的文字没有实体边界，
 * 读起来像「内容开始」。同样 8px，上方显得宽松、下方显得拥挤。
 * 底 12 是对这种不对称的补偿，不是随手放大。
 *
 * <p>8/12 也刚好保持「盒底留白(12) > 行间距(8)」的递减关系，层级仍然读得出来。
 */
.chat-composer__box {
  border: 1px solid var(--el-border-color-light);
  border-radius: var(--os-radius-xl);
  background: var(--el-bg-color);
  padding: var(--os-space-2) var(--os-space-3) var(--os-space-3);
  /*
   * 最轻一档阴影（气泡档）而不是平贴：输入盒落在同色卡片上时，
   * 只靠 1px 边框在浅色 UI 里接近「描边线稿」（tokens.css 里气泡的结论：
   * 面负责体积、线只负责收口）。阴影极轻，不与边框抢戏。
   */
  box-shadow: var(--os-shadow-bubble);
  transition:
    border-color var(--os-duration-fast) var(--os-ease),
    box-shadow var(--os-duration-fast) var(--os-ease),
    background var(--os-duration-fast) var(--os-ease);
}

.chat-composer__box:hover {
  border-color: var(--el-border-color);
}

/* 聚焦高亮落在外盒上：控件内部各自的 focus 描边会被收掉，避免出现两层框 */
.chat-composer__box:focus-within {
  border-color: var(--el-color-primary);
  box-shadow: 0 0 0 3px var(--el-color-primary-light-9);
}

/*
 * 拖拽悬浮：盒子整体换主色边框 + 浅主色底。
 *
 * <p>边框换色而不只是加蒙层：蒙层出现前的那一两百毫秒里，
 * 用户需要从盒子本身得到「接住了」的反馈，否则会以为没拖对地方。
 */
.chat-composer--dragging .chat-composer__box {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

/*
 * 拖拽蒙层：提示「松开上传到附件」。
 *
 * <h3>为什么 inset 要复刻根元素的 padding 公式、而不是 inset: 0</h3>
 * 放置区是整条底栏（监听挂在根元素上，附件条在盒子外面也要能接），
 * 但**视觉高亮必须落在盒子上** —— 用户感知里的「输入框」是那个盒子，
 * 不是它外面的居中留白。直接 inset: 0 时蒙层比盒子各边宽出约 94px
 * （1440 实测），盒子的边缘与蒙层的边缘对不齐，读起来像「蒙层浮在
 * 别的东西上面」。用同一个 max() 公式后两者严格重合，窄屏留白收到
 * 16px 时也一起收，不会出现两种表现。
 */
.chat-composer__drop-mask {
  position: absolute;
  /*
   * 上 12 / 下 16 与根元素 padding 一致；左右复刻居中公式。
   * 注意这里的 100% 是根元素宽度，与 `.chat-composer` 的 padding 同源。
   */
  inset: var(--os-space-3) max(var(--os-space-4), calc((100% - var(--os-chat-band)) / 2)) var(--os-space-4);
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--os-space-2);
  border-radius: var(--os-radius-xl);
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-size: var(--os-font-sm);
  /* 不抢指针事件：dragover/drop 必须继续打到根元素上（见模板注释） */
  pointer-events: none;
}

.chat-composer__textarea :deep(.el-textarea__inner) {
  padding: var(--os-space-1) 2px;
  border: none;
  background: transparent;
  box-shadow: none;
  /*
   * 输入框正文 14px 而不是 13px（阶段 23）：这是全应用打字最多的地方，
   * 13px 中文在 1x 屏上偏小；且 iOS Safari 对 <16px 的输入框会自动放大页面，
   * 14px 至少与正文一致，不会显得比消息内容更小。
   */
  font-size: var(--os-font-md);
  line-height: 1.65;
  /*
   * 单行起步，autosize 按内容往上长（上限 160px）。
   *
   * <h3>为什么从 2 行改成 1 行</h3>
   * 两行时实测：textarea 54px，而占位文字只占 31px（14px × 1.65 + 上下 padding），
   * **空态下有 23px 的纯空白行**（54 - 31 = 23，正好是一整行行高）。
   * 这块空白位于「占位文字」与「工具栏」之间，会让人觉得输入框松垮、
   * 内容都往下沉。多行输入本来也不依赖预留行数 —— autosize 会在输入时
   * 立刻把高度补上（实测：输入 2 行后 textarea 从 31px 长到 54px）。
   *
   * <p>31px = 23.1（行高）+ 4 + 4（padding）。不写死 46 那类「两行高度」的
   * 魔法数，改为让内容自己决定初始高度。
   */
  min-height: 31px;
}

/*
 * 行内按钮之间的间隙由父级 gap 提供，禁掉 EP 自带 margin-left 的叠加
 * （EP 默认 margin-left: 12px，与 gap: 8px 叠成 20px，多行叠起来呈锯齿）。
 *
 * <p>作用域限定在动作组内：语音/思考/发送三个按钮都在这里。
 */
.chat-composer__action-group :deep(.el-button + .el-button) {
  margin-left: 0;
}

/*
 * 图标按钮组：思考 / 语音 / 发送（顺序见模板注释）。
 *
 * <p>整体 `margin-left: auto` 推右 —— 它是工具行里唯一「靠右收尾」的实体。
 * 左侧的附件/知识库胶囊是**流式排列**的，宽度随内容变；靠右的只有这一组，
 * 所以「左宽右窄」的分布不会因为知识库开关而改变，右边界始终贴容器右内缘。
 *
 * <p>组内 4px（`--os-space-1`）—— 三个按钮是同一簇「能点的」，紧一点才读得出是一组。
 * 与左边的工具胶囊之间由父级 gap 8px 加这里的 margin-left 4px = 12px，
 * 那是「换了一类东西」的一档。
 *
 * <p>用 `span` 而不是再包一层 `div`：span 是 inline 的，在 flex 容器里
 * 会被自动块级化（display: inline-flex 显式指定），不会引入额外的块格式化上下文。
 */
.chat-composer__action-group {
  display: inline-flex;
  align-items: center;
  gap: var(--os-space-1);
  flex: none;
  margin-left: auto;
}
</style>
