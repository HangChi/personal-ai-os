// 类型定义（清单第 7 项）：用量统计
// 与后端 UsageStatsDto 一一对应。字段名保持后端原样（不改成 snake/camel 混排），
// 转换只在渲染时做一次，避免「两个名字指同一个东西」的漂移。

/** 单日用量 */
export interface DailyUsage {
  /** 本地日期 yyyy-MM-dd */
  date: string
  promptTokens: number
  completionTokens: number
  totalTokens: number
  turns: number
  /** 这一天全部数字都是估算（上游未返回 usage） */
  estimated: boolean
}

/** 用量统计汇总 */
export interface UsageStats {
  /** 实际生效的天数（后端会收敛越界值） */
  days: number
  totalPrompt: number
  totalCompletion: number
  totalTokens: number
  /** 轮次（写入的记录数） */
  turns: number
  /** 其中来自上游真实 usage 的 token 数 */
  actualTokens: number
  /** 其中属于估算的 token 数 */
  estimatedTokens: number
  /** 窗口内是否有任何记录；false 时界面显示「还没有数据」而不是画零值图 */
  hasData: boolean
  daily: DailyUsage[]
}
