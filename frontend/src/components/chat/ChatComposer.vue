<script setup lang="ts">
/**
 * 对话输入区（从 ChatView 拆出，2026-10-26；阶段 22 增加思考程度）。
 *
 * <p>包含：知识库增强开关（含条数）、思考程度、输入框、发送/停止。
 * 键盘行为在这里实现而不是父组件，因为它完全由「输入框聚焦时的按键」触发：
 *
 * <ul>
 *   <li>{@code enterSend=true}（默认）：Enter 发送，Shift+Enter 换行</li>
 *   <li>{@code enterSend=false}：Enter 换行，Ctrl/Cmd+Enter 发送</li>
 *   <li>输入法选词期间的 Enter 一律不发送（否则打不了中文）</li>
 * </ul>
 */
import { Close, Document, MagicStick, Paperclip, Reading } from '@element-plus/icons-vue'
import { computed, nextTick, onMounted, ref, watch } from 'vue'

import { uploadAttachment } from '@/api/chat'
import { ElMessage } from 'element-plus'
import type { ContextUsage } from '@/api/chat'
import ContextMeter from '@/components/chat/ContextMeter.vue'
import { THINKING_HINTS, THINKING_LABELS, type ThinkingLevel } from '@/lib/settings'
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
  /** 上下文用量（token 口径）；budgetTokens 为 0 时不显示仪表盘 */
  contextUsage: ContextUsage
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
  (e: 'update:topK', value: number | null): void
  (e: 'update:thinkingLevel', value: ThinkingLevel): void
  (e: 'toggle-knowledge', next: boolean): void
  (e: 'change-budget', tokens: number): void
  (e: 'send', attachments: ChatAttachment[]): void
  (e: 'stop'): void
}>()

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
onMounted(autosize)
</script>

<template>
  <div class="chat-composer">
    <div class="chat-composer__toolbar">
      <!--
        上下文仪表盘放回发送框（2026-10-27）：它回答的是「这一发会带多少上下文」，
        和附件/知识库同属「发送前的状态」，放在一起才看得懂。
      -->
      <ContextMeter
        v-if="contextUsage.budgetTokens > 0"
        :usage="contextUsage"
        @change-budget="(tokens: number) => emit('change-budget', tokens)"
      />

      <!--
        附件按钮**没有**选中态：它是个瞬时动作（打开文件选择器），不是开关。
        曾经误把知识库的 --on 类绑定复制到这里，导致一开知识库附件也变蓝。
      -->
      <button type="button" class="chat-composer__chip" title="添加附件" @click="fileInput?.click()">
        <el-icon :size="12"><Paperclip /></el-icon>
        附件
        <span v-if="uploading">…</span>
      </button>

      <button
        type="button"
        class="chat-composer__chip"
        :class="{ 'chat-composer__chip--on': knowledgeOn }"
        :title="knowledgeOn ? '点击关闭知识库增强' : '开启后每条消息先检索知识库再回答'"
        @click="emit('toggle-knowledge', !knowledgeOn)"
      >
        <el-icon :size="12"><Reading /></el-icon>
        知识库增强
      </button>

      <!--
        条数改成下拉而不是数字步进器：步进器为了选 20 要点十下，且在窄工具栏里
        两侧按钮把数字挤成一条缝。下拉一次点选，宽度也固定。
      -->
      <!--
        槽位宽度固定：知识库一开，条数下拉凭空出现会把左边的「附件」等图标推走。
        常驻占位、只切换可见性，工具栏几何形态就始终不变。
      -->
      <span class="chat-composer__topk-slot">
        <el-select
          v-if="knowledgeOn"
          class="chat-composer__topk"
          :model-value="topK"
          size="small"
          title="检索条数"
          @change="(value: number) => emit('update:topK', value)"
        >
          <el-option v-for="option in TOP_K_OPTIONS" :key="option" :label="`${option} 条`" :value="option" />
        </el-select>
      </span>
    </div>

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
        <el-button link size="small" title="移除" @click="removeAttachment(item.id)">
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
        :rows="2"
        maxlength="4000"
        resize="none"
        :placeholder="knowledgeOn ? '输入消息，将基于知识库回答' : '输入消息'"
        @update:model-value="(value: string) => emit('update:modelValue', value)"
        @keydown="handleKeydown"
      />
      <!--
        操作行进框内：改造前是「裸 textarea + 下方一排控件」，三块各占一行、
        互相没有归属感。合成一个带边界的输入框后，聚焦高亮、字数、按钮都收在里面，
        与主流聊天工具的结构一致。
      -->
      <div class="chat-composer__footer">
        <span class="chat-composer__hint">
          {{ enterSend ? 'Enter 发送 · Shift+Enter 换行' : 'Ctrl+Enter 发送 · Enter 换行' }}
        </span>
        <span class="chat-composer__count">{{ modelValue.length }} / 4000</span>

        <!--
          思考程度从工具栏搬到 footer，并改成下拉触发。
          原来它在工具栏占一整段（标签 + 三选一 + 一长串说明），把「知识库增强」
          挤到角落，且分段按钮在窄屏会折行。搬到 footer 后：工具栏只留知识库，
          这里一个按钮显示当前档位，点开才列出三档与代价 —— 与 Codex 一类
          agent 的「模型/推理设置放在发送区附近」一致。
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

        <el-button v-if="streaming" round @click="emit('stop')">停止</el-button>
        <el-button v-else type="primary" round :disabled="modelValue.trim().length === 0" @click="handleSend">
          发送
        </el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.chat-composer {
  flex: none;
  padding: var(--os-space-3) var(--os-space-5) var(--os-space-4);
  border-top: 1px solid var(--el-border-color-lighter);
  background: var(--el-bg-color);
}

