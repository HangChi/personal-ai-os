<script setup lang="ts">
/**
 * 对话输入区（从 ChatView 拆出，2026-10-26；阶段 22 增加思考程度）。
 *
 * <p>包含：知识库增强开关（含条数）、思考程度、语音输入、输入框、发送/停止。
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
import ChatModelPicker from '@/components/chat/ChatModelPicker.vue'
import ChatVoiceInput from '@/components/chat/ChatVoiceInput.vue'
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
  /** 本会话选中的模型；null = 跟随生效配置 */
  sessionModel: string | null
  /** 可选模型（生效配置的对话模型清单） */
  chatModels: string[]
  /** 生效配置的默认模型名（供「跟随配置」提示） */
  defaultModel: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
  (e: 'update:topK', value: number | null): void
  (e: 'update:thinkingLevel', value: ThinkingLevel): void
  (e: 'update:sessionModel', value: string | null): void
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
        :rows="2"
        maxlength="4000"
        resize="none"
        :placeholder="knowledgeOn ? '输入消息，将基于知识库回答' : '输入消息'"
        @update:model-value="(value: string) => emit('update:modelValue', value)"
        @keydown="handleKeydown"
      />

      <!--
        ============ 第一行：输入增强 ============

        <h3>为什么全部收进边框内</h3>
        改造前这一行在**边框外面**、发送行在**边框里面** —— 同一类「发送前设置」
        被一个边框劈成两个视觉层级，看上去像是两组无关的东西浮在上方。
        收进来之后：边框 = 「这一条消息的全部设置」，内部按两行分组。

        <h3>为什么分两行而不是挤成一行</h3>
        八个控件挤一行有两个必然结果：① 1440px 下右侧空出 540px（实测），
        左侧密、右侧空，行被拉断；② 1024px 下必折行，且折行位置随机。
        按语义分两行后，每行各自「左侧控件 + 右侧状态」，视觉重量均衡。
      -->
      <div class="chat-composer__row chat-composer__row--tools">
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
          :aria-pressed="knowledgeOn"
          @click="emit('toggle-knowledge', !knowledgeOn)"
        >
          <el-icon :size="12"><Reading /></el-icon>
          知识库增强
        </button>

        <!--
          条数改成下拉而不是数字步进器：步进器为了选 20 要点十下，且在窄工具栏里
          两侧按钮把数字挤成一条缝。下拉一次点选，宽度也固定。

          这里**不再**用固定宽度的常驻占位。原来为了「开关知识库时工具栏不跳动」
          给了一个 78px 的空槽，实测知识库关闭时它仍然占地（w=78, h=0），
          使「知识库增强」与后面的元素之间凭空多出 94px 空洞。开关知识库时
          后面的元素跟着挪动是符合预期的因果；恒定空洞是永远看不懂的留白。
        -->
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

        <!--
          上下文仪表盘靠右：它是**只读状态**（这一发会带多少上下文），
          与左边的可点控件不同类。推到行尾既能利用右侧空白，又形成
          「左边操作 / 右边读数」的稳定结构 —— 与第二行完全一致。
        -->
        <ContextMeter
          v-if="contextUsage.budgetTokens > 0"
          class="chat-composer__meter-slot"
          :usage="contextUsage"
          @change-budget="(tokens: number) => emit('change-budget', tokens)"
        />
      </div>

      <!--
        ============ 第二行：生成与发送 ============

        <h3>模型为什么在最左</h3>
        这是 ChatGPT / Claude / Cursor 一致的惯例：模型名挨着发送按钮，
        回答的是「这条消息要谁来答」。它同时是这一行里唯一带文字的控件，
        放左侧自然形成视觉起点，右侧留给纯图标与主按钮。

        <h3>位置试错记录（三次实测）</h3>
        ① 卡片顶栏 → 与「重命名/导出」等会话操作混在一起，语义不同；
        ② 工具栏最右（margin-left:auto）→ chip 结束 x=953、下拉起于 x=1227，
           中间 274px 空洞，整行被拉成左重右轻两半；
        ③ 工具栏紧跟 chip → 1024px 下工具栏可用 462px、需求 592px，必折行。
      -->
      <div class="chat-composer__row chat-composer__row--actions">
        <ChatModelPicker
          :model-value="sessionModel"
          :models="chatModels"
          :default-model="defaultModel"
          :disabled="streaming"
          @update:model-value="(value: string | null) => emit('update:sessionModel', value)"
        />

        <!--
          快捷键提示：靠右且紧跟字数统计（都是「关于这段文字」的读数）。
          窄屏由 CSS 整块隐藏而不是截断成「Enter 发送 · Shi...」。
        -->
        <span class="chat-composer__hint">
          {{ enterSend ? 'Enter 发送 · Shift+Enter 换行' : 'Ctrl+Enter 发送 · Enter 换行' }}
        </span>

        <span class="chat-composer__count">{{ modelValue.length }} / 4000</span>

        <!--
          图标按钮组：语音、思考、发送。
          三者之间只隔 4px、与左侧读数组隔 12px —— 让「能点的」连成一簇，
          与「只是给人看的」文字读数在视觉上分开。此前全部 8px 等距，
          六个元素地位相同，眼睛既找不到起点也找不到终点。
        -->
        <span class="chat-composer__action-group">
          <!--
            语音输入紧邻发送按钮：它产出的是「输入框内容」，与发送是同一件事的
            两个环节，放在「附件/知识库」那行会让人以为它影响检索范围。
          -->
          <ChatVoiceInput
            :draft="modelValue"
            :disabled="streaming"
            @update:draft="(value: string) => emit('update:modelValue', value)"
          />

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

          <el-button v-if="streaming" round @click="emit('stop')">停止</el-button>
          <!-- 上传期间同时置灰：拦截逻辑在 handleSend 里，但按钮先给一个「现在不能点」的视觉信号 -->
          <el-button
            v-else
            type="primary"
            round
            :disabled="modelValue.trim().length === 0 || uploading"
            :loading="uploading"
            @click="handleSend"
          >
            发送
          </el-button>
        </span>
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

