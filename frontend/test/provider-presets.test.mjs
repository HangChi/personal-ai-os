import { check, eq } from './assert.mjs'

/**
 * 供应商预设库。
 *
 * 预设是本项目「换供应商不用手填端点」的唯一来源，三条容易出错的点：
 * ① 数据完整性 —— 缺 baseUrl 会让新建出来的配置直接不可用；
 * ② 搜索覆盖 —— 只按名称搜会让「记得域名/模型名但忘了名称」的用户搜不到；
 * ③ 预设 id 唯一 —— 重复 id 会让 findPreset 永远只返回第一个。
 */

const { PROVIDER_PRESETS, defaultProfileName, findPreset, searchPresets } = await import('@/lib/provider-presets')

// ---------------------------------------------------------------- 数据完整性

{
  check('预设库非空', PROVIDER_PRESETS.length > 0)

  const ids = PROVIDER_PRESETS.map((preset) => preset.id)
  eq('预设 id 必须唯一', new Set(ids).size, ids.length)

  for (const preset of PROVIDER_PRESETS) {
    check(`${preset.id} 有 id`, typeof preset.id === 'string' && preset.id.length > 0)
    check(`${preset.id} 有名称`, typeof preset.name === 'string' && preset.name.length > 0)
    check(`${preset.id} 有厂商`, typeof preset.vendor === 'string' && preset.vendor.length > 0)
    check(`${preset.id} 有说明`, typeof preset.note === 'string' && preset.note.length > 0)
    // 除「自定义」外都必须给出端点：否则选中预设后建出来的配置不可用
    if (preset.id !== 'custom') {
      check(`${preset.id} 提供了端点地址`, preset.baseUrl.startsWith('http'))
      check(`${preset.id} 提供了对话模型`, preset.chatModel.length > 0)
    }
  }
}

// ---------------------------------------------------------------- 查找

{
  eq('按 id 取到预设', findPreset('dashscope')?.name, '阿里云百炼')
  eq('未知 id 返回 null 而不是抛异常', findPreset('不存在的预设'), null)
  eq('null 返回 null', findPreset(null), null)
  eq('undefined 返回 null', findPreset(undefined), null)
}

// ---------------------------------------------------------------- 搜索

{
  eq('空关键词返回全部', searchPresets('').length, PROVIDER_PRESETS.length)

  // 三种记忆方式都要能搜到：中文名 / 厂商英文 / 域名
  check(
    '按中文名搜到百炼',
    searchPresets('百炼').some((p) => p.id === 'dashscope'),
  )
  check(
    '按厂商名搜到 DeepSeek',
    searchPresets('deepseek').some((p) => p.id === 'deepseek'),
  )
  check(
    '按域名搜到百炼',
    searchPresets('dashscope.aliyuncs.com').some((p) => p.id === 'dashscope'),
  )
  check(
    '按模型名搜到 qwen',
    searchPresets('qwen').some((p) => p.id === 'dashscope'),
  )

  // 大小写不敏感：用户不会记得大小写
  eq('搜索大小写不敏感', searchPresets('DASHSCOPE').length, searchPresets('dashscope').length)

  eq('无匹配返回空数组', searchPresets('绝无此供应商zzz').length, 0)
  eq('纯空白视为无关键词', searchPresets('   ').length, PROVIDER_PRESETS.length)
}

// ---------------------------------------------------------------- 默认名

{
  const dashscope = findPreset('dashscope')
  eq('默认名取预设名，不加序号', defaultProfileName(dashscope), dashscope.name)
}
