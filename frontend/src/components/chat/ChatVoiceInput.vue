<script setup lang="ts">
/**
 * 语音输入按钮（麦克风）。
 *
 * <h2>两条路，优先走后端</h2>
 * 按「设置页有没有配语音模型」二选一，而不是固定用某一条：
 *
 * <ol>
 *   <li><b>后端转写</b>：{@code MediaRecorder} 录音 → 上传到
 *       {@code /api/v1/chat/transcribe} → 用户自己配的 ASR 模型转文字。
 *       音频只到自己配的端点。</li>
 *   <li><b>浏览器识别</b>：Web Speech API。零配置、有实时中间结果，
 *       但在 Chrome 上音频由 Google 的云端转写。</li>
 * </ol>
 *
 * <p>改造前只有第二条路，而设置页那个「语音模型」分类只用于把语音模型从
 * 对话下拉里剔除 —— 用户配了语音模型，没有任何代码会用它。这是本次补上的。
 *
 * <p>有意思的是接上后端之后 Firefox 反而**从不能用变成能用**：
 * {@code SpeechRecognition} 至今没有实现，而 {@code MediaRecorder} 早就有了。
 *
 * <h2>为什么是「点一下开始、再点一下停」而不是按住说话</h2>
 * 鼠标按住期间无法聚焦输入框、也无法按发送键；移动端长按还会触发系统菜单。
 * 点两下的交互在桌面与移动端都成立。
 *
 * <h2>中间结果为什么只在浏览器模式下有</h2>
 * 后端转写是「录完再传」，天然没有逐词结果 —— 这是它换来隐私与质量的代价。
 * 此时按钮旁显示已录时长，让用户知道它确实在录。
 */
import { Microphone, VideoPause } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

import { fetchTranscriptionStatus, transcribeAudio } from '@/api/chat'
import {
  appendTranscript,
  formatDuration,
  getSpeechRecognitionCtor,
  mimeToExtension,
  pickRecorderMime,
  pickSpeechLang,
  speechErrorMessage,
  transcribeErrorMessage,
  type SpeechRecognitionLike,
} from '@/lib/voice-input'

const props = defineProps<{
  /** 当前草稿（识别结果要追加到它后面） */
  draft: string
  /** 生成中：禁止开始识别 */
  disabled: boolean
}>()

const emit = defineEmits<{
  /** 草稿被更新（整串覆盖，由父组件写回 v-model） */
  (e: 'update:draft', value: string): void
}>()

// ------------------------------------------------------------------ 能力检测

/**
 * 构造函数检测放在模块级常量而不是 computed：可用性在整个会话里不会变，
 * 每次渲染都去读 window 是没必要的开销。
 */
const SpeechCtor = typeof window === 'undefined' ? null : getSpeechRecognitionCtor(window)

/** 录音能力：`MediaRecorder` + `getUserMedia` 都要有 */
const recorderSupported =
  typeof window !== 'undefined' &&
  typeof window.MediaRecorder === 'function' &&
  typeof navigator !== 'undefined' &&
  typeof navigator.mediaDevices?.getUserMedia === 'function'

/** 本机支持的录音格式；空串表示一个都不支持（有 MediaRecorder 却无可用编码） */
const recorderMime = recorderSupported ? pickRecorderMime(MediaRecorder.isTypeSupported) : ''

/**
 * 走后端还是走浏览器。
 *
 * <p>{@code unknown} 是初始态：状态还在请求中，此时**按钮不渲染**。
 * 直接渲染一个按钮会让用户点下去、然后再被改道 —— 那一下点击是白费的。
 */
type Mode = 'unknown' | 'backend' | 'browser' | 'none'
const mode = ref<Mode>('unknown')

/** 后端不可用的原因（仅在需要解释「为什么没走自己的模型」时用） */
const backendReason = ref<string | null>(null)

const listening = ref(false)
/** 浏览器模式的未确认片段，只用于预览 */
const interim = ref('')
/** 后端模式：已录时长（毫秒），用于「正在录 3 秒」的提示 */
const recordedMs = ref(0)
/** 后端模式：正在上传转写 */
const transcribing = ref(false)
/** 是否已经因为出错提示过，避免连续弹窗 */
const failed = ref(false)

let recognition: SpeechRecognitionLike | null = null
let recorder: MediaRecorder | null = null
let mediaStream: MediaStream | null = null
let timerId: number | null = null

/**
 * 组件眼中的「最新草稿」。
 *
 * <p>为什么不直接用 `props.draft`：连续两次 `onresult` 之间，父组件的 props
 * 更新要等一拍，第二次事件读到的还是旧值 —— 会把上一段 final 丢掉。
 */