/* ---- 工具行 ---- */

.chat-composer__toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--os-space-2);
  margin-bottom: var(--os-space-2);
}

.chat-composer__meter {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-left: auto;
}

.chat-composer__meter-label {
  color: var(--el-text-color-placeholder);
  font-size: var(--os-font-xs);
}

/* 48px 细条：够看清比例，又不至于像个按钮 */
.chat-composer__meter-track {
  display: inline-block;
  width: 48px;
  height: 4px;
  border-radius: 999px;
  background: var(--el-fill-color);
  overflow: hidden;
}

.chat-composer__meter-fill {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: var(--el-color-primary);
  transition: width var(--os-duration-normal) var(--os-ease);
}

.chat-composer__meter-fill--warn {
  background: var(--el-color-warning);
}

.chat-composer__meter-fill--over {
  background: var(--el-color-danger);
}

.chat-composer__meter-value {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
  font-variant-numeric: tabular-nums;
}

.chat-composer__chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 10px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 999px;
  background: var(--el-bg-color);
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
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

/* 开启态必须有底色：只描边的话，在浅底上「开没开」基本看不出来 */
.chat-composer__chip--on {
  border-color: var(--el-color-primary-light-5);
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}

.chat-composer__topk {
  width: 78px;
}

.chat-composer__topk :deep(.el-select__wrapper) {
  box-shadow: none;
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

/* topK 槽位：常驻占位，宽度与下拉一致 */
.chat-composer__topk-slot {
  flex: none;
  display: inline-block;
  width: 78px;
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

.chat-composer__thinking-desc {
  color: var(--el-text-color-placeholder);
  font-size: var(--os-font-xs);
}

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

/* ---- 输入框（带边界的外盒 + 内部无框文本域） ---- */

.chat-composer__box {
  border: 1px solid var(--el-border-color-light);
  border-radius: var(--os-radius-xl);
  background: var(--el-bg-color);
  padding: var(--os-space-2) var(--os-space-3);
  transition:
    border-color var(--os-duration-fast) var(--os-ease),
    box-shadow var(--os-duration-fast) var(--os-ease);
}

.chat-composer__box:hover {
  border-color: var(--el-border-color);
}

/* 聚焦高亮落在外盒上：控件内部各自的 focus 描边会被收掉，避免出现两层框 */
.chat-composer__box:focus-within {
  border-color: var(--el-color-primary);
  box-shadow: 0 0 0 3px var(--el-color-primary-light-9);
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
  /* 两行文本的高度；autosize 会在此基础上按内容上调 */
  min-height: 46px;
}

.chat-composer__footer {
  display: flex;
  align-items: center;
  gap: var(--os-space-3);
  margin-top: var(--os-space-1);
}

.chat-composer__footer .chat-composer__hint {
  margin-right: auto;
  color: var(--el-text-color-placeholder);
  font-size: var(--os-font-xs);
}

.chat-composer__count {
  color: var(--el-text-color-placeholder);
  font-size: var(--os-font-xs);
  font-variant-numeric: tabular-nums;
}
</style>
