import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import KnowledgeAskTab from '@/components/knowledge/KnowledgeAskTab.vue'
import KnowledgeGovernanceTab from '@/components/knowledge/KnowledgeGovernanceTab.vue'
import KnowledgeHitList from '@/components/knowledge/KnowledgeHitList.vue'
import RagTrace from '@/components/knowledge/RagTrace.vue'
import { useKnowledgeStore } from '@/stores/knowledge'
import * as api from '@/api/knowledge-governance'
import type { RagRetrieval, KnowledgeSource, KnowledgeHit } from '@/types/api'
vi.mock('@/api/knowledge-governance', () => ({
  listSpaces: vi.fn(),
  createSpace: vi.fn(),
  listSources: vi.fn(),
  updateSource: vi.fn(),
  retryReview: vi.fn(),
  listConflicts: vi.fn(),
  resolveConflict: vi.fn(),
}))
const source: KnowledgeSource = {
  spaceId: 's',
  spaceName: '财务制度',
  version: '2026版',
  scope: '研发部',
  effectiveFrom: '2026-01-01',
  effectiveUntil: null,
  authority: '财务',
  lifecycle: 'ACTIVE',
  revision: 2,
  reviewState: 'CHECKED_CANDIDATES',
}
const hit: KnowledgeHit = {
  documentId: 'a',
  documentTitle: '差旅制度',
  filename: 'a.md',
  chunkIndex: 0,
  content: '每天五百元',
  score: 0.8,
  source,
}
const retrieval: RagRetrieval = {
  hits: [hit],
  queries: ['报销多少', '住宿限额'],
  stages: [{ name: 'evidence', status: 'FALLBACK', durationMs: 8000, inputCount: 2, outputCount: 2 }],
  warnings: ['证据精审未完成'],
  status: 'UNVERIFIED',
  disagreements: [],
  clarification: '',
}
describe('RAG 证据与治理界面', () => {
  it('外部向量召回没有余弦分时仍显示向量来源', () => {
    const wrapper = mount(KnowledgeHitList, { props: { items: [{ ...hit, score: null, matchedBy: 'VECTOR' }] } })
    expect(wrapper.text()).toContain('向量命中')
    expect(wrapper.text()).not.toContain('关键词命中')
    wrapper.unmount()
  })
  it('核对未完成时展示降级和过程，不显示事实认证', () => {
    const wrapper = mount(RagTrace, { props: { retrieval } })
    expect(wrapper.text()).toContain('证据核对未完成')
    expect(wrapper.text()).toContain('已降级')
    expect(wrapper.text()).toContain('住宿限额')
    expect(wrapper.text()).toContain('研发部')
    wrapper.unmount()
  })
  it('有冲突引用时不把 answered=false 显示为空知识库', () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    useKnowledgeStore().answer = {
      question: '报销多少',
      answer: '存在不同说法，请补充日期 [1]。',
      answered: false,
      citations: [{ ...hit, index: 1 }],
      retrieval: { ...retrieval, status: 'CONFLICT' },
    }
    const wrapper = mount(KnowledgeAskTab, { global: { plugins: [pinia] } })
    expect(wrapper.text()).toContain('存在不同说法')
    expect(wrapper.text()).toContain('引用来源（1）')
    expect(wrapper.text()).not.toContain('知识库中没有找到相关内容')
    wrapper.unmount()
  })
  it('治理页面区分候选审查与无冲突，并复制编辑数据', async () => {
    vi.mocked(api.listSpaces).mockResolvedValue([{ id: 's', name: '财务制度' }])
    vi.mocked(api.listSources).mockResolvedValue([{ documentId: 'a', title: '差旅制度', source: { ...source } }])
    vi.mocked(api.listConflicts).mockResolvedValue([])
    const wrapper = mount(KnowledgeGovernanceTab, { global: { stubs: { teleport: true } } })
    await flushPromises()
    expect(wrapper.text()).toContain('相关候选审查完成')
    expect(wrapper.text()).toContain('不代表不存在冲突')
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '编辑')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('资料来源与适用条件')
    expect(api.updateSource).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
