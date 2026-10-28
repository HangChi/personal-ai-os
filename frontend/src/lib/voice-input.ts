/**
 * 语音输入的纯函数：浏览器能力判定、文本拼接、错误翻译、录音格式选择。
 *
 * <p>按项目约定，能被单测覆盖的判定逻辑一律放在 `src/lib/`，
 * 组件里只剩「拿到结果 → 调这里 → 写回 v-model」的编排。
 *
 * <h2>两条路，优先走后端</h2>
 * 语音输入有两条完全不同的实现，按「设置页有没有配语音模型」二选一：
 *
 * <ol>
 *   <li><b>后端转写</b>（配了语音模型时）：{@code MediaRecorder} 录音 →
 *       上传到 {@code /api/v1/chat/transcribe} → 自己的 ASR 模型转文字。
 *       音频只到自己的端点，且识别质量由用户选的模型决定。</li>
 *   <li><b>浏览器识别</b>（没配时）：Web Speech API。零配置、有实时中间结果，
 *       但 Chrome 上音频由 Google 的云端转写 —— 自托管场景下这点值得知道。</li>
 * </ol>
 *
 * <p>为什么优先后端：这是「配了就该生效」的问题。改造前设置页能识别出
 * 语音模型分类，却没有任何调用路径 —— 用户配了它，然后发现没任何变化。
 *
 * <p>回落不是降级体验而是兜底：{@code MediaRecorder} 在 Firefox 上可用，
 * 而 {@code SpeechRecognition} 在 Firefox 上至今没有实现 ——
 * 所以对 Firefox 用户，接上后端 ASR 反而**从不能用变成能用**。
 */

/** 浏览器给的最小化 SpeechRecognition 类型（DOM lib 里没有标准定义） */
export interface SpeechRecognitionLike {
  lang: string
  continuous: boolean
  interimResults: boolean
  maxAlternatives: number
  start(): void
  stop(): void
  abort(): void
  onresult: ((event: SpeechResultEventLike) => void) | null
  onerror: ((event: SpeechErrorEventLike) => void) | null
  onend: (() => void) | null
  onstart: (() => void) | null
}

export interface SpeechResultEventLike {
  /** 结果下标 + 是否最终结果；这里只需要读得到 items */
  resultIndex: number
  results: ArrayLike<ArrayLike<{ transcript: string }> & { isFinal: boolean }>
}

export interface SpeechErrorEventLike {
  error: string
  message?: string
}

/** 构造函数类型（需要 new 出来） */
export type SpeechRecognitionCtor = new () => SpeechRecognitionLike

/**
 * 从 window 上取 SpeechRecognition 构造函数。
 *
 * <p>两个全局名都要试：标准名 `SpeechRecognition` 只有极少数版本提供，
 * 实际能用的几乎都是带前缀的 `webkitSpeechRecognition`（Chrome / Edge / Safari）。
 * 只查前者会让「明明浏览器支持」被误判成不支持。
 *
 * @param scope 通常是 `window`；测试里可以塞一个假对象
 * @returns 构造函数；不支持时返回 null
 */
export function getSpeechRecognitionCtor(scope: unknown): SpeechRecognitionCtor | null {
  if (!scope || typeof scope !== 'object') {
    return null
  }
  const target = scope as Record<string, unknown>
  const candidate = target.SpeechRecognition ?? target.webkitSpeechRecognition
  return typeof candidate === 'function' ? (candidate as SpeechRecognitionCtor) : null
}

/**
 * 识别语言。
 *
 * <p>用浏览器语言而不是写死 `zh-CN`：界面虽然以中文为主，但用户完全可能
 * 用英文说话（尤其在手敲英文模型名、写代码片段时）。中文环境以外的用户
 * 拿到 `zh-CN` 会得到一段完全乱码的识别结果，而且很难联想到是语言设置的问题。
 *
 * <p>只在拿不到语言时才回落 `zh-CN`（本项目的默认受众）。
 */
