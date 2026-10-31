<script setup lang="ts">
/** 对话输入区：输入与工具栏在框内，快捷键和用量读数在框下。 */
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

/** 开启知识库时提示回答将使用检索内容。 */
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
  <div
    class="chat-composer"
    :class="{ 'chat-composer--dragging': dragging }"
    @dragenter="onDragEnter"
    @dragover="onDragOver"
    @dragleave="onDragLeave"
    @drop="onDrop"
  >
    <div ref="root" class="chat-composer__box">
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
        aria-label="消息内容"
        maxlength="4000"
        resize="none"
        :placeholder="inputPlaceholder"
        @update:model-value="(value: string) => emit('update:modelValue', value)"
        @keydown="handleKeydown"
        @paste="onPaste"
      />
      <div class="chat-composer__tool-row">
        <div class="chat-composer__source-group">
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
          <span class="chat-composer__chip-group" :class="{ 'chat-composer__chip-group--on': knowledgeOn }">
            <button
              type="button"
              class="chat-composer__chip-trigger"
              :title="knowledgeOn ? '点击关闭知识库增强' : '开启后每条消息先检索知识库再回答'"
              :aria-pressed="knowledgeOn"
              aria-label="知识库增强"
              @click="emit('toggle-knowledge', !knowledgeOn)"
            >
              <el-icon :size="14"><Reading /></el-icon>
              <span class="chat-composer__chip-label">知识库增强</span>
            </button>
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
        </div>
        <span class="chat-composer__action-group">
          <ChatModelPicker
            :model-value="sessionModel"
            :models="chatModels"
            :default-model="defaultModel"
            :disabled="streaming"
            @update:model-value="(value: string | null) => emit('update:sessionModel', value)"
          />
          <el-dropdown
            trigger="click"
            placement="top-end"
            @command="(value: ThinkingLevel) => emit('update:thinkingLevel', value)"
          >
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
                  <span class="chat-composer__thinking-option">
                    <span :class="{ 'chat-composer__thinking-option--on': level === thinkingLevel }">
                      {{ THINKING_LABELS[level] }}
                    </span>
                  </span>
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <ChatVoiceInput
            :draft="modelValue"
            :disabled="streaming"
            @update:draft="(value: string) => emit('update:modelValue', value)"
          />
          <el-button v-if="streaming" class="chat-composer__submit" type="danger" round @click="emit('stop')">
            <el-icon :size="12"><VideoPause /></el-icon>
            停止
          </el-button>
          <el-button
            v-else
            type="primary"
            class="chat-composer__submit"
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
    <div class="chat-composer__meta-row">
      <span class="chat-composer__shortcut">{{
        enterSend ? 'Enter 发送 · Shift + Enter 换行' : 'Ctrl / ⌘ + Enter 发送'
      }}</span>
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

    <div v-if="dragging" class="chat-composer__drop-mask" aria-hidden="true">
      <el-icon :size="16"><UploadFilled /></el-icon>
      <span>松开上传到附件</span>
    </div>
  </div>
</template>

<style scoped>
.chat-composer {
  flex: none;
  position: relative;
  container-type: inline-size;
  padding: var(--os-space-3) max(var(--os-space-4), calc((100% - var(--os-chat-band)) / 2)) var(--os-space-3);
  background: var(--el-bg-color);
}

.chat-composer__meta-row,
.chat-composer__tool-row {
  display: flex;
  align-items: center;
  min-width: 0;
  gap: var(--os-space-2);
}

.chat-composer__meta-row {
  min-height: var(--os-space-6);
  margin-top: var(--os-space-1);
  padding: 0 var(--os-space-1);
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}

.chat-composer__readings {
  display: inline-flex;
  align-items: center;
  gap: var(--os-space-3);
  flex: none;
  margin-left: auto;
  height: var(--os-space-6);
}

.chat-composer__tool-row {
  margin-top: var(--os-space-2);
  flex-wrap: wrap;
}

.chat-composer__count {
  flex: none;
  color: var(--el-text-color-placeholder);
  font-size: var(--os-font-xs);
  font-variant-numeric: tabular-nums;

  white-space: nowrap;
}

.chat-composer__count--warn {
  color: var(--el-color-warning);
}

.chat-composer__chip {
  display: inline-flex;
  align-items: center;
  gap: var(--os-space-1);
  flex: none;
  height: var(--os-button-height);
  padding: 0 var(--os-space-2);
  border: 1px solid transparent;
  border-radius: var(--os-radius-md);
  background: transparent;
  color: var(--el-text-color-secondary);
  font: inherit;
  font-size: var(--os-font-sm);
  cursor: pointer;
  transition:
    background var(--os-duration-fast) var(--os-ease),
    color var(--os-duration-fast) var(--os-ease);
}

.chat-composer__chip:hover {
  background: var(--el-fill-color-light);
  color: var(--el-text-color-primary);
}