/* ---- 行（边框内部分两行：输入增强 / 生成发送） ---- */

/*
 * 两行各自内部统一高度。
 *
 * <h3>为什么是「各自」而不是「一致」</h3>
 * 改造前四种高度混用（实测）：chip 27px、条数下拉 24px、模型下拉 28px、
 * 图标按钮 32px。同一行里出现四种高度，眼睛会看出「参差」但说不出哪里不对。
 * 现在：第一行全是 28px（胶囊 + 条数下拉），第二行全是 32px（模型 + 图标 + 发送）。
 *
 * <p>两行**不强求跨行等高**：第二行压到 28px 会撞上 `theme.css` 里
 * `html .el-button--small:not(.is-link) { min-height: 32px }`（0-2-1）——
 * 那是全站有意统一的（改造前「刷新」按钮在用量页比别人矮一截）。
 * 为一个局部推翻全局约定，别处又会不一致。
 */

/*
 * 垂直节奏：输入框 → 4px → 第一行 → 6px → 第二行 → 8px(盒内 padding) → 边框。
 *
 * <h3>为什么两行之间是 6px 而不是 4px</h3>
 * 原先两处都用 4px，结果「文本→第一行」与「第一行→第二行」间隔相同，
 * 视线读不出「这两行是一组控件、而不是三块独立内容」。
 * 6px 介于两者之间：比「文本与控件」松一点，又明显紧于盒内 padding(8px)，
 * 于是两行自动成簇。**间距必须互不相等才能表达层级** —— 全都相等等于没分组。
 */
.chat-composer__row {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
  min-width: 0;
}

/* 第一行紧贴输入框：它是「输入框的附件」而不是独立段落 */
.chat-composer__row--tools {
  margin-top: var(--os-space-1);
}

/* 第二行与第一行之间 6px：略松于上一处，使两行成簇 */
.chat-composer__row--actions {
  margin-top: 6px;
}

/* 工具栏里的读数推到行尾：左操作 / 右读数，两行结构一致 */
.chat-composer__meter-slot {
  margin-left: auto;
  flex: none;
  /*
   * 父级是 `align-items: center`，子元素默认按内容高（12px）参与布局；
   * 而 ContextMeter 内部那层 `.context-meter` 带 `margin: -6px 0`（它原为卡片
   * 头部里贴边而写），放进 flex 行后会上下各溢出 6px（实测 slot 12px / inner 24px）。
   * 虽然 overflow 是 visible、点击仍有效，但行高会由这 12px 决定，
   * 使这一行的中心线与右侧 chip 差 6px。显式给满行高把它撑平。
   */
  height: var(--os-button-height-inline);
  display: flex;
  align-items: center;
}

/*
 * 下面这段 `.chat-composer__meter-*` 在 ContextMeter 拆成独立组件后就没人用了
 * （组件自己的 scoped 样式管内部结构），留着只会让后来的人以为这里有覆盖逻辑。
 */