export function pickSpeechLang(navigatorLanguage: string | null | undefined): string {
  const lang = (navigatorLanguage ?? '').trim()
  if (lang.length === 0) {
    return 'zh-CN'
  }
  /*
   * 按 BCP-47 解析，而不是简单取前两段：`zh-Hans-CN` 的第二段 `Hans`
   * 是**书写体系**（简体/繁体），不是地区。直接取 `parts[1].slice(0,2)`
   * 会得到 `zh-HA` —— 一个不存在的语言标签，SpeechRecognition 直接报
   * `language-not-supported`（实测踩到，且报错文案看不出是这里的问题）。
   *
   * 优先找地区子标签（两位字母或三位数字），找不到再留书写体系。
   */
  const parts = lang.split('-').filter((part) => part.length > 0)
  const head = parts[0]
  for (let i = 1; i < parts.length; i += 1) {
    const part = parts[i]
    if (/^[A-Za-z]{2}$/.test(part) || /^\d{3}$/.test(part)) {
      return `${head}-${part.toUpperCase()}`
    }
  }
  // 没有地区：`zh-Hans` 这种留书写体系（首字母大写，其余小写），比只留 `zh` 更准
  for (let i = 1; i < parts.length; i += 1) {
    const part = parts[i]
    if (/^[A-Za-z]{4}$/.test(part)) {
      return `${head}-${part.slice(0, 1).toUpperCase()}${part.slice(1).toLowerCase()}`
    }
  }
  return head
}

/** 中日韩字符与全角标点：这些字符之间不插空格 */
const CJK = /[\u3000-\u303f\u3040-\u30ff\u4e00-\u9faf\uff00-\uffef]/

/**
 * 把一次识别结果拼到已有草稿后面。
 *
 * <p>三个必须处理的边界，每一个都对应一种肉眼可见的丑：
 *
 * <ol>
 *   <li>**中文之间不能补空格** —— 识别结果是逐片返回的（「今天」+「天气不错」），
 *       补空格会变成「今天 天气不错」；而英文逐片返回（「hello」+「world」）
 *       不补空格会粘成「helloworld」。</li>
 *   <li>**草稿为空时不要留前导空格** —— 用户还没打过字，语音是第一条内容。</li>
 *   <li>**草稿以换行结尾时保持换行** —— 那是用户刻意分段的，别把两段粘一起。</li>
 * </ol>
 *
 * @param base  识别开始前的草稿（或上一段已确认的文本）
 * @param piece 本次识别到的一段文本
 * @returns 拼接后的完整草稿
 */
export function appendTranscript(base: string, piece: string): string {
  const head = base ?? ''
  const tail = (piece ?? '').trim()
  if (tail.length === 0) {
    return head
  }
  if (head.length === 0) {
    return tail
  }
  const last = head.slice(-1)
  // 已有空白（含换行）就别再加
  if (/\s/.test(last)) {
    return head + tail
  }
  const first = tail.slice(0, 1)
  const needSpace = !CJK.test(last) && !CJK.test(first)
  return needSpace ? `${head} ${tail}` : head + tail
}

/**
 * 识别错误的用户可读提示。
 *
 * <p>必须逐类翻译：`SpeechRecognitionErrorEvent.error` 是一串英文机器码，
 * 直接透出等于什么都没说（用户看到 `not-allowed` 不会想到「去浏览器设置里
 * 允许麦克风」）。而这几类的处置方式完全不同 —— 有的要改设置，
 * 有的只要再说一遍，混成一句「语音识别失败」会让人反复重试。
 *
 * @returns 中文提示；`aborted` 返回 null（用户主动停止，不该报错）
 */
