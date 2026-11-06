/** 可分享的知识页位置；与本地默认视图分离，深链接优先。 */
export const KNOWLEDGE_TABS = ['documents', 'search', 'ask', 'captures', 'governance', 'memory'] as const
export type KnowledgeTab = (typeof KNOWLEDGE_TABS)[number]

export function isKnowledgeTab(value: unknown): value is KnowledgeTab {
  return typeof value === 'string' && KNOWLEDGE_TABS.some((tab) => tab === value)
}

/** 旧书签和跨模块链接统一落到新入口，保留其他查询参数。 */
export const legacyWorkspaceRoutes = [
  { path: '/memory', name: 'memory', redirect: { path: '/knowledge', query: { tab: 'memory' } } },
  { path: '/usage', name: 'usage', redirect: { path: '/activity', query: { view: 'usage' } } },
  { path: '/maintenance', name: 'maintenance', redirect: { path: '/settings', query: { section: 'data' } } },
] as const