/*
 * chip：与同行的下拉、图标按钮统一 28px。
 *
 * <p>padding 从 `4px 10px` 改为 `0 10px` + 固定 height：靠 padding 撑出来的高度
 * 会随字体行高浮动（12px 字体在不同缩放比下能差 1~2px），而同一行里
 * 下拉是固定高度，两者就对不上了。
 */
.chat-composer__chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  flex: none;
  height: var(--os-button-height-inline);
  padding: 0 10px;
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
  flex: none;
}

/*
 * 条数下拉的边框与 box-shadow 都收掉：它紧跟在「知识库增强」这个已开启的
 * chip 之后，语义上属于「知识库」的一个参数，两者视觉上应连成一体。
 * 保留边框会变成三个并列的方框，观众会以为它是独立开关。
 * 高度统一走 `--os-button-height-inline`（28px），与 chip 齐平。
 */
.chat-composer__topk :deep(.el-select__wrapper) {
  min-height: var(--os-button-height-inline);
  box-shadow: none;
  background: var(--el-fill-color-lighter);
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

/*
 * 操作行的读数组（提示 + 字数）靠右。
 *
 * <p>模型下拉在最左，读数组推到右侧：形成「左边选谁答 / 右边还能打多少」的
 * 双端结构。改造前是「提示 → 模型 → 字数 → 图标 → 发送」五段等距平铺，
 * 每个元素地位相同，眼睛找不到起点也找不到终点 —— 这正是「分不清谁和谁一组」
 * 的来源。
 */
.chat-composer__hint {
  margin-left: auto;
  color: var(--el-text-color-placeholder);
  font-size: var(--os-font-xs);
  /*
   * 窄屏直接隐藏，而不是截断成省略号。
   *
   * 实测 1024px 视口下这一行只剩 ~436px，装不下完整提示，截断后显示成
   * 「Enter 发送 · Shi...」—— 一句被腰斩的快捷键说明比没有更糟：用户会以为
   * 界面上还有别的内容没加载出来。提示本身在设置页里也有，隐藏不丢信息。
   */
  flex: 0 1 auto;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

@media (max-width: 1180px) {
  .chat-composer__hint {
    display: none;
  }

  /* 提示消失后由字数统计接管「推右」的职责，否则整组会贴到模型下拉边上 */
  .chat-composer__count {
    margin-left: auto;
  }
}

.chat-composer__count {
  color: var(--el-text-color-placeholder);
  font-size: var(--os-font-xs);
  font-variant-numeric: tabular-nums;
  /* 「0 / 4000」在窄屏被压成「0 /」+「4000」两行（实测 h=30、w=32） */
  flex: none;
  white-space: nowrap;
}

/*
 * 操作行统一到 32px（`--os-button-height`），与全站按钮一致。
 *
 * <h3>为什么不是 28px</h3>
 * 本来想让整行压到 28px 与第一行的 chip 齐平，实测做不到且不该做：
 * `theme.css` 有一条 `html .el-button--small:not(.is-link) { min-height: 32px }`
 * （特异性 0-2-1），是**全站有意统一的**——改造前「刷新」按钮在用量页比其他页
 * 矮一截，就是被这条治好的。在组件里用更高特异性压回 28px，等于为一个局部
 * 推翻一条全局约定，别处又会不一致。
 *
 * <p>所以改成让这一行**整体**取 32px：模型下拉也抬到 32（见 ChatModelPicker），
 * 与语音、思考、发送同高。
 */
.chat-composer__row--actions {
  gap: var(--os-space-2);
  min-width: 0;
}

/* 行内按钮之间的间隙由父级 gap 提供，禁掉 EP 自带 margin-left 的叠加
   （EP 默认 margin-left: 12px，与 gap: 8px 叠成 20px，多行叠起来呈锯齿） */
.chat-composer__row--actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

/*
 * 图标按钮组：语音 / 思考 / 发送。
 *
 * <p>组内 4px、组与左侧读数组之间 12px（由外层 gap 8 + 这里 margin-left 4 凑出）。
 * 目的是让「能点的」连成一簇，与「只是给人看的」文字读数分开 ——
 * 改造前六个元素全是 8px 等距，地位相同，眼睛找不到视觉起点与终点。
 *
 * <p>用 `span` 而不是再包一层 `div`：span 是 inline 的，在 flex 容器里
 * 会被自动块级化（display: inline-flex 显式指定），不会引入额外的块格式化上下文。
 */
.chat-composer__action-group {
  display: inline-flex;
  align-items: center;
  gap: var(--os-space-1);
  flex: none;
  margin-left: var(--os-space-1);
}
</style>
