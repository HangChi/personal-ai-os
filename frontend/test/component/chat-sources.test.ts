import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import ChatSources from '@/components/chat/ChatSources.vue'
import ChatMessageItem from '@/components/chat/ChatMessageItem.vue'
import ChatStreamingItem from '@/components/chat/ChatStreamingItem.vue'
import { bindReplyRetrieval, restoreReplyRetrieval } from '@/lib/chat-retrieval'
import type { ChatCitation, ChatMessage, RagRetrieval } from '@/types/api'

const citation: ChatCitation = {
  index: 1,
  documentId: 'a',
  documentTitle: '差旅制度',
  filename: 'travel.md',
  chunkIndex: 0,
  content: '限额为五百元',
}
const retrieval: RagRetrieval = {
  hits: [],
  queries: ['差旅限额'],
  stages: [{ name: 'recall', status: 'OK', durationMs: 10, inputCount: 1, outputCount: 2 }],
  warnings: [],
  status: 'READY',
  disagreements: [],
  clarification: '',
}
const reply = (): ChatMessage => ({
  id: 2,
  sequenceNo: 2,
  role: 'ASSISTANT',
  content: '限额五百元 [1]',
  citations: [citation],
  attachments: [],
  createdAt: '2026-11-06T00:00:00Z',
})
afterEach(() => {
  document.body.innerHTML = ''
})

describe('统一来源入口', () => {
  it('普通零命中不显示空卡片，真实澄清即使没有阶段也可查看', async () => {
    const wrapper = mount(ChatSources, {
      props: { citations: [], retrieval: { ...retrieval, status: 'INSUFFICIENT' } },
      attachTo: document.body,
    })
    expect(wrapper.find('button').exists()).toBe(false)
    await wrapper.setProps({
      retrieval: { ...retrieval, status: 'CLARIFY', stages: [], clarification: '请选择制度版本' },
    })
    expect(wrapper.get('button').text()).toBe('检索说明')
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('请选择制度版本')
    expect(document.body.textContent).not.toContain('0 篇来源')
    wrapper.unmount()
  })
  it('按文档去重，抽屉合并片段与过程，危险 URL 不变成链接', async () => {
    const wrapper = mount(ChatSources, {
      attachTo: document.body,
      props: {
        retrieval,
        citations: [
          citation,
          { ...citation, index: 2, chunkIndex: 1 },
          { ...citation, documentId: 'b', index: 3, documentTitle: '不安全来源', url: 'javascript:alert(1)' },
        ],
      },
    })
    expect(wrapper.get('button').text()).toBe('2 个来源')
    expect(wrapper.get('button').attributes('aria-expanded')).toBe('false')
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(document.querySelectorAll('.chat-sources__source')).toHaveLength(2)
    expect(document.querySelectorAll('.chat-sources__excerpt')).toHaveLength(3)
    expect(document.querySelector('.rag-trace')).not.toBeNull()
    expect(document.querySelector('a')).toBeNull()
    wrapper.unmount()
  })
  it('安全网页同地址分组并保留外链防护', async () => {
    const wrapper = mount(ChatSources, {
      attachTo: document.body,
      props: {
        citations: [
          { ...citation, documentId: '', url: 'https://example.com/source' },
          { ...citation, index: 2, documentId: '', url: 'https://example.com/source' },
        ],
      },
    })
    expect(wrapper.get('button').text()).toBe('1 个来源')
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(document.querySelector('a')?.getAttribute('rel')).toBe('noopener noreferrer')
    wrapper.unmount()
  })
  it('来源在流式与完成态均位于回答正文之后', () => {
    const options = {
      global: { stubs: { MarkdownRenderer: { template: '<div class="answer">回答正文</div>' }, AppLogo: true } },
    }
    const stream = mount(ChatStreamingItem, {
      ...options,
      props: { content: '回答正文', hint: null, citations: [citation], retrieval },
    })
    const done = mount(ChatMessageItem, { ...options, props: { message: { ...reply(), retrieval }, toolTrail: [] } })
    for (const wrapper of [stream, done]) {
      expect(
        wrapper.get('.answer').element.compareDocumentPosition(wrapper.get('.chat-sources').element) &
          Node.DOCUMENT_POSITION_FOLLOWING,
      ).toBeTruthy()
      expect(wrapper.findAll('.chat-sources__trigger')).toHaveLength(1)
      wrapper.unmount()
    }
  })
})

describe('来源归属与恢复', () => {
  it('完成事件匹配最后回复才绑定，不把等待或失败轮次挂到旧回答', () => {
    const messages = [reply()]
    const event = (id: number, name: string, data: unknown) => ({ id, name, payload: JSON.stringify(data) })
    const run = {
      id: 'r',
      conversationId: 'c',
      status: 'COMPLETED' as const,
      events: [event(1, 'rag', retrieval), event(2, 'done', { content: messages[0]!.content })],
    }
    restoreReplyRetrieval(run, messages)
    expect(messages[0]!.retrieval).toEqual(retrieval)
    const old = reply()
    restoreReplyRetrieval({ ...run, events: [...run.events, event(3, 'reset', {})] }, [old])
    expect(old.retrieval).toBeUndefined()
    expect(bindReplyRetrieval([old], retrieval, '别的回答')).toBe(false)
    expect(bindReplyRetrieval([{ ...old, role: 'USER' }], retrieval, old.content)).toBe(false)
    expect(bindReplyRetrieval([old], retrieval, null)).toBe(false)
  })
})