export function speechErrorMessage(code: string | null | undefined): string | null {
  switch (code) {
    case 'no-speech':
      return '没有听到声音，请靠近麦克风再说一次'
    case 'audio-capture':
      return '找不到麦克风，请检查设备是否已连接'
    case 'not-allowed':
    case 'service-not-allowed':
      return '麦克风权限被拒绝，请在浏览器地址栏的权限设置里允许后重试'
    case 'network':
      return '语音识别需要联网，请检查网络后重试'
    case 'language-not-supported':
      return '当前语言不被语音识别支持，可改用键盘输入'
    case 'aborted':
      // 用户点了停止，不是错误
      return null
    default:
      return '语音识别失败，请重试或改用键盘输入'
  }
}

/**
 * 识别结果的最终文本（把 results 里所有 final 段拼起来）。
 *
 * <p>只在测试与调试时直接调用；运行时由组件按 `resultIndex` 增量拼接，
 * 以便中间结果（isFinal=false）能实时替换而不是追加。
 */
export function collectFinal(event: SpeechResultEventLike | null | undefined): string {
  if (!event || !event.results) {
    return ''
  }
  const pieces: string[] = []
  for (let i = 0; i < event.results.length; i += 1) {
    const result = event.results[i]
    if (!result || !result.isFinal) {
      continue
    }
    const alternative = result[0]
    if (alternative && typeof alternative.transcript === 'string') {
      pieces.push(alternative.transcript)
    }
  }
  return pieces.join('')
}

/**
 * 识别过程中的中间态文本（未确认部分）。
 *
 * <p>中间结果只用于**预览**：它会被下一次结果整段替换，所以绝不能直接
 * append 进草稿 —— 那样每换一个候选词就会在输入框里留一层重影。
 */
export function collectInterim(event: SpeechResultEventLike | null | undefined): string {
  if (!event || !event.results) {
    return ''
  }
  const pieces: string[] = []
  for (let i = 0; i < event.results.length; i += 1) {
    const result = event.results[i]
    if (!result || result.isFinal) {
      continue
    }
    const alternative = result[0]
    if (alternative && typeof alternative.transcript === 'string') {
      pieces.push(alternative.transcript)
    }
  }
  return pieces.join('')
}

// ------------------------------------------------------------------ MediaRecorder 录音

/**
 * 录音可用的 MIME 类型，按优先级排列。
 *
 * <h2>为什么是一个候选列表而不是写死一个</h2>
 * 没有任何一种格式是「所有浏览器 + 所有 ASR 端点」都认的：
 * <ul>
 *   <li>{@code audio/webm;codecs=opus} —— Chromium/Firefox 首选，体积最小；
 *       Safari 不支持（macOS 14 前的 Safari 完全不支持 MediaRecorder）。</li>
 *   <li>{@code audio/mp4} —— Safari 唯一支持的一种。OpenAI 与 DashScope 都认
 *       {@code .m4a}，而它只接受 {@code .webm} 以外的少数几种。</li>
 *   <li>{@code audio/ogg;codecs=opus} —— Firefox 也提供，作为第二选择。</li>
 * </ul>
 * 逐个试探 `MediaRecorder.isTypeSupported`，取第一个支持的。
 *
 * <h2>为什么不写 {@code audio/wav}</h2>
 * 浏览器不提供 WAV 编码（要自己做 PCM 打包），而 WAV 体积是 opus 的 15 倍左右
 * —— 8MB 上限下只能录 40 秒。
 */
export const RECORDER_MIME_CANDIDATES: readonly string[] = [
  'audio/webm;codecs=opus',
  'audio/webm',
  'audio/ogg;codecs=opus',
  'audio/mp4',
]

/** MIME → 上传时用的文件名后缀。很多端点靠扩展名判断格式，写错就 400 */
const MIME_EXTENSIONS: Record<string, string> = {
  'audio/webm;codecs=opus': 'webm',
  'audio/webm': 'webm',
  'audio/ogg;codecs=opus': 'ogg',
  'audio/ogg': 'ogg',
  'audio/mp4': 'm4a',
  'audio/mpeg': 'mp3',
  'audio/wav': 'wav',
}

