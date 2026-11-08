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
    expect(wrapper.get('details').attributes('open')).toBeUndefined()
    expect(wrapper.get('summary').text()).toContain('1 篇来源 · 1 条提示')
    expect(wrapper.get('summary').text()).not.toContain('证据精审未完成')
    expect(wrapper.find('.el-alert').exists()).toBe(false)
    expect(wrapper.get('.rag-trace__notices').text()).toContain('证据精审未完成')
    expect(wrapper.text()).toContain('已降级')
    expect(wrapper.text()).toContain('住宿限额')
    expect(wrapper.text()).toContain('研发部')
    wrapper.unmount()
  })
  it('关闭查询改写时展示已跳过，继续展示重排和精审且不误报降级', () => {
    const wrapper = mount(RagTrace, {
      props: {
        retrieval: {
          ...retrieval,
          status: 'READY',
          warnings: [],
          queries: ['报销多少'],
          stages: [
            { name: 'rewrite', status: 'SKIPPED', durationMs: 0, inputCount: 1, outputCount: 1 },
            { name: 'rerank', status: 'OK', durationMs: 10, inputCount: 2, outputCount: 2 },
            { name: 'evidence', status: 'OK', durationMs: 20, inputCount: 2, outputCount: 1 },
          ],
        },
      },
    })
    const stages = wrapper.findAll('.rag-trace__stage-title').map((stage) => stage.text())
    expect(stages).toEqual(['查询改写已跳过', '候选重排完成', '证据精审完成'])
    expect(wrapper.get('summary').text()).toContain('已找到可用证据')
    expect(wrapper.get('summary').text()).not.toContain('条提示')
    expect(wrapper.find('.rag-trace__notices').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('已降级')
    expect(wrapper.get('.rag-trace__queries').text()).toBe('检索问题：报销多少')
    wrapper.unmount()
  })
  it('合并同文档分块与重复提示，区分无结果和降级，省略空元数据', async () => {
    const wrapper = mount(RagTrace, {
      props: {
        retrieval: {
          ...retrieval,
          hits: [hit, { ...hit, chunkIndex: 1 }],
          warnings: ['证据精审未完成', '证据精审未完成', ''],
          stages: [{ ...retrieval.stages[0]!, status: 'EMPTY' }],
        },
      },
    })
    expect(wrapper.get('summary').text()).toContain('1 篇来源 · 1 条提示')
    expect(wrapper.findAll('.rag-trace__sources li')).toHaveLength(1)
    expect(wrapper.findAll('.rag-trace__notices p')).toHaveLength(1)
    expect(wrapper.text()).toContain('无结果')
    expect(wrapper.text()).not.toContain('已降级')
    expect(wrapper.text()).not.toContain('未填写')
    await wrapper.setProps({ retrieval: { ...retrieval, status: 'READY', warnings: [] } })
    expect(wrapper.get('summary').text()).toContain('已找到可用证据')
    expect(wrapper.get('summary').text()).not.toContain('条提示')
    expect(wrapper.find('.rag-trace__hint').exists()).toBe(false)
    wrapper.unmount()
  })
  it('冲突详情展示具体分歧和双方原文，来源无元数据仍可查看', () => {
    const right: KnowledgeHit = {
      ...hit,
      documentId: 'b',
      documentTitle: '新差旅制度',
      content: '每天八百元',
      source: null,
    }
    const wrapper = mount(RagTrace, {
      props: {
        retrieval: {
          ...retrieval,
          status: 'CONFLICT',
          hits: [hit, right],
          clarification: '请确认适用日期',
          disagreements: [
            { left: hit, right, leftQuote: '每天五百元', rightQuote: '每天八百元', reason: '同一费用限额不同' },
          ],
        },
      },
    })
    expect(wrapper.get('summary').text()).toContain('来源存在分歧')
    expect(wrapper.get('summary').text()).toContain('2 篇来源')
    expect(wrapper.get('.rag-trace__notices').text()).toContain('请确认适用日期')
    expect(wrapper.get('.rag-trace__conflict').text()).toContain('同一费用限额不同')
    expect(wrapper.findAll('blockquote cite').map((quote) => quote.text())).toEqual(['差旅制度', '新差旅制度'])
    expect(wrapper.findAll('blockquote p').map((quote) => quote.text())).toEqual(['每天五百元', '每天八百元'])
    expect(wrapper.get('blockquote small').text()).toContain('2026版 · 研发部')
    expect(wrapper.findAll('.rag-trace__sources li')).toHaveLength(2)
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
    expect(wrapper.text()).toContain('1 个来源')
    expect(wrapper.text()).not.toContain('知识库中没有找到相关内容')
    wrapper.unmount()
  })
  it('零来源澄清仍展示真实问题，不误报为空知识库', () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    useKnowledgeStore().answer = {
      question: '哪一版制度',
      answer: '请确认适用部门和制度版本。',
      answered: false,
      citations: [],
      retrieval: { ...retrieval, status: 'CLARIFY', hits: [], clarification: '请确认适用部门和制度版本。' },
    }
    const wrapper = mount(KnowledgeAskTab, { global: { plugins: [pinia] } })
    expect(wrapper.text()).toContain('请确认适用部门和制度版本。')
    expect(wrapper.text()).toContain('检索说明')
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