let current = ''

/**
 * 上一次由本组件 emit 出去的值。
 *
 * <p>用来区分「props 变化是 emit 的回环」还是「用户手打了字」。
 * 不区分的话：用户一边说一边改文字时，要么丢掉手打内容（用开始时的快照当基线），
 * 要么把上一段识别结果重复一遍（把回环当成用户输入再 append 一次）。
 */
let lastEmitted: string | null = null

watch(
  () => props.draft,
  (value) => {
    if (lastEmitted !== null && value === lastEmitted) {
      // 自己 emit 回来的，不回灌
      lastEmitted = null
      return
    }
    // 外部修改（用户手打、或父组件清空）：同步基线，别把用户输入冲掉
    current = value ?? ''
  },
)

/**
 * 进组件时问一次后端「有没有配语音模型」。
 *
 * <p>只在挂载时问：设置页与聊天页是不同的路由，从设置页配完回聊天页
 * 会重新挂载本组件。做成每次点击都问的代价是每次说话多一次往返，
 * 而那一次往返的结果在 99% 的情况下与挂载时一样。
 *
 * <p>请求失败时按「后端不可用」处理并继续用浏览器识别：这次失败可能只是
 * 后端重启中，而浏览器那条路不依赖后端 —— 因为一个可选功能的探测失败
 * 就让整个语音输入不可用，是过度耦合。
 */
onMounted(async () => {
  if (recorderSupported && recorderMime) {
    try {
      const status = await fetchTranscriptionStatus()
      if (status.available) {
        mode.value = 'backend'
        return
      }
      backendReason.value = status.reason
    } catch {
      // 忽略：下面统一按浏览器模式处理
    }
  }
  mode.value = SpeechCtor ? 'browser' : 'none'
})

/** 按钮是否渲染。两条路都不可用时整块不渲染（见模板注释） */
const supported = computed(() => mode.value === 'backend' || mode.value === 'browser')

const tooltip = computed(() => {
  if (mode.value === 'unknown') {
    return '正在检查语音输入能力…'
  }
  if (!supported.value) {
    return backendReason.value
      ? `语音输入不可用：${backendReason.value}；当前浏览器也不支持语音识别`
      : '当前浏览器不支持语音输入'
  }
  if (props.disabled) {
    return '正在生成，稍后再用语音输入'
  }
  if (transcribing.value) {
    return '正在转写…'
  }
  if (listening.value) {
    return mode.value === 'backend' ? '停止录音并转写' : '停止语音输入'
  }
  return mode.value === 'backend' ? '语音输入（录音后由配置的语音模型转写）' : '语音输入（点一下开始说）'
})

/**
 * 预览文本：让用户看到「正在识别/录制什么」，而不是对着一个闪烁的图标猜。
 *
 * <p>浏览器模式显示未确认的识别片段；后端模式显示已录时长 ——
 * 后者没有逐词结果，时长是唯一能证明「它真的在录」的信号。
 */
const preview = computed(() => {
  if (transcribing.value) {
    return '转写中…'
  }
  if (mode.value === 'backend' && listening.value) {
    return `录音 ${formatDuration(recordedMs.value)}`
  }
  if (mode.value === 'browser') {
    return interim.value.trim()
  }
  return ''
})

/** 把最终文本写回草稿，并处理基线 */
function commit(text: string): void {
  current = appendTranscript(current, text)
  lastEmitted = current
  emit('update:draft', current)
}

// ------------------------------------------------------------------ 后端转写

function clearTimer(): void {
  if (timerId !== null) {
    window.clearInterval(timerId)
    timerId = null
  }
}

/** 释放麦克风。不释放会让标签页上一直挂着录音指示器 */
function releaseStream(): void {
  if (mediaStream) {
    for (const track of mediaStream.getTracks()) {
      track.stop()
    }
    mediaStream = null
  }
}

