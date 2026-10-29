import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import ChatSessionList from '@/components/chat/ChatSessionList.vue'
import type { ChatSession } from '@/types/api'

/**
 * 会话标题的行内重命名（2026-10-29）。
 *
 * <h2>守的 bug：点重命名图标后切到别的会话，那一行仍停在编辑态</h2>
 *
 * 用户原话：「我点击修改图标后，点击其他会话，点修改的那个会话还是修改标题的状态」。
 *
 * <p>根因不在退出逻辑，而在**输入框从来没拿到焦点**：
 *
 * 1. `ref="renameInput"` 写在 `v-for` **内部**。Vue 3 对这种写法会把同名 ref
 *    收集成**数组**，于是 `renameInput.value` 是 `[input]`，`.focus()` 不存在，
 *    运行时抛 `renameInput.value?.focus is not a function`；
 * 2. `?.` 只防 `null`、不防「有值但没这个方法」，所以它**没能兜住**这个错误；
 * 3. 没焦点 → `@blur` 永不触发 → 而 blur 是编辑态**唯一**的退出通道
 *    → 点别的会话时上一行永远停在编辑态，标题被一个空输入框顶掉。
 *
 * <p>实测对照（浏览器）：手动 `input.focus()` 后再点别的会话，编辑态正常退出（输入框归 0）；
 * 不手动聚焦则残留 1 个。这条对照是「blur 是唯一通道」的直接证据。
 *
 * <h2>为什么不只靠 mount 断言</h2>
 *
 * happy-dom 会「假装」聚焦（`document.activeElement` 不随 `focus()` 变化的行为并不稳定），
 * 所以「是否真的获得焦点」只能靠浏览器。这里守的是两件在 JS 层就能确定的事：
 * <ul>
 *   <li>**ref 不许收集成数组** —— 这是被修的 bug 本体；</li>
 *   <li>**切换 currentId 必须退出编辑态** —— 这条不依赖焦点，是状态一致性。</li>
 * </ul>
 */

const SOURCE = readFileSync(resolve(process.cwd(), 'src/components/chat/ChatSessionList.vue'), 'utf8')

function makeSession(id: string, title: string): ChatSession {
  return {
    conversationId: id,
    title,
    messageCount: 2,
    updatedAt: new Date().toISOString(),
  } as ChatSession
}

const SESSIONS = [makeSession('c-1', '第一个会话'), makeSession('c-2', '第二个会话'), makeSession('c-3', '第三个会话')]

/**
 * 挂载会话列表。
 *
 * <p>`AppPagination` 与 EP 组件都显式 stub / 注册：`vitest.config.ts` 刻意不装 unplugin
 * （自动注册在测试里没有意义，还会往源码注入未使用的自动导入语句），
 * 不 stub 会刷一堆 "Failed to resolve component"。
 */
function mountList(currentId: string | null = 'c-1') {
  return mount(ChatSessionList, {
    props: {
      sessions: SESSIONS,
      currentId,
      keyword: '',
      total: SESSIONS.length,
      page: 1,
      size: 20,
      loading: false,
    },
    global: {
      stubs: {
        AppPagination: true,
        ElCard: { template: '<div><slot name="header" /><slot /></div>' },
        ElInput: true,
        ElEmpty: true,
        ElButton: {
          props: ['icon', 'link', 'type', 'size'],
          template: '<button v-bind="$attrs"><slot /></button>',
        },
      },
    },
  })
}

/** 点某一行的重命名图标（每行第一个动作按钮） */
async function clickRename(wrapper: ReturnType<typeof mountList>, rowIndex: number) {
  const rows = wrapper.findAll('.chat-sessions__item')
  await rows[rowIndex].findAll('.chat-sessions__item-actions button')[0].trigger('click')
  await flushPromises()
}

