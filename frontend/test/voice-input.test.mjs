import { check, eq } from './assert.mjs'

/**
 * 语音输入纯函数。
 *
 * 断言口径集中在三类「写错了不报错、只表现为行为莫名其妙」的地方：
 * ① 能力检测 —— 弄错会让支持的浏览器看不到按钮、或不支持的浏览器点出报错；
 * ② 文本拼接 —— 弄错表现为中文多空格、英文粘连，且用户根本不会联想到语音；
 * ③ 错误翻译 —— 机器码直接透出等于什么都没说，混成一句又会让人反复重试。
 */

const {
  appendTranscript,
  collectFinal,
  collectInterim,
  formatDuration,
  getSpeechRecognitionCtor,
  mimeToExtension,
  pickRecorderMime,
  pickSpeechLang,
  RECORDER_MIME_CANDIDATES,
  speechErrorMessage,
  transcribeErrorMessage,
} = await import('@/lib/voice-input')

// ---------------------------------------------------------------- 能力检测

{
  // 标准名
  function Standard() {}
  eq('标准 SpeechRecognition 能取到', typeof getSpeechRecognitionCtor({ SpeechRecognition: Standard }), 'function')

  // 实际能用的几乎都是带前缀的
  function Prefixed() {}
  eq(
    'webkitSpeechRecognition 也能取到（Chrome/Edge/Safari 实际形态）',
    typeof getSpeechRecognitionCtor({ webkitSpeechRecognition: Prefixed }),
    'function',
  )

  // 两个都有时优先标准名
  function A() {}
  function B() {}
  check('两个都存在时优先标准名', getSpeechRecognitionCtor({ SpeechRecognition: A, webkitSpeechRecognition: B }) === A)

  // 不支持：必须返回 null 让调用方隐藏按钮，而不是抛异常
  eq('两个都没有时返回 null', getSpeechRecognitionCtor({}), null)
  eq('非函数值不当作构造函数', getSpeechRecognitionCtor({ SpeechRecognition: 'yes' }), null)
  eq('null 作用域不抛异常', getSpeechRecognitionCtor(null), null)
  eq('undefined 作用域不抛异常', getSpeechRecognitionCtor(undefined), null)
}

// ---------------------------------------------------------------- 识别语言

{
  eq('中文环境取 zh-CN', pickSpeechLang('zh-CN'), 'zh-CN')
  eq('英文环境取 en-US', pickSpeechLang('en-US'), 'en-US')
  eq('小写地区码转大写', pickSpeechLang('zh-cn'), 'zh-CN')
  /*
   * 三段式的第二段是**书写体系**不是地区：`zh-Hans-CN` 必须解析出 `zh-CN`。
   *
   * 这条断言防的是一个很难自己发现的 bug：按「取前两段」实现会得到 `zh-HA`，
   * 那是个不存在的标签，SpeechRecognition 直接报 language-not-supported，
   * 而报错文案与这里毫无关系（实测踩到过）。
   */
  eq('三段式跳过书写体系取地区', pickSpeechLang('zh-Hans-CN'), 'zh-CN')
  eq('三段式繁体同样取地区', pickSpeechLang('zh-Hant-TW'), 'zh-TW')
  eq('只有书写体系时保留它', pickSpeechLang('zh-Hans'), 'zh-Hans')
  eq('只有主语言时原样', pickSpeechLang('ja'), 'ja')
  eq('空串回落 zh-CN', pickSpeechLang(''), 'zh-CN')
  eq('null 回落 zh-CN', pickSpeechLang(null), 'zh-CN')
  eq('空白串回落 zh-CN', pickSpeechLang('   '), 'zh-CN')
}

// ---------------------------------------------------------------- 文本拼接

{
  // 关键：中文之间**不能**补空格
  eq('中文分段拼接不加空格', appendTranscript('今天', '天气不错'), '今天天气不错')
  eq('中文标点后不加空格', appendTranscript('你好，', '我想问个问题'), '你好，我想问个问题')

  // 英文之间**必须**补空格
  eq('英文分段拼接补空格', appendTranscript('hello', 'world'), 'hello world')

  // 混排：以中文结尾时紧跟英文也不加空格（CJK 侧优先）
  eq('中文后接英文不补空格', appendTranscript('模型名是', 'qwen'), '模型名是qwen')

  // 草稿为空：不留前导空格
  eq('空草稿不留前导空格', appendTranscript('', ' 你好 '), '你好')
  eq('空草稿接英文也不留空格', appendTranscript('', 'hello'), 'hello')

  // 草稿已有空白结尾：不重复加
  eq('草稿以空格结尾不重复加', appendTranscript('hello ', 'world'), 'hello world')
  eq('草稿以换行结尾保持换行', appendTranscript('第一段\n', '第二段'), '第一段\n第二段')

  // 空片段：原样返回草稿（识别没听到内容时不能把草稿清掉）
  eq('空片段不清空草稿', appendTranscript('已有内容', ''), '已有内容')
  eq('空白片段不追加空格', appendTranscript('已有内容', '   '), '已有内容')
  eq('纯空格草稿 + 空片段 = 空', appendTranscript('', ''), '')

  // null 兜底
  eq('null 草稿当空处理', appendTranscript(null, '你好'), '你好')
  eq('null 片段保留草稿', appendTranscript('你好', null), '你好')
}

