import { eq, check } from './assert.mjs'

/**
 * 数据导出（阶段 12）。
 *
 * 只测纯函数部分（文件名解析与生成）：真正触发下载要 DOM，
 * 而「解析错文件名」「把 UTF-8 解成乱码」这类问题是纯逻辑，能在 Node 里断言。
 */

const { resolveFilename, defaultFilename } = await import('@/api/export')

// ---- 文件名解析 ----

{
  eq(
    '标准 attachment 头',
    resolveFilename('attachment; filename="personal-ai-os-export-20261025-120000.json"', 'x.json'),
    'personal-ai-os-export-20261025-120000.json',
  )
}

{
  eq('缺失头时用兜底名', resolveFilename(undefined, 'fallback.json'), 'fallback.json')
  eq('空串头时用兜底名', resolveFilename('', 'fallback.json'), 'fallback.json')
}

{
  eq('无引号也能解析', resolveFilename('attachment; filename=a.json', 'x.json'), 'a.json')
}

{
  // RFC 5987 形式：filename*=UTF-8''%E5%AF%BC%E5%87%BA.json
  eq(
    'UTF-8 编码的文件名被解码',
    resolveFilename("attachment; filename*=UTF-8''%E5%AF%BC%E5%87%BA.json", 'x.json'),
    '导出.json',
  )
}

{
  // 文件名的值里带分号时只取到分号前，不能把后面的参数一起吞进去
  eq('分号分隔不会吞掉后续参数', resolveFilename('attachment; filename="a.json"; size=123', 'x.json'), 'a.json')
}

// ---- 兜底文件名 ----

{
  const name = defaultFilename(new Date(Date.UTC(2026, 9, 25, 12, 0, 0)))
  eq('兜底名格式正确', name, 'personal-ai-os-export-20261025-120000.json')
  check('兜底名以 .json 结尾', name.endsWith('.json'))
}

{
  // 个位数月/日/时/分/秒必须补零，否则文件名排序会乱
  const name = defaultFilename(new Date(Date.UTC(2026, 0, 5, 3, 7, 9)))
  eq('个位数补零', name, 'personal-ai-os-export-20260105-030709.json')
}