describe('重命名：ref 不能被 v-for 收集成数组', () => {
  it('模板里不许再用 ref="renameInput"（v-for 内会被收集成数组）', () => {
    // 这是被修 bug 的字面形态。改回字符串 ref 就一定会复发：
    // `renameInput.value` 变成 `[input]`，`.focus()` 不存在。
    expect(SOURCE).not.toMatch(/\bref="renameInput"/)
  })

  it('输入框用函数式 ref，并传入本行 id 做「只认当前编辑行」的判断', () => {
    // 函数式 ref 是唯一能绕开 v-for 数组行为的写法；
    // 带上 conversationId 是因为 v-for 里每行都会调一次回调，
    // 不带 id 的话最后一行会把引用覆盖掉。
    expect(SOURCE).toMatch(/:ref="\(el\) => setRenameInput\(el, session\.conversationId\)"/)
    expect(SOURCE).toMatch(/function setRenameInput\(/)
    // 必须按 id 过滤：否则 v-for 的最后一行会赢，焦点跑到用户没在编辑的会话上
    expect(SOURCE).toMatch(/if \(conversationId !== editingId\.value\)/)
  })

  it('聚焦调用处不能假设拿到的是单个元素而不做类型兜底', () => {
    // 修复前是 `renameInput.value?.focus()`，`?.` 只防 null —— 拿到数组时照样抛。
    // 修复后引用被收敛成单个元素，`?.` 才是有效的兜底。
    const watchBlock = SOURCE.match(/watch\(editingId,[\s\S]*?\n\}\)/)?.[0] ?? ''
    expect(watchBlock).toContain('renameInput.value?.focus()')
    expect(watchBlock).toContain('renameInput.value?.select()')
    // 包在 nextTick 之后：输入框是 v-if 渲染的，DOM 未更新时 ref 还是 null
    expect(watchBlock).toContain('await nextTick()')
  })
})

describe('重命名：切换会话必须退出编辑态', () => {
  it('点重命名图标后该行出现输入框，原标题行消失', async () => {
    const wrapper = mountList()
    expect(wrapper.findAll('.chat-sessions__rename')).toHaveLength(0)

    await clickRename(wrapper, 0)

    expect(wrapper.findAll('.chat-sessions__rename')).toHaveLength(1)
    // 输入框与标题是 v-if / v-else 互斥的：同时存在就等于「标题丢了还在编辑」
    expect(wrapper.findAll('.chat-sessions__title-text')).toHaveLength(SESSIONS.length - 1)
    expect((wrapper.find('.chat-sessions__rename').element as HTMLInputElement).value).toBe('第一个会话')
  })

  it('currentId 变成别的会话时，编辑态必须自动退出（用户报的场景）', async () => {
    const wrapper = mountList('c-1')
    await clickRename(wrapper, 0)
    expect(wrapper.findAll('.chat-sessions__rename')).toHaveLength(1)

    // 模拟父组件把选中会话切到第二行
    await wrapper.setProps({ currentId: 'c-2' })
    await flushPromises()

    // 修复前这里会是 1 —— 那一行仍停在编辑态，标题被空输入框顶掉。
    expect(wrapper.findAll('.chat-sessions__rename')).toHaveLength(0)
    // 且**不能被当成「确认改名」**：切会话不该把用户没提交的内容落库
    expect(wrapper.emitted('rename')).toBeUndefined()
  })

  it('currentId 未变（点自己那一行）时不能误退出', async () => {
    const wrapper = mountList('c-1')
    await clickRename(wrapper, 0)

    // 同一个 id 再赋一次：既不该退出，也不该 emit rename
    await wrapper.setProps({ currentId: 'c-1' })
    await flushPromises()

    expect(wrapper.findAll('.chat-sessions__rename')).toHaveLength(1)
    expect(wrapper.emitted('rename')).toBeUndefined()
  })

  it('currentId 为 null（会话还没加载完）时不清编辑态', async () => {
    const wrapper = mountList('c-1')
    await clickRename(wrapper, 0)

    // 冷启动/刷新时 currentId 会短暂回到 null。此时若照判「不相等就清」，
    // 用户刚点开的重命名会被一次无关的重渲染打断。
    await wrapper.setProps({ currentId: null })
    await flushPromises()

    expect(wrapper.findAll('.chat-sessions__rename')).toHaveLength(1)
  })

  it('切会话退出后重新编辑另一行，输入框里是新行的标题而不是上一行的残留', async () => {
    const wrapper = mountList('c-1')
    await clickRename(wrapper, 0)
    // 在第一行里改成一半，然后切会话（不提交）
    await wrapper.find('.chat-sessions__rename').setValue('第一行改了一半')
    await wrapper.setProps({ currentId: 'c-2' })
    await flushPromises()

    // 换到第三行重新进入编辑：输入框必须显示第三行的原标题。
    // 若退出时只清 editingId、不清 editingValue，这里会显示「第一行改了一半」——
    // 用户看到的是一个不属于这一行的字符串，一按 Enter 就把它写进第三行。
    await clickRename(wrapper, 2)
    const input = wrapper.find('.chat-sessions__rename')
    expect((input.element as HTMLInputElement).value).toBe('第三个会话')
  })

  it('切会话不会把未提交的改动落库', async () => {
    const wrapper = mountList('c-1')
    await clickRename(wrapper, 0)
    await wrapper.find('.chat-sessions__rename').setValue('未提交的内容')

    await wrapper.setProps({ currentId: 'c-2' })
    await flushPromises()

    // 「切会话」不是「确认改名」。这条与上一条配对：
    // 上一条守「值不能串行」，这一条守「值不能偷偷落库」。
    expect(wrapper.emitted('rename')).toBeUndefined()
  })
})

