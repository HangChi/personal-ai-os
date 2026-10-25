import { requestData } from '@/api/http'

/** 一条搜索命中（对应后端 SearchHit） */
export interface SearchHitItem {
  /** 对象类型：task / session / event / memory / document */
  kind: string
  id: string
  title: string
  subtitle: string | null
  /** 命中的字段：标题 / 描述 / 地点 / 内容 / 消息内容 / 文件名 / 正文 */
  matchedField: string
}

/** 一个模块的分组（对应后端 SearchGroup） */
export interface SearchGroupItem {
  /** 模块中文名 */
  module: string
  kind: string
  hits: SearchHitItem[]
}

/** 跨模块搜索结果（对应后端 GlobalSearchResult） */
export interface GlobalSearchResult {
  keyword: string
  totalHits: number
  groups: SearchGroupItem[]
}

/**
 * 跨模块全局搜索（阶段 17）。
 *
 * @param keyword 关键词（前端负责 trim）
 * @param limit   每模块条数，默认 5
 */
export function globalSearch(keyword: string, limit = 5): Promise<GlobalSearchResult> {
  return requestData<GlobalSearchResult>({
    url: '/search',
    method: 'get',
    params: { keyword, limit },
  })
}
