/**
 * 语音输入的纯函数：浏览器能力判定、文本拼接、错误翻译。
 *
 * <p>按项目约定，能被单测覆盖的判定逻辑一律放在 `src/lib/`，
 * 组件里只剩「拿到结果 → 调这里 → 写回 v-model」的编排。
 *
 * <h2>为什么用 Web Speech API 而不是服务端转写</h2>
 * 本项目已有模型配置能力，理论上可以把录音发给 ASR 模型转写。但那条路要先解决
 * 录音编码（MediaRecorder → 文件）、上传、轮询结果三件事，且每次说话都要花一次
 * 模型调用。Web Speech API 是浏览器内置的，零成本、有实时中间结果，
 * 对「说一句话转成文字塞进输入框」这个场景完全够用。
 *
 * <p>代价是**只有 Chromium 系与 Safari 支持**，Firefox 至今没有实现。
 * 所以能力检测必须诚实：不支持就不显示按钮，而不是显示一个点了没反应的按钮。
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