describe('重命名：保存与取消的边界', () => {
  it('Enter 提交新标题，并退出编辑态', async () => {
    const wrapper = mountList()
    await clickRename(wrapper, 0)
    const input = wrapper.find('.chat-sessions__rename')

    await input.setValue('  新标题  ')
    await input.trigger('keydown.enter')
    await flushPromises()

    // trim 后提交：前导空格不该进库
    expect(wrapper.emitted('rename')?.[0]).toEqual([SESSIONS[0], '新标题'])
    expect(wrapper.findAll('.chat-sessions__rename')).toHaveLength(0)
  })

  it('空标题视为取消，不把会话名清空', async () => {
    const wrapper = mountList()
    await clickRename(wrapper, 0)

    await wrapper.find('.chat-sessions__rename').setValue('   ')
    await wrapper.find('.chat-sessions__rename').trigger('keydown.enter')
    await flushPromises()

    expect(wrapper.emitted('rename')).toBeUndefined()
    expect(wrapper.findAll('.chat-sessions__rename')).toHaveLength(0)
  })

  it('标题没变时不发 rename（避免无意义的落库）', async () => {
    const wrapper = mountList()
    await clickRename(wrapper, 0)

    await wrapper.find('.chat-sessions__rename').trigger('keydown.enter')
    await flushPromises()

    expect(wrapper.emitted('rename')).toBeUndefined()
  })

  it('Esc 取消后，紧随其后的 blur 不能再保存一次', async () => {
    const wrapper = mountList()
    await clickRename(wrapper, 0)
    const input = wrapper.find('.chat-sessions__rename')

    await input.setValue('不该被保存')
    await input.trigger('keydown.esc')
    await flushPromises()
    // 真实浏览器里 Esc 之后组件被卸载，卸载也会触发 blur —— 这里显式补一发，
    // 守的是 `editCancelled` 这道锁；没有它就会把取消的内容落库。
    await input.trigger('blur')
    await flushPromises()

    expect(wrapper.emitted('rename')).toBeUndefined()
  })

  it('点删除会先收起编辑态（两行不能同时展开）', async () => {
    const wrapper = mountList('c-1')
    await clickRename(wrapper, 0)

    const rows = wrapper.findAll('.chat-sessions__item')
    // 动作按钮顺序：重命名 / 导出 / 删除
    await rows[0].findAll('.chat-sessions__item-actions button')[2].trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.chat-sessions__rename')).toHaveLength(0)
    expect(wrapper.findAll('.chat-sessions__confirm')).toHaveLength(1)
  })

  it('同一时刻只能有一行处于编辑态', async () => {
    const wrapper = mountList()
    await clickRename(wrapper, 0)
    await clickRename(wrapper, 2)

    // 点击第二行的编辑图标时，第一行必须已经收起 ——
    // 否则侧栏里出现两个输入框，用户不知道该在哪个里打字。
    expect(wrapper.findAll('.chat-sessions__rename')).toHaveLength(1)
    expect((wrapper.find('.chat-sessions__rename').element as HTMLInputElement).value).toBe('第三个会话')
  })
})