/**
 * 选一个当前浏览器支持的录音格式。
 *
 * @param isTypeSupported 通常传 `MediaRecorder.isTypeSupported`；
 *                        测试里塞一个假函数。为 null 时（无 MediaRecorder）返回空串
 * @returns 可用的 MIME；一个都不支持时返回空串
 */
export function pickRecorderMime(isTypeSupported: ((mime: string) => boolean) | null | undefined): string {
  if (typeof isTypeSupported !== 'function') {
    return ''
  }
  for (const mime of RECORDER_MIME_CANDIDATES) {
    try {
      if (isTypeSupported(mime)) {
        return mime
      }
    } catch {
      // 某些实现对非法类型直接抛异常，而不是返回 false
    }
  }
  return ''
}

/**
 * MIME → 文件名后缀。
 *
 * <p>为什么要单独一个函数而不是在上传处 `split('/')[1]`：
 * `audio/webm;codecs=opus` 会切出 `webm;codecs=opus`，带着分号与参数名，
 * 而文件名里出现分号会让部分端点的 multipart 解析器直接把请求判为非法。
 *
 * @param mime 录音的 MIME
 * @returns 后缀（不含点）；未知时返回 `webm`（最常见的默认）
 */
export function mimeToExtension(mime: string | null | undefined): string {
  const normalized = (mime ?? '').trim().toLowerCase()
  if (normalized.length === 0) {
    return 'webm'
  }
  const exact = MIME_EXTENSIONS[normalized]
  if (exact) {
    return exact
  }
  // 去掉参数后再试一次（`audio/ogg; codecs=vorbis`）
  const base = normalized.split(';')[0].trim()
  return MIME_EXTENSIONS[base] ?? 'webm'
}

/**
 * 录音时长的可读描述，用于「已录 3 秒」这类提示。
 *
 * @param milliseconds 毫秒
 */
export function formatDuration(milliseconds: number): string {
  const seconds = Math.max(0, Math.floor((milliseconds || 0) / 1000))
  if (seconds < 60) {
    return `${seconds} 秒`
  }
  const minutes = Math.floor(seconds / 60)
  return `${minutes} 分 ${seconds % 60} 秒`
}

/**
 * 转写失败时的提示语。
 *
 * <p>与 {@link speechErrorMessage} 同一原则：逐类翻译，因为这几种情况的
 * 下一步动作完全不同 —— 没配模型要去设置页，超时要检查网络，
 * 限流要等一会儿。混成一句「转写失败」会让用户反复点麦克风。
 *
 * @param unavailable 后端是否报告「没配语音模型」
 * @param error       后端给的原始原因（已含 401/404 等分类）
 * @returns 中文提示；null 表示不该报错（比如用户主动取消）
 */
export function transcribeErrorMessage(unavailable: boolean, error: string | null | undefined): string {
  if (unavailable) {
    // 走到这里说明前端原本以为配了、后端说没有（多半是刚切了配置）。
    // 不是故障：提示用户去哪儿配即可，不要说「失败」
    return '没有可用的语音模型，可在设置 → 模型配置里添加「语音模型」'
  }
  const raw = (error ?? '').trim()
  if (raw.length === 0) {
    return '语音转写失败，请重试或改用键盘输入'
  }
  if (raw.includes('401') || raw.includes('403')) {
    return '语音模型鉴权失败，请检查设置里的 API Key 是否有效'
  }
  if (raw.includes('404')) {
    return '这个端点不支持 OpenAI 兼容的转写接口，可在设置里换一个语音模型'
  }
  if (raw.includes('429')) {
    return '语音转写被限流，请稍后再试'
  }
  if (raw.includes('超时')) {
    return '语音转写超时，请检查网络或端点地址后重试'
  }
  // 其余情况透出后端给的说明：它已经把技术原因翻成中文，比前端再猜一遍准
  return raw
}