.chat-composer__chip-group {
  display: inline-flex;
  align-items: center;
  flex: none;
  height: var(--os-button-height);
  box-sizing: border-box;
  padding: 0 var(--os-space-1);
  border: 1px solid transparent;
  border-radius: var(--os-radius-md);
  background: transparent;
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-sm);
  transition:
    background var(--os-duration-fast) var(--os-ease),
    color var(--os-duration-fast) var(--os-ease);
}

.chat-composer__chip-group:hover {
  background: var(--el-fill-color-light);
  color: var(--el-text-color-primary);
}

.chat-composer__chip-group--on {
  border-color: transparent;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}

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

.chat-composer__chip-trigger:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 2px;
}

.chat-composer__chip-group:focus-within {
  border-color: var(--el-color-primary);
}

.chat-composer__chip-sep {
  width: 1px;
  height: 14px;
  flex: none;
  margin: 0 2px;
  background: currentColor;
  opacity: 0.25;
}

.chat-composer__topk {
  display: inline-flex;
  align-items: center;
  flex: none;
}

.chat-composer__topk :deep(.el-select) {
  width: 52px;
  flex: none;
}

.chat-composer__topk :deep(.el-select__wrapper) {
  min-height: 26px;
  padding: 0;
  box-shadow: none;
  background: transparent;
  font-size: var(--os-font-sm);
  color: inherit;
}

.chat-composer__topk :deep(.el-select__caret) {
  margin-left: 2px;
  font-size: 12px;
  color: inherit;
  opacity: 0.7;
}

.chat-composer__topk :deep(.el-select__wrapper.is-focused) {
  box-shadow: none;
}

.chat-composer__thinking-trigger {
  color: var(--el-text-color-placeholder);
}

.chat-composer__thinking-trigger--standard {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.chat-composer__thinking-trigger--deep {
  color: var(--el-color-warning);
  background: var(--el-color-warning-light-9);
}

.chat-composer__thinking-trigger:hover {
  color: var(--el-color-primary);
}

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

.chat-composer__file-input {
  display: none;
}

.chat-composer__box {
  border: 1px solid var(--el-border-color);
  border-radius: var(--os-radius-xl);
  background: var(--el-bg-color);
  padding: var(--os-space-3);
  box-shadow: var(--os-shadow-bubble);
  transition:
    border-color var(--os-duration-fast) var(--os-ease),
    box-shadow var(--os-duration-fast) var(--os-ease);
}

.chat-composer__box:hover {
  border-color: var(--el-border-color);
}

.chat-composer__box:focus-within {
  border-color: var(--el-color-primary-light-5);
  box-shadow: 0 0 0 3px var(--el-color-primary-light-9);
}

.chat-composer--dragging .chat-composer__box {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.chat-composer__drop-mask {
  position: absolute;

  inset: var(--os-space-3) max(var(--os-space-4), calc((100% - var(--os-chat-band)) / 2))
    calc(var(--os-space-3) + var(--os-space-6) + var(--os-space-1));
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--os-space-2);
  border-radius: var(--os-radius-xl);
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-size: var(--os-font-sm);

  pointer-events: none;
}

.chat-composer__textarea :deep(.el-textarea__inner) {
  padding: var(--os-space-1);
  border: none;
  background: transparent;
  box-shadow: none;
  font-size: var(--os-font-md);
  line-height: 1.65;
  min-height: 52px;
}

.chat-composer__action-group :deep(.el-button + .el-button) {
  margin-left: 0;
}

.chat-composer__action-group {
  display: inline-flex;
  align-items: center;
  gap: var(--os-space-1);
  flex: 0 1 auto;
  min-width: 0;
  max-width: 100%;
  margin-left: auto;
}

.chat-composer__source-group {
  display: inline-flex;
  align-items: center;
  flex: none;
  gap: var(--os-space-1);
}

.chat-composer__action-group :deep(.model-picker-dropdown) {
  flex: 0 1 auto;
  max-width: 200px;
}
.chat-composer__action-group > :deep(.el-button),
.chat-composer__action-group > :deep(.el-dropdown:not(.model-picker-dropdown)) {
  flex: none;
}
.chat-composer__submit {
  gap: var(--os-space-1);
}
.chat-composer__shortcut {
  white-space: nowrap;
}
.chat-composer__chip-group--on:hover {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}
/* Keep model and thinking adjacent; wrap them together when sidebars reduce the column. */
@container (max-width: 560px) {
  .chat-composer__action-group {
    flex: 1 1 100%;
    justify-content: flex-end;
  }
  .chat-composer__shortcut {
    display: none;
  }
}
@media (max-width: 600px) {
  .chat-composer__textarea :deep(.el-textarea__inner) {
    font-size: var(--os-font-lg);
  }
}
@media (prefers-reduced-motion: reduce) {
  .chat-composer__box,
  .chat-composer__chip,
  .chat-composer__chip-group {
    transition: none;
  }
}
</style>
