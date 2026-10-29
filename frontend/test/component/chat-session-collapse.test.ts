import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { describe, expect, it } from 'vitest'
import { nextTick } from 'vue'

import ChatSessionList from '@/components/chat/ChatSessionList.vue'
import { useUiStore } from '@/stores/ui'
import type { ChatSession } from '@/types/api'

/**
 * 会话栏折叠（2026-10-30）。
 *
 * <h2>为什么要有这个功能</h2>
 * 会话栏固定占 260px。实测各视口下的主列剩余宽度：
 * 1440 → 920、1280 → 760、1100 → **580**、1024 → **504**。
 * 气泡的设计宽度是 680，于是 1100 被压到 502、1024 被压到 426（只剩 63%）。
 * 折叠后会话栏降到 48px，主列净增 212px：1100 回到 792（气泡恢复 680）、
 * 1024 回到 716（气泡 638）。
 *
 * <h2>为什么守这些点</h2>
 * 折叠是「两种完全不同的 DOM」，最容易出错的不是宽度，而是：
 * <ul>
 *   <li>用 `v-show` 装折叠态 —— 留下一个零内容的隐藏盒子，两态元素叠在一起；</li>
 *   <li>折叠后用户失去回来的入口（只剩图标没有「展开」按钮）；</li>
 *   <li>折叠轨上的按钮被 EP 的横向相邻按钮间距规则推歪（已经踩过，见下）。</li>
 * </ul>
 */

const LIST_SRC = readFileSync(resolve(process.cwd(), 'src/components/chat/ChatSessionList.vue'), 'utf8')
const VIEW_SRC = readFileSync(resolve(process.cwd(), 'src/views/ChatView.vue'), 'utf8')

function makeSession(id: string, title: string): ChatSession {
  return {
    conversationId: id,
    title,
    messageCount: 2,
    updatedAt: new Date().toISOString(),
  } as ChatSession
}

const SESSIONS = [
  makeSession('c-1', '期中复习提纲'),
  makeSession('c-2', 'Excel 算平均分'),
  makeSession('c-3', '家长会发言稿'),
]

/**
 * 找出「包含指定选择器」的那个 `@media (max-width: 900px)` 块。
 *
 * <p>不能简单 `match(/@media ... \{([\s\S]*?)\n\}\n/)` —— 非贪婪匹配到**第一个**
 * 闭合的 `}`，而块内第一个规则就已经有 `}`，于是只会抓到几行。更糟的是组件里
 * 有两个 ≤900px 块，抓到哪个全看顺序，断言可能在检查一段与它无关的 CSS。
 *
 * <p>做法：从每个 `@media` 起点往后按大括号配平，拿到完整块，再挑含关键字的那个。
 */
function mediaBlockWith(source: string, needle: string, query = 'max-width: 900px'): string {
  const blocks: string[] = []
  let from = 0
  for (;;) {
    const at = source.indexOf(`@media (${query})`, from)
    if (at === -1) {
      break
    }
    let depth = 0
    let i = source.indexOf('{', at)
    const start = i
    for (; i < source.length; i++) {
      if (source[i] === '{') depth++
      else if (source[i] === '}') {
        depth--
        if (depth === 0) break
      }
    }
    blocks.push(source.slice(start, i + 1))
    from = i + 1
  }
  return blocks.find((block) => block.includes(needle)) ?? ''
}