async function startRecording(): Promise<void> {
  failed.value = false
  recordedMs.value = 0
  let stream: MediaStream
  try {
    stream = await navigator.mediaDevices.getUserMedia({ audio: true })
  } catch (cause) {
    /*
     * getUserMedia 的失败分三类，处理方式完全不同：
     * 拒绝授权要去改浏览器设置、找不到设备要插麦克风、
     * 其它（如非 HTTPS 下的 NotAllowedError）基本无解。
     * 统一说「录音失败」会让用户反复点。
     */
    const name = cause instanceof Error ? cause.name : ''
    if (name === 'NotAllowedError' || name === 'SecurityError') {
      ElMessage.warning('麦克风权限被拒绝，请在浏览器地址栏的权限设置里允许后重试')
    } else if (name === 'NotFoundError' || name === 'DevicesNotFoundError') {
      ElMessage.warning('找不到麦克风，请检查设备是否已连接')
    } else {
      ElMessage.warning('无法开始录音，请检查麦克风与浏览器权限')
    }
    return
  }

  mediaStream = stream
  const chunks: Blob[] = []
  try {
    recorder = new MediaRecorder(stream, recorderMime ? { mimeType: recorderMime } : undefined)
  } catch {
    // 有 MediaRecorder 但构造失败（某些打包环境/权限策略）—— 退回浏览器识别
    releaseStream()
    recorder = null
    mode.value = SpeechCtor ? 'browser' : 'none'
    ElMessage.warning('当前浏览器无法录音，已切换为浏览器自带识别')
    return
  }

  recorder.ondataavailable = (event) => {
    if (event.data && event.data.size > 0) {
      chunks.push(event.data)
    }
  }
  recorder.onstop = () => {
    clearTimer()
    releaseStream()
    const type = recorderMime || 'audio/webm'
    const blob = new Blob(chunks, { type })
    recorder = null
    void upload(blob, type)
  }

  /*
   * 每 250ms 吐一块数据而不是等 stop 时一次性给：
   * 长时间录音全留在内存里，某个时刻 OOM 会一声不响地丢掉整段。
   */
  recorder.start(250)
  listening.value = true
  const startedAt = Date.now()
  timerId = window.setInterval(() => {
    recordedMs.value = Date.now() - startedAt
    // 8MB 上限对应 opus 约 40 分钟；这里卡 3 分钟，超了自动停并转写。
    // 用户按了没反应时至少有个上限，不会一直录下去
    if (recordedMs.value >= MAX_RECORD_MS) {
      stopRecording()
      ElMessage.info('录音已到 3 分钟上限，自动结束并转写')
    }
  }, 200)
}

/** 录音上限：3 分钟。够说完一段话，又远小于 8MB 的体积上限 */
const MAX_RECORD_MS = 3 * 60 * 1000

function stopRecording(): void {
  if (!recorder) {
    return
  }
  listening.value = false
  try {
    // requestData 后再 stop：确保最后不足 250ms 的那一小块也被收进来
    recorder.requestData()
    recorder.stop()
  } catch {
    // 已经停了，忽略
  }
}

async function upload(blob: Blob, mime: string): Promise<void> {
  if (blob.size === 0) {
    ElMessage.warning('没有录到声音，请靠近麦克风再说一次')
    return
  }
  transcribing.value = true
  try {
    const file = new File([blob], `voice.${mimeToExtension(mime)}`, { type: mime })
    const result = await transcribeAudio(file)
    if (result.unavailable) {
      /*
       * 挂载时后端说可用，上传时却说没有 —— 只可能是这段时间里用户切了配置。
       * 此时**已录的音频不能浪费**是次要的，让用户知道去哪儿配更重要：
       * 切成浏览器模式，下一次点按钮就能正常用。
       */
      mode.value = SpeechCtor ? 'browser' : 'none'
      backendReason.value = result.error
      ElMessage.warning(transcribeErrorMessage(true, result.error))
      return
    }
    if (!result.ok) {
      if (!failed.value) {
        failed.value = true
        ElMessage.warning(transcribeErrorMessage(false, result.error))
      }
      return
    }
    if (result.text.trim().length === 0) {
      // 不是故障：用户可能只是没说话就按了停止
      ElMessage.info('没听清，请再说一次')
      return
    }
    commit(result.text)
  } catch (cause) {
    const message = cause instanceof Error ? cause.message : ''
    if (!failed.value) {
      failed.value = true
      ElMessage.warning(transcribeErrorMessage(false, message))
    }
  } finally {
    transcribing.value = false
    recordedMs.value = 0
  }
}

// ------------------------------------------------------------------ 浏览器识别

function stopBrowser(): void {
  if (!recognition) {
    return
  }
  try {
    // stop 而不是 abort：让引擎把已听到的部分作为 final 结果吐出来，不丢字
    recognition.stop()
  } catch {
    // 已经停了，忽略
  }
}

