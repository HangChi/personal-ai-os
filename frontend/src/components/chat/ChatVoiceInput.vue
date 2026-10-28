<script setup lang="ts">
/**
 * 语音输入按钮（麦克风）。
 *
 * <h2>为什么是「按住说话」之外的一种选择</h2>
 * 用的是 Web Speech API 的**连续识别**模式：点一下开始，再点一下停止，
 * 中间说的话实时变成文字写回输入框。没有做「按住说话」是因为：
 * 鼠标按住期间无法聚焦输入框、也无法按下发送键；移动端长按还会触发系统菜单。
 * 点两下的交互在桌面与移动端都成立。
 *
 * <h2>为什么中间结果要单独显示而不直接写进输入框</h2>
 * 识别器每收到一个候选词就会发一次中间结果，且**下一次结果会整段替换上一次**。
 * 直接 append 进草稿会让输入框里留下一串重影（「今天天天气天天气不错」）。
 * 所以：已确认（isFinal）的追加进草稿，未确认的只作为临时预览显示在按钮旁。
 *
 * <h2>不支持时的处理</h2>
 * Firefox 至今没有实现 SpeechRecognition。此时**不渲染按钮**，
 * 而不是渲染一个点了没反应的按钮 —— 后者比没有更糟，用户会以为是坏了。
 */
import { Microphone, VideoPause } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onBeforeUnmount, ref, watch } from 'vue'

import {
  appendTranscript,
  getSpeechRecognitionCtor,
  pickSpeechLang,
  speechErrorMessage,
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

/**
 * 能力检测放在模块级常量而不是 computed：构造函数的可用性在整个会话里
 * 不会变，每次渲染都去读 window 是没必要的开销，也让模板更难读。
 */
const Ctor = typeof window === 'undefined' ? null : getSpeechRecognitionCtor(window)

const supported = Ctor !== null
const listening = ref(false)
/** 未确认的识别片段，只用于预览 */
const interim = ref('')
/** 是否已经因为出错/不支持而提示过，避免连续弹窗 */
const failed = ref(false)

let recognition: SpeechRecognitionLike | null = null

/**
 * 组件眼中的「最新草稿」。
 *
 * <p>为什么不直接用 `props.draft`：连续两次 `onresult` 之间，父组件的 props
 * 更新要等一拍，第二次事件读到的还是旧值 —— 会把上一段 final 丢掉。
 * 所以本地维护一份，emit 后立即同步。
 */
let current = ''

/**
 * 上一次由本组件 emit 出去的值。
 *
 * <p>用来区分「props 变化是 emit 的回环」还是「用户手打了字」。
 * 不区分的话：用户一边说一边改文字时，要么丢掉手打内容（用开始时的快照当基线），
 * 要么把上一段识别结果重复一遍（把回环当成用户输入再 append 一次）。
 * 两者都是肉眼可见的错误，且用户很难联想到是语音输入导致的。
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

const tooltip = computed(() => {
  if (!supported) {
    return '当前浏览器不支持语音输入（需 Chrome / Edge / Safari）'
  }
  if (props.disabled) {
    return '正在生成，稍后再用语音输入'
  }
  return listening.value ? '停止语音输入' : '语音输入（点一下开始说）'
})

/** 预览文本：让用户看到「正在识别什么」，而不是对着一个闪烁的图标猜 */
const preview = computed(() => interim.value.trim())

function stop(): void {
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

function start(): void {
  if (!Ctor) {
    return
  }
  failed.value = false
  interim.value = ''
  current = props.draft ?? ''

  recognition = new Ctor()
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
     * 追加进 baseDraft 了，再遍历一次会把前面的话重复一遍。
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
      current = appendTranscript(current, finalText)
      lastEmitted = current
      emit('update:draft', current)
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

function toggle(): void {
  if (props.disabled) {
    return
  }
  if (listening.value) {
    stop()
  } else {
    start()
  }
}

/*
 * 组件卸载时必须显式停掉：SpeechRecognition 是浏览器级的资源，
 * 不 stop 会一直占用麦克风（标签页上一直显示录音指示器），
 * 用户切到别的页面会看到「这个网站还在录音」而不知道为什么。
 */
onBeforeUnmount(stop)
</script>

<template>
  <!--
    不支持时整块不渲染。用 v-if 而不是 disabled：
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
          :disabled="disabled"
          :aria-label="tooltip"
          :aria-pressed="listening"
          @click="toggle"
        >
          <el-icon :size="14">
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
