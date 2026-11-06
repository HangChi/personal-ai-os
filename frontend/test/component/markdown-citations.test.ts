import { mount } from '@vue/test-utils'
import { expect, it } from 'vitest'

import MarkdownRenderer from '@/components/MarkdownRenderer.vue'

it('previews only registered citations outside links and code and opens concrete web sources', async () => {
  const wrapper = mount(MarkdownRenderer, {
    attachTo: document.body,
    props: {
      content: '来自知识库 [1] 和网页 [2]，未知 [3]。 `[1]` [2](https://example.org)',
      citations: [
        { index: 1, documentTitle: '工作制度', filename: 'policy.md', chunkIndex: 2, content: '制度原文' },
        {
          index: 2,
          documentTitle: '网页标题',
          filename: 'example.com',
          chunkIndex: 0,
          content: '网页原文',
          url: 'https://example.com/article',
        },
      ],
    },
  })
  const buttons = wrapper.findAll('button.md-citation')
  expect(buttons.map((button) => button.text())).toEqual(['[1]', '[2]'])
  expect(wrapper.text()).toContain('[3]')
  expect(wrapper.get('code').text()).toBe('[1]')
  expect(document.body.textContent).not.toContain('制度原文')

  await buttons[0].trigger('pointerover')
  expect(document.body.querySelector('.md-source')?.textContent).toContain('制度原文')
  expect(document.body.querySelector('.md-source')?.textContent).toContain('policy.md · 第 3 段')
  await buttons[0].trigger('keydown', { key: 'Escape' })
  expect(document.body.querySelector('.md-source')).toBeNull()

  await buttons[1].trigger('click', { detail: 1 })
  const panel = document.body.querySelector('.md-source')!
  expect(panel.textContent).toContain('网页原文')
  expect(panel.querySelector('a')?.getAttribute('href')).toBe('https://example.com/article')
  expect(panel.querySelector('a')?.getAttribute('rel')).toBe('noopener noreferrer')
  wrapper.unmount()
})

it('keeps unsafe source URLs inert while making evidence reachable by keyboard', async () => {
  const wrapper = mount(MarkdownRenderer, {
    attachTo: document.body,
    props: {
      content: '来源 [1]。',
      citations: [
        {
          index: 1,
          documentTitle: '网页',
          filename: '',
          chunkIndex: 0,
          content: '<script>text</script>',
          url: 'javascript:alert(1)',
        },
      ],
    },
  })
  const button = wrapper.get('button.md-citation')
  await button.trigger('focusin')
  const panel = document.body.querySelector('.md-source')!
  expect(panel.textContent).toContain('<script>text</script>')
  expect(panel.querySelector('script, a')).toBeNull()
  expect(button.attributes('aria-expanded')).toBe('true')
  wrapper.unmount()
})

it('labels an unknown number and offers retrieved sources without implying a claim was cited', async () => {
  const wrapper = mount(MarkdownRenderer, {
    attachTo: document.body,
    props: {
      content: '回答 [9]。',
      citations: [
        { index: 1, documentTitle: '制度', filename: 'policy.md', chunkIndex: 0, content: '可核对的制度原文' },
      ],
    },
  })
  expect(wrapper.get('.md-citation-unavailable').text()).toBe('[9] 无对应来源')
  const sources = wrapper.get('.md-source-list')
  expect(sources.text()).toContain('本轮检索来源')
  await sources.get('button').trigger('click', { detail: 1 })
  expect(document.body.querySelector('.md-source')?.textContent).toContain('可核对的制度原文')
  wrapper.unmount()
})

it('统一来源入口存在时省略备用来源行，保留内联编号预览', async () => {
  const wrapper = mount(MarkdownRenderer, {
    props: {
      content: '没有编号的回复',
      hideSourceList: true,
      citations: [{ index: 1, documentTitle: '制度', filename: 'policy.md', chunkIndex: 0, content: '原文' }],
    },
  })
  expect(wrapper.find('.md-source-list').exists()).toBe(false)
  await wrapper.setProps({ content: '有依据的回复 [1]' })
  expect(wrapper.findAll('.md-citation')).toHaveLength(1)
  wrapper.unmount()
})