// ---------------------------------------------------------------- 错误翻译

{
  eq('no-speech 提示再说一次', speechErrorMessage('no-speech'), '没有听到声音，请靠近麦克风再说一次')
  eq('audio-capture 提示检查设备', speechErrorMessage('audio-capture'), '找不到麦克风，请检查设备是否已连接')
  check('not-allowed 提示改权限', (speechErrorMessage('not-allowed') ?? '').includes('权限'))
  check('service-not-allowed 同样提示权限', (speechErrorMessage('service-not-allowed') ?? '').includes('权限'))
  check('network 提示检查网络', (speechErrorMessage('network') ?? '').includes('网络'))

  // aborted 是用户主动停止，**不能**报错
  eq('aborted 不算错误', speechErrorMessage('aborted'), null)

  // 未知码要有兜底文案，而不是透出机器码
  const unknown = speechErrorMessage('some-new-code')
  check('未知码有兜底文案', typeof unknown === 'string' && unknown.length > 0)
  check('兜底文案不透出机器码', unknown.includes('some-new-code') === false)
  eq('null 码也有兜底文案', typeof speechErrorMessage(null), 'string')
}

// ---------------------------------------------------------------- 结果提取

{
  // 构造 SpeechRecognitionResultList 的最小形状
  const makeResult = (transcript, isFinal) => {
    const r = [{ transcript }]
    r.isFinal = isFinal
    return r
  }
  const makeList = (items) => {
    const list = items
    list.item = (i) => items[i]
    return list
  }

  const event = {
    resultIndex: 0,
    results: makeList([makeResult('你好', true), makeResult('世界', true), makeResult('还在识别', false)]),
  }

  eq('collectFinal 只取已确认段', collectFinal(event), '你好世界')
  eq('collectInterim 只取未确认段', collectInterim(event), '还在识别')

  // 全未确认时 collectFinal 为空 —— 组件据此不写回草稿
  eq('全未确认时无 final', collectFinal({ resultIndex: 0, results: makeList([makeResult('猜', false)]) }), '')
  // 全已确认时 collectInterim 为空
  eq('全已确认时无 interim', collectInterim({ resultIndex: 0, results: makeList([makeResult('好', true)]) }), '')

  // 空值兜底
  eq('null 事件 collectFinal 返回空', collectFinal(null), '')
  eq('null 事件 collectInterim 返回空', collectInterim(null), '')
  eq('无 results 不抛异常', collectFinal({ resultIndex: 0, results: null }), '')
}

// ---------------------------------------------------------------- 组合场景

{
  /*
   * 真实链路：说两段话，中间停顿被切成两个 final。
   * 用 appendTranscript 逐段接线，结果必须是一句通顺的中文。
   */
  let draft = ''
  draft = appendTranscript(draft, '帮我总结一下')
  draft = appendTranscript(draft, '这个项目的技术栈')
  eq('两段中文最终拼接通顺', draft, '帮我总结一下这个项目的技术栈')

  // 用户在已有草稿后继续说英文模型名
  let draft2 = '用哪个模型'
  draft2 = appendTranscript(draft2, 'qwen3.8-flash')
  eq('中文草稿后接英文模型名不夹空格', draft2, '用哪个模型qwen3.8-flash')

  // 英文草稿后继续说英文
  let draft3 = 'explain'
  draft3 = appendTranscript(draft3, 'the concept')
  eq('英文连续拼接保持单空格', draft3, 'explain the concept')
}

// ---------------------------------------------------------------- 录音格式选择（后端转写）