function startBrowser(): void {
  if (!SpeechCtor) {
    return
  }
  failed.value = false
  interim.value = ''
  current = props.draft ?? ''

  recognition = new SpeechCtor()
  recognition.lang = pickSpeechLang(typeof navigator === 'undefined' ? null : navigator.language)
  /*
   * continuous=true：用户说长句子时不会在第一个停顿就结束。
   * 代价是某些浏览器会一直保持识别直到手动停止 —— 这正是「点两下」的交互前提。
   */
  recognition.continuous = true
  recognition.interimResults = true
  recognition.maxAlternatives = 1

  recognition.onstart = () => {
    listening.value = true
  }

  recognition.onresult = (event) => {
    /*
     * 只处理**本次增量**（resultIndex 之后的结果）：历史结果已经被
     * append 进草案了，再遍历一次会把前面的话重复一遍。
     */
    let finalText = ''
    let pending = ''
    for (let i = event.resultIndex; i < event.results.length; i += 1) {
      const result = event.results[i]
      if (!result) {
        continue
      }
      const transcript = result[0]?.transcript ?? ''
      if (result.isFinal) {
        finalText += transcript
      } else {
        pending += transcript
      }
    }
    if (finalText.length > 0) {
      /*
       * 基于**当前**草稿拼接而不是开始识别时的快照：用户完全可能一边说
       * 一边手打补充内容。用快照会让那段手打内容在下一段 final 到达时消失，
       * 而且消失得毫无征兆 —— 是这里最容易埋下的坑。
       */
      commit(finalText)
    }
    interim.value = pending
  }

  recognition.onerror = (event) => {
    const message = speechErrorMessage(event.error)
    // aborted 是用户主动停止，不是错误
    if (message && !failed.value) {
      failed.value = true
      ElMessage.warning(message)
    }
  }

  recognition.onend = () => {
    listening.value = false
    interim.value = ''
    recognition = null
  }

  try {
    recognition.start()
  } catch {
    // 重复 start 会抛 InvalidStateError；此时状态已经是「在听」，不必再提示
    listening.value = false
    recognition = null
  }
}

// ------------------------------------------------------------------ 编排

function stop(): void {
  if (mode.value === 'backend') {
    stopRecording()
    return
  }
  stopBrowser()
}

function start(): void {
  if (mode.value === 'backend') {
    void startRecording()
    return
  }
  startBrowser()
}

function toggle(): void {
  // 转写中不允许再点：再点一次会开始一段新录音，而上一段的结果随后到达，
  // 两段文本按到达顺序拼接，顺序与用户说话顺序不符
  if (props.disabled || transcribing.value) {
    return
  }
  if (listening.value) {
    stop()
  } else {
    start()
  }
}

/*
 * 组件卸载时必须显式停掉并释放麦克风：录音/识别都是浏览器级的资源，
 * 不停会一直占用麦克风（标签页上一直显示录音指示器），
 * 用户切到别的页面会看到「这个网站还在录音」而不知道为什么。
 */
onBeforeUnmount(() => {
  stop()
  clearTimer()
  releaseStream()
})
</script>

<template>
  <!--
    两条路都不可用时整块不渲染。用 v-if 而不是 disabled：
    一个永远点不动的麦克风按钮只会让人以为功能坏了。
  -->
  <template v-if="supported">
    <el-tooltip :content="tooltip" placement="top" :disabled="listening">
      <span>
        <el-button
          text
          size="small"
          class="voice-trigger"
          :class="{ 'voice-trigger--on': listening }"
          :disabled="disabled || transcribing"
          :loading="transcribing"
          :aria-label="tooltip"
          :aria-pressed="listening"
          @click="toggle"
        >
          <el-icon v-if="!transcribing" :size="14">
            <VideoPause v-if="listening" />
            <Microphone v-else />
          </el-icon>
        </el-button>
      </span>
    </el-tooltip>
    <!-- 实时预览：让「正在听什么」可见，用户才能判断要不要停下重说 -->
    <span v-if="preview" class="voice-preview" :title="preview">{{ preview }}</span>
  </template>
</template>

<style scoped>
.voice-trigger {
  color: var(--el-text-color-placeholder);
}

.voice-trigger:hover {
  color: var(--el-color-primary);
}

/*
 * 「正在听」必须有明显不同的外观：麦克风是浏览器级权限，
 * 用户需要随时知道它此刻到底在不在录。只换图标不够醒目，
 * 这里同时换色 + 加一圈脉冲光晕。
 */
.voice-trigger--on {
  color: var(--el-color-danger);
}

.voice-trigger--on :deep(.el-icon) {
  animation: voice-pulse 1.2s ease-in-out infinite;
}

@keyframes voice-pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.45;
  }
}

/*
 * 预览文本限宽：识别一段长话会堆出几十个字，不加限制会把 footer 里的
 * 发送按钮挤出可视区。超出部分用省略号，完整内容在 title 里。
 */
.voice-preview {
  max-width: 180px;
  overflow: hidden;
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 系统设置了「减少动态效果」就不闪 —— 部分用户对闪烁非常敏感 */
@media (prefers-reduced-motion: reduce) {
  .voice-trigger--on :deep(.el-icon) {
    animation: none;
  }
}
</style>