/** 挂载会话列表；`collapsed` 是必填 prop，这里显式给值而不是靠 `undefined` 恰好等于 false */
function mountList(options: { currentId?: string | null; collapsed?: boolean; sessions?: ChatSession[] } = {}) {
  const { currentId = 'c-1', collapsed = false, sessions = SESSIONS } = options
  return mount(ChatSessionList, {
    props: {
      sessions,
      currentId,
      keyword: '',
      total: sessions.length,
      page: 1,
      size: 20,
      loading: false,
      collapsed,
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

describe('折叠态与展开态是两套 DOM', () => {
  it('折叠时渲染竖轨，展开态的内容（列表/搜索/分页）整体不进入 DOM', () => {
    const wrapper = mountList({ collapsed: true })

    expect(wrapper.find('.chat-sessions__rail').exists()).toBe(true)
    // 关键判据不是「看不见」而是「不存在」：
    // 用 v-show 收起的话这些元素仍在 DOM 里，宽度与可点区都会留下残影，
    // 读屏与 Ctrl+F 也能找到它们 —— 与「折叠」的语义矛盾。
    expect(wrapper.findAll('.chat-sessions__item')).toHaveLength(0)
    expect(wrapper.find('.chat-sessions__filter').exists()).toBe(false)
    expect(wrapper.find('.chat-sessions__scroll').exists()).toBe(false)
  })

  it('展开时不渲染竖轨，列表照常出现', () => {
    const wrapper = mountList({ collapsed: false })

    expect(wrapper.find('.chat-sessions__rail').exists()).toBe(false)
    expect(wrapper.findAll('.chat-sessions__item')).toHaveLength(SESSIONS.length)
  })

  it('模板里折叠分支用 v-if，不许用 v-show', () => {
    // 源码断言是必要的补充：两态的差异太大（收起后连 <ul> 都没有），
    // 用 v-show 无法把宽度降下来，而且这条退化在 mount 断言里
    // 只会表现为「元素存在但 CSS 不可见」，happy-dom 不做布局，查不出来。
    expect(LIST_SRC).toMatch(/<el-card v-if="collapsed" class="chat-sessions chat-sessions--rail"/)
    expect(LIST_SRC).toMatch(/<el-card v-else class="chat-sessions"/)
    expect(LIST_SRC).not.toMatch(/v-show="collapsed"/)
    expect(LIST_SRC).not.toMatch(/v-show="!collapsed"/)
  })
})

describe('折叠轨上的操作都要有出口', () => {
  it('「展开」按钮 emit toggle-collapse(false)', async () => {
    const wrapper = mountList({ collapsed: true })
    const expand = wrapper.findAll('.chat-sessions__rail-btn')[0]

    expect(expand.attributes('title')).toBe('展开会话列表')
    await expand.trigger('click')

    // 传 false 而不是 `!collapsed`：箭头函数里取反会读到 props 的旧值，
    // 而父组件才是状态的唯一所有者，事件只描述「我想要什么」。
    expect(wrapper.emitted('toggle-collapse')?.[0]).toEqual([false])
  })

  it('「新建」按钮在折叠态仍可用（emit create）', async () => {
    const wrapper = mountList({ collapsed: true })
    const create = wrapper.findAll('.chat-sessions__rail-btn')[1]

    expect(create.attributes('title')).toBe('新建会话')
    await create.trigger('click')

    // 折叠不该让「新建会话」消失：它是本页最高频的动作之一，
    // 收起来还得先展开一次才能用，折叠就变成了惩罚。
    expect(wrapper.emitted('create')).toHaveLength(1)
  })

  it('展开态头部有「收起」按钮，emit toggle-collapse(true)', async () => {
    const wrapper = mountList({ collapsed: false })
    const collapse = wrapper.find('.chat-sessions__collapse')

    expect(collapse.exists()).toBe(true)
    expect(collapse.attributes('title')).toBe('收起会话列表')
    await collapse.trigger('click')

    expect(wrapper.emitted('toggle-collapse')?.[0]).toEqual([true])
  })

  it('纯图标按钮必须有 aria-label（没有可读文本可依赖）', () => {
    const wrapper = mountList({ collapsed: true })
    for (const button of wrapper.findAll('.chat-sessions__rail-btn')) {
      expect(button.attributes('aria-label')).toBeTruthy()
    }
    // 展开态那个也一样
    const expanded = mountList({ collapsed: false })
    expect(expanded.find('.chat-sessions__collapse').attributes('aria-label')).toBe('收起会话列表')
  })
})

describe('折叠轨上仍能看出「我在哪」「有几个会话」', () => {
  it('显示会话总数', () => {
    const wrapper = mountList({ collapsed: true })
    expect(wrapper.find('.chat-sessions__rail-count').text()).toBe(String(SESSIONS.length))
    expect(wrapper.find('.chat-sessions__rail-count').attributes('title')).toBe(`共 ${SESSIONS.length} 个会话`)
  })

  it('显示当前会话的首字', () => {
    const wrapper = mountList({ collapsed: true, currentId: 'c-2' })
    // 'Excel 算平均分' 的首字
    expect(wrapper.find('.chat-sessions__rail-active').text()).toBe('E')
    // 首字标记必须带上完整标题的 title：一个字符本身不解释任何事
    expect(wrapper.find('.chat-sessions__rail-active').attributes('title')).toContain('Excel 算平均分')
  })

  it('没有当前会话时不渲染首字标记（而不是渲染一个空格子）', () => {
    const wrapper = mountList({ collapsed: true, currentId: null })
    expect(wrapper.find('.chat-sessions__rail-active').exists()).toBe(false)
  })

  it('当前会话的 id 不在本页会话里时也不渲染', () => {
    const wrapper = mountList({ collapsed: true, currentId: 'c-not-on-this-page' })
    expect(wrapper.find('.chat-sessions__rail-active').exists()).toBe(false)
  })

  it('标题全空白时退回一个通用标记而不是空串', () => {
    const wrapper = mountList({ collapsed: true, currentId: 'c-1', sessions: [makeSession('c-1', '   ')] })
    // 空串会让圆形底色变成一个没有内容的空圆 —— 看起来像渲染失败。
    expect(wrapper.find('.chat-sessions__rail-active').text()).toBe('·')
  })
})

describe('折叠轨的排版细节（都是踩过的坑）', () => {
  it('轨上按钮显式把 margin-left 归零', () => {
    // EP 有 `.el-button + .el-button { margin-left: 8px }` —— 为**横向并排**写的。
    // 折叠轨是纵向堆叠，这条规则照样命中，第二个按钮被推右 8px。
    // 实测两个 32px 按钮中心一个在 260、一个在 264（4px 偏移），
    // 叠在同样居中的计数与首字上就看得出来整列是歪的。
    const block = LIST_SRC.match(/\.chat-sessions__rail-btn \{([^}]*)\}/)?.[1] ?? ''
    expect(block).toContain('margin-left: 0')
    // 宽度必须显式给：link 按钮不写 width 时宽度由图标撑开（16px），
    // 热区会从 32×32 退化成 16×32 的窄条。
    expect(block).toContain('width: 32px')
    expect(block).toContain('height: 32px')
  })

  it('折叠轨的卡片要压掉默认内边距（48px 里塞不下 20px padding + 按钮）', () => {
    expect(LIST_SRC).toMatch(/\.chat-sessions--rail :deep\(\.el-card__body\) \{[\s\S]*?padding: var\(--os-space-2\) 0;/)
  })

  it('竖轨是纵向 flex，并由父层决定宽度（自己不许写 width）', () => {
    const block = LIST_SRC.match(/\.chat-sessions__rail \{([^}]*)\}/)?.[1] ?? ''
    expect(block).toContain('flex-direction: column')
    expect(block).toContain('align-items: center')
    // 宽度若在这里写死，ChatView 的 grid 列宽就与它打架，
    // 出现「列 48px、卡片 52px」的横向溢出。
    expect(block).not.toMatch(/(^|\s)width:/)
  })
})

describe('ChatView 的列宽契约', () => {
  it('列宽由 --os-session-panel 单一变量驱动，两态只改它', () => {
    expect(VIEW_SRC).toMatch(/grid-template-columns: var\(--os-session-panel\) 1fr/)
    expect(VIEW_SRC).toMatch(/\.chat \{[^}]*--os-session-panel: 260px;/)
    expect(VIEW_SRC).toMatch(/\.chat--session-collapsed \{\s*--os-session-panel: 48px;/)
  })

  it('过渡做在 grid-template-columns 上，不是 width', () => {
    // 改 `width` 对 `1fr` 那一列**无效**：主列不会跟着变宽，
    // 结果是「会话栏窄了但主列没动」的错位。列宽是 grid 属性。
    expect(VIEW_SRC).toMatch(/transition: grid-template-columns var\(--os-duration-fast\)/)
    expect(VIEW_SRC).not.toMatch(/transition: width /)
  })

  it('窄屏（≤900px）要关掉过渡：单列布局下折叠不改变布局方向', () => {
    const media = VIEW_SRC.match(/@media \(max-width: 900px\) \{([\s\S]*?)\n\}\n/)?.[1] ?? ''
    expect(media).toContain('transition: none')
  })

  /*
   * 这一条是本文件里**唯一一条真出过事的断言**（2026-10-30）。
   *
   * <p>窄屏横条的样式最初写在 ChatView 的媒体查询里，我写的断言是
   * 「那段媒体查询里有 `flex-direction: row`」—— **它一直是绿的**，
   * 而那几条规则从未生效过。因为 scoped 的 scope id 只加到子组件的
   * **根元素**上，`.chat-sessions__rail`（子组件内部元素）上根本没有
   * 父组件的 `[data-v-parent]`，选择器永不匹配。
   *
   * <p>实测退化（860px 折叠态）：`dir=column`、卡片高 156px、
   * 按钮被居中到 `l:526` —— 一条占满整行的图标高条。
   *
   * <p>所以判据不能是「某段文字存在」，而是「这段样式写在**拥有这些元素**的组件里」，
   * 外加一条反向断言：父组件不许出现指向子组件内部类的选择器。
   * 这类错误 `type-check` / `lint` / `build` 全绿，只有量 DOM 才看得见。
   */
  it('窄屏横条的样式必须写在 ChatSessionList 里（scoped 穿不过子组件内部）', () => {
    // 注意这里不能用「匹配第一个 @media」的写法：组件里有**两个** ≤900px 媒体查询
    // （列表限高、折叠轨横条）。按关键字找包含竖轨的那个，否则断言会静默检查错的块
    // —— 这正是上一版假错的一种形态（当时检查了 ChatView 里那段，但那几行根本没生效）。
    const media = mediaBlockWith(LIST_SRC, '.chat-sessions__rail')
    expect(media).toBeTruthy()
    expect(media).toContain('flex-direction: row')
    // 撑杆在横条里必须**不存在**（display:none），不是 `flex: 0`：
    // 0 宽元素仍是 flex 项，仍吃掉一个 8px 的 gap —— 实测左右边距差正好 8px。
    expect(media).toMatch(/\.chat-sessions__rail-spacer \{\s*display: none;/)
    // 计数靠右收尾
    expect(media).toMatch(/\.chat-sessions__rail-count \{[\s\S]*?margin-left: auto;/)
  })

  it('父组件不许用 scoped 选择器指向子组件内部类（那永远不会命中）', () => {
    // 反向守卫：把子组件内部的 BEM 子元素写在 ChatView 的 <style scoped> 里，
    // 编译后是 `[data-v-view] .chat-sessions__rail`，而那个属性只加在
    // <ChatSessionList> 的根元素上 —— 内部元素上不存在。
    // 换句话说：这些规则是**死代码**，却看起来像在工作（同批里作用在根元素上的
    // 规则正常生效，于是像「媒体查询没命中」）。
    for (const inner of [
      'chat-sessions__rail ',
      'chat-sessions__rail-',
      'chat-sessions__item',
      'chat-sessions__filter',
    ]) {
      // `:deep()` 是唯一合法的穿透方式；不带 :deep 的引用一律视为 bug
      const offenders = VIEW_SRC.split('\n').filter((line) => line.includes(inner) && !line.includes(':deep('))
      // 只数 CSS 选择器行（模板里的类名不算）
      const cssOffenders = offenders.filter((line) => /^\s*\.[\w-]+/.test(line) || /^\s*&/.test(line))
      expect(cssOffenders).toEqual([])
    }
  })

  it('父组件把折叠状态与切换事件都接上了', () => {
    expect(VIEW_SRC).toMatch(/:collapsed="sessionPanelCollapsed"/)
    // 事件直接赋值而不是取反：状态归 store 所有，事件只带目标值。
    expect(VIEW_SRC).toMatch(/@toggle-collapse="sessionPanelCollapsed = \$event"/)
  })

  /*
   * 同一个元素的尺寸只能有一处声明（2026-10-30 实测）。
   *
   * <p>会话卡片的限高一度在**两个文件**里各写一份：ChatView 的 200px 与
   * ChatSessionList 的 300px，指向同一个根元素、特异性也相同 —— 谁赢取决于
   * 打包后的 CSS 顺序。实测赢的是 200px，那条 300px 从未生效，
   * 是一段「看起来在管事、实际没有」的死代码。
   *
   * <p>这类冲突最容易被误读：改 300 没反应就以为是「媒体查询没命中」，
   * 于是继续在错误的文件里加规则。判据要落在「只有一处」。
   */
  it('会话卡片的窄屏限高只声明一次（两处同特异性 → 谁赢看打包顺序）', () => {
    // 注意：判据不能是 `/max-height/` —— 注释里为了说明这段历史也写了这个词，
    // 直接用会匹配到注释，于是删掉代码后测试仍然红（我第一次就踩了：
    // 代码已删干净，红的是注释）。先剥掉注释再判。
    const stripComments = (css: string) => css.replace(/\/\*[\s\S]*?\*\//g, '')
    const listCss = stripComments(LIST_SRC)
    expect(listCss).not.toMatch(/\.chat-sessions \{[^}]*max-height/)
    // 归 ChatView 管：它只被 ChatView 使用一次，根元素高度是页面级约束
    expect(stripComments(VIEW_SRC)).toMatch(/\.chat-sessions \{\s*max-height: 200px;/)
  })
})

describe('折叠状态要跨刷新保持', () => {
  it('写进 localStorage（os. 前缀，重置偏好时会被清掉）并读回', async () => {
    setActivePinia(createPinia())
    localStorage.clear()

    const store = useUiStore()
    // 默认必须是展开：会话列表是对话页的主导航，
    // 首次进入就收起来等于让用户找不到自己的会话。
    expect(store.sessionPanelCollapsed).toBe(false)

    store.sessionPanelCollapsed = true
    await nextTick()
    await flushPromises()

    expect(localStorage.getItem('os.chat.sessionPanel')).toBe('collapsed')

    // 新建一个 store 实例（模拟刷新）：初值必须从 localStorage 恢复
    setActivePinia(createPinia())
    expect(useUiStore().sessionPanelCollapsed).toBe(true)
  })

  it('无法识别的旧值回落到「展开」，不是抛错也不是收起', () => {
    setActivePinia(createPinia())
    localStorage.setItem('os.chat.sessionPanel', 'auto')

    // 存字符串而不是 '1'/'0'，就是为了将来能加第三态（如跟随视口宽度）而不必迁移数据；
    // 代价是必须容忍未知值 —— 按 `=== 'collapsed'` 判等，其余一律展开。
    expect(useUiStore().sessionPanelCollapsed).toBe(false)
  })

  it('与侧栏折叠是两个独立开关（互不影响）', async () => {
    setActivePinia(createPinia())
    localStorage.clear()

    const store = useUiStore()
    store.sessionPanelCollapsed = true
    await nextTick()

    // 合并成一个「全局折叠」会让「去任务页收起侧栏、回对话页」意外改版式：
    // 侧栏是全站导航，会话栏只属于对话页。
    expect(store.navCollapsed).toBe(false)
    expect(localStorage.getItem('os.nav.collapsed')).toBeNull()
    expect(localStorage.getItem('os.chat.sessionPanel')).toBe('collapsed')
  })
})