{
  // 全支持时取列表里第一个：opus/webm 体积最小，Chromium/Firefox 都认
  eq(
    '全支持时取第一个候选',
    pickRecorderMime(() => true),
    'audio/webm;codecs=opus',
  )

  // Safari：只认 mp4。选错格式会让 MediaRecorder 构造抛异常，
  // 症状是「点了麦克风什么也没发生」—— 必须真的按能力选
  eq(
    '只有 mp4 时选 mp4（Safari）',
    pickRecorderMime((m) => m === 'audio/mp4'),
    'audio/mp4',
  )

  // 只有 ogg 的浏览器（部分 Firefox 构建）
  eq(
    '只有 ogg 时选 ogg',
    pickRecorderMime((m) => m === 'audio/ogg;codecs=opus'),
    'audio/ogg;codecs=opus',
  )

  // 一个都不支持 → 空串，调用方据此判定「不能录音」
  eq(
    '都不支持时返回空串',
    pickRecorderMime(() => false),
    '',
  )

  // 没有 MediaRecorder 时传 null，不能抛异常
  eq('null 判定函数不抛异常', pickRecorderMime(null), '')

  // 某些实现对非法类型直接抛异常而不是返回 false
  eq(
    '判定函数抛异常时跳过该项继续试',
    pickRecorderMime((m) => {
      if (m === 'audio/webm;codecs=opus') {
        throw new Error('not supported')
      }
      return m === 'audio/webm'
    }),
    'audio/webm',
  )

  // 候选顺序就是优先级：改动顺序会改变实际录音格式
  eq('候选列表首项是 webm/opus', RECORDER_MIME_CANDIDATES[0], 'audio/webm;codecs=opus')
  check('候选列表含 Safari 的 mp4', RECORDER_MIME_CANDIDATES.includes('audio/mp4'))
}

{
  // 带参数的 MIME 必须去掉参数：文件名里出现分号会让部分端点的
  // multipart 解析器直接判请求非法
  eq('webm+opus 取 webm', mimeToExtension('audio/webm;codecs=opus'), 'webm')
  eq('mp4 取 m4a（OpenAI 只认这个后缀）', mimeToExtension('audio/mp4'), 'm4a')
  eq('ogg+opus 取 ogg', mimeToExtension('audio/ogg;codecs=opus'), 'ogg')
  eq('纯 mp3 取 mp3', mimeToExtension('audio/mpeg'), 'mp3')
  eq('大小写不敏感', mimeToExtension('AUDIO/MP4'), 'm4a')
  eq('未知类型回落 webm', mimeToExtension('audio/x-unknown'), 'webm')
  eq('空值回落 webm 而不是崩溃', mimeToExtension(''), 'webm')
  eq('null 回落 webm', mimeToExtension(null), 'webm')
  // 分号后带空格也要能剥掉
  eq('分号后有空格也能剥', mimeToExtension('audio/ogg; codecs=vorbis'), 'ogg')
}

// ---------------------------------------------------------------- 时长显示

{
  eq('0 毫秒显示 0 秒', formatDuration(0), '0 秒')
  eq('不足 1 秒显示 0 秒', formatDuration(999), '0 秒')
  eq('3.4 秒向下取整', formatDuration(3400), '3 秒')
  eq('59 秒不进化到分钟', formatDuration(59_000), '59 秒')
  eq('60 秒进化到 1 分 0 秒', formatDuration(60_000), '1 分 0 秒')
  eq('95 秒显示 1 分 35 秒', formatDuration(95_000), '1 分 35 秒')
  // 负数不该出现，但不该显示「-1 秒」
  eq('负数被夹到 0', formatDuration(-500), '0 秒')
}

// ---------------------------------------------------------------- 转写错误翻译

{
  // 没配语音模型不是错误：要指出去哪儿配，而不是说「失败」
  const unavailable = transcribeErrorMessage(true, '当前生效的模型配置没有语音模型')
  check('不可用时提示去设置页配', unavailable.includes('设置'))
  check('不可用时不说「失败」', !unavailable.includes('失败'))

  // 401/403 → 密钥问题，这是用户唯一能自己修的一类
  check('401 指向 API Key', transcribeErrorMessage(false, '认证失败（401）：API Key 无效').includes('API Key'))
  check('403 也指向 API Key', transcribeErrorMessage(false, '无权限（403）').includes('API Key'))

  // 404 → 该端点没有兼容接口，换模型是唯一出路
  check(
    '404 建议换语音模型',
    transcribeErrorMessage(false, '接口不存在（404）：/v1/audio/transcriptions').includes('转写接口'),
  )

  check('429 建议稍后重试', transcribeErrorMessage(false, '被限流（429）').includes('稍后'))
  check('超时提示检查网络', transcribeErrorMessage(false, '转写超时（超过 30 秒）').includes('网络'))

  // 其它原因透出后端说明：它已经把技术原因翻成中文，前端再猜一遍只会更差
  eq('未知原因透出后端说明', transcribeErrorMessage(false, '录音超过 8MB，请缩短后重试'), '录音超过 8MB，请缩短后重试')

  // 空原因也要有一句能看的话，不能显示空白
  check('空原因给出兜底文案', transcribeErrorMessage(false, '').includes('转写'))
  check('null 原因给出兜底文案', transcribeErrorMessage(false, null).includes('转写'))
}
