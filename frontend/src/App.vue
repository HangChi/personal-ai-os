<script setup lang="ts">
/**
 * 应用根组件：侧栏导航 + 顶栏（搜索 / 主题 / 用户）+ 路由出口。
 * 业务状态一律放在 Pinia store 中，不做跨组件透传。
 *
 * 布局演进：
 * - 阶段 0-17：顶部横向导航。7 个模块到顶后顶栏拥挤（375px 直接溢出），
 *   窄屏只能整个藏进抽屉。
 * - 阶段 18 追加（2026-10-26）：横向菜单改为**左侧导航栏**。顶栏只剩
 *   折叠按钮 / 搜索 / 主题 / 用户。侧栏可折叠为纯图标（64px），
 *   选择持久化到 localStorage；<768px 侧栏隐藏，走汉堡 + 抽屉（原有机制）。
 *   模块以后加到 10 个也不再挤——这是侧栏相对顶栏的核心收益。
 */
import {
  Calendar,
  ChatDotRound,
  DataLine,
  Expand,
  Fold,
  HomeFilled,
  Memo,
  Reading,
  Search,
  Setting,
  SwitchButton,
  Tickets,
  User,
} from '@element-plus/icons-vue'
import { storeToRefs } from 'pinia'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { useAuthStore } from '@/stores/auth'
import { useUiStore } from '@/stores/ui'

const authStore = useAuthStore()
const uiStore = useUiStore()
const { authEnabled, username } = storeToRefs(authStore)
const { navCollapsed } = storeToRefs(uiStore)
const route = useRoute()
const router = useRouter()

/** 登录页不显示导航：点任何一个都会立刻被守卫弹回登录页，只是噪音 */
const isLoginPage = computed(() => route.name === 'login')

/**
 * 导航分组（阶段 23）。
 *
 * 改造前 8 项平铺在一条菜单里，「设置」和「对话」视觉权重完全相同。
 * 分组后「日常使用」与「查看 / 配置」分开，9 项以上也不会糊成一片。
 * index 与 el-menu 的 router 模式对齐（即路由 path）。
 */
const navGroups = [
  {
    key: 'workspace',
    label: '工作区',
    items: [
      { index: '/', label: '总览', icon: HomeFilled },
      { index: '/chat', label: '对话', icon: ChatDotRound },
      { index: '/tasks', label: '任务', icon: Tickets },
      { index: '/knowledge', label: '知识库', icon: Reading },
      { index: '/calendar', label: '日程', icon: Calendar },
    ],
  },
  {
    key: 'system',
    label: '系统',
    items: [
      { index: '/memory', label: '记忆', icon: Memo },
      { index: '/activity', label: '活动', icon: DataLine },
      { index: '/settings', label: '设置', icon: Setting },
    ],
  },
]

/** 抽屉里用扁平列表（抽屉空间小，分组标题反而占位） */
const navItems = navGroups.flatMap((group) => group.items)

/** 顶栏全局搜索词（阶段 17）：回车后跳到结果页，关键词走 query 而非 store ——
 *  直接分享 /search?q=xxx 也该能用 */
const searchKeyword = ref('')
const searchInput = ref<InstanceType<typeof HTMLInputElement> | null>(null)

/** 窄屏抽屉导航（阶段 18）：<768px 侧栏隐藏，改汉堡 + 抽屉 */
const drawerOpen = ref(false)

/** macOS 用 ⌘K，其余平台显示 Ctrl K（改造前一律写 "Ctrl K"，Mac 用户看着别扭） */
const isMac = computed(() => typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform))
const searchHint = computed(() => (isMac.value ? '⌘ K' : 'Ctrl K'))

/** 用户名首字作头像文字（无头像上传能力，用首字母是最低成本的「有身份」） */
const userInitial = computed(() => (username.value ? username.value.slice(0, 1).toUpperCase() : '?'))

function runGlobalSearch(): void {
  const term = searchKeyword.value.trim()
  if (!term) {
    return
  }
  void router.push({ path: '/search', query: { q: term } })
}

/**
 * Ctrl/Cmd+K 聚焦搜索框（FR-004）。
 * 挂在 window 上而不是 el-input 的 keydown：焦点在页面任意处（包括消息流里）
 * 时都要能唤起，这也是命令面板式交互的肌肉记忆。
 */
function handleGlobalKeydown(event: KeyboardEvent): void {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    if (!isLoginPage.value) {
      searchInput.value?.focus()
    }
  }
}

onMounted(() => window.addEventListener('keydown', handleGlobalKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', handleGlobalKeydown))

function toggleNav(): void {
  navCollapsed.value = !navCollapsed.value
}

async function handleLogout(): Promise<void> {
  await authStore.logout()
  await router.push({ name: 'login' })
}
</script>

<template>
  <!--
    app-shell--fixed：对话页专用。外壳锁死为视口高且不滚动，
    于是「长高」的压力全部传给消息区内部的 overflow-y —— 页面本身永远不出现滚动条。
    其他页面（任务/知识库/设置）不加这个类，保持整页滚动。
  -->
  <el-container :class="['app-shell', { 'app-shell--fixed': route.meta.fullWidth }]">
    <!-- 侧栏：宽屏主导航；登录页不渲染 -->
    <el-aside
      v-if="!isLoginPage"
      class="app-shell__aside"
      :width="navCollapsed ? 'var(--os-sidebar-width-collapsed)' : 'var(--os-sidebar-width)'"
    >
      <router-link to="/" class="app-shell__brand" title="返回总览">
        <span class="app-shell__logo">AI</span>
        <span v-show="!navCollapsed" class="app-shell__title">Personal AI OS</span>
      </router-link>

      <el-menu class="app-shell__nav" :collapse="navCollapsed" :default-active="route.path" :router="true">
        <!-- 分组导航：折叠态下不显示分组标题（64px 放不下，且图标本身已足够） -->
        <template v-for="group in navGroups" :key="group.key">
          <li v-show="!navCollapsed" class="app-shell__group-label">{{ group.label }}</li>
          <el-menu-item v-for="item in group.items" :key="item.index" :index="item.index" :title="item.label">
            <el-icon><component :is="item.icon" /></el-icon>
            <template #title>{{ item.label }}</template>
          </el-menu-item>
        </template>
      </el-menu>

      <div class="app-shell__aside-footer">
        <el-button link class="app-shell__collapse" :title="navCollapsed ? '展开侧栏' : '收起侧栏'" @click="toggleNav">
          <el-icon :size="16"><Expand v-if="navCollapsed" /><Fold v-else /></el-icon>
          <span v-show="!navCollapsed">收起侧栏</span>
        </el-button>
      </div>
    </el-aside>

    <el-container class="app-shell__body">
      <el-header class="app-shell__header">
        <!-- 登录页：顶栏只放品牌与主题，其余空间留白 -->
        <template v-if="isLoginPage">
          <div class="app-shell__brand app-shell__brand--header">
            <span class="app-shell__logo">AI</span>
            <span class="app-shell__title">Personal AI OS</span>
          </div>
          <div class="app-shell__header-spacer" />
          <ThemeToggle />
        </template>

        <template v-else>
          <!-- 两个按钮按视口二选一：窄屏开抽屉，宽屏折叠侧栏 -->
          <el-button class="app-shell__burger" link aria-label="打开导航菜单" @click="drawerOpen = true">
            <el-icon :size="20"><Fold /></el-icon>
          </el-button>
          <el-button
            class="app-shell__collapse-top"
            link
            :aria-label="navCollapsed ? '展开侧栏' : '收起侧栏'"
            @click="toggleNav"
          >
            <el-icon :size="18"><Expand v-if="navCollapsed" /><Fold v-else /></el-icon>
          </el-button>

          <div class="app-shell__header-spacer" />

          <!-- 搜索：胶囊形 + 右侧快捷键提示（改造前是裸 el-input，看不出可 Ctrl K） -->
          <div class="app-shell__search">
            <el-input
              ref="searchInput"
              v-model="searchKeyword"
              class="app-shell__search-input"
              clearable
              placeholder="搜索任务、对话、日程…"
              :prefix-icon="Search"
              @keydown.enter="runGlobalSearch"
              @clear="searchKeyword = ''"
            />
            <kbd v-show="!searchKeyword" class="app-shell__kbd">{{ searchHint }}</kbd>
          </div>

          <div class="app-shell__right">
            <ThemeToggle />
            <!-- 用户区改头像 + 下拉：改造前是「用户名 + 登出」两个裸文本，权重很低 -->
            <el-dropdown v-if="authEnabled && username" trigger="click">
              <button type="button" class="app-shell__user" :title="username">
                <span class="app-shell__avatar">{{ userInitial }}</span>
                <span class="app-shell__user-name">{{ username }}</span>
              </button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item disabled>
                    <span class="app-shell__user-email">已登录：{{ username }}</span>
                  </el-dropdown-item>
                  <el-dropdown-item divided @click="handleLogout">
                    <el-icon><SwitchButton /></el-icon>
                    登出
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <span v-else class="app-shell__guest" title="未开启访问控制">
              <el-icon :size="16"><User /></el-icon>
            </span>
          </div>
        </template>
      </el-header>

      <el-main :class="['app-shell__main', { 'app-shell__main--wide': route.meta.fullWidth }]">
        <router-view v-slot="{ Component }">
          <transition name="page" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </el-main>

      <el-footer class="app-shell__footer">
        <span>Personal AI OS</span>
        <span class="app-shell__footer-dot">·</span>
        <span>Apache-2.0</span>
        <span class="app-shell__footer-dot">·</span>
        <span>本地单用户</span>
      </el-footer>
    </el-container>

    <!-- 窄屏导航抽屉（<768px 才看得到） -->
    <el-drawer v-model="drawerOpen" title="导航" direction="ltr" size="240px">
      <el-menu :default-active="route.path" :router="true" @select="drawerOpen = false">
        <el-menu-item v-for="item in navItems" :key="item.index" :index="item.index">
          <el-icon class="app-shell__nav-icon"><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </el-menu-item>
      </el-menu>
    </el-drawer>
  </el-container>
</template>

<style scoped>
/*
 * 外壳只保留 min-height：el-container 本身就是 flex 容器，
 * 再声明一次 display 会把 EP 的方向判断覆盖掉，反而把页面撑出滚动条（实测）。
 * 真正要撑满的是 main —— 见下面的 flex:1。
 */
.app-shell {
  min-height: 100vh;
}

/*
 * 对话页：外壳锁死视口高度且自身不滚动。
 *
 * 只写 min-height 的话，外壳会随内容长高（实测 14 条消息时被撑到 1857px），
 * 于是整页出现滚动条，而消息区自己的 overflow-y 永远没机会触发。
 * 锁死高度后，长高的压力沿 flex 链一路传到 .chat__messages，由它内部滚动。
 */
.app-shell--fixed {
  height: 100dvh;
  overflow: hidden;
}

/* ---- 侧栏 ---- */

/*
 * 侧栏锁在视口高度内并常驻（sticky）。
 *
 * 改造前只有 flex 列布局、没有高度约束：首页内容 1284px 高时侧栏被一起撑到
 * 1284px（实测），页面向下滚动导航就滑出视口 —— 长长的列表页里想切页得先滚回顶部。
 * 对话页因为外壳锁死 100dvh 侥幸没暴露这个问题。
 *
 * overflow: hidden 原本是为了折叠动画（宽度收窄时不溢出），
 * 但同时会裁掉菜单——菜单自己需要滚动，所以把滚动下沉到 .app-shell__nav。
 */
.app-shell__aside {
  position: sticky;
  top: 0;
  display: flex;
  flex-direction: column;
  height: 100vh;
  height: 100dvh;
  border-right: 1px solid var(--el-border-color-lighter);
  background: var(--el-bg-color);
  /* 折叠/展开的宽度过渡 */
  transition: width var(--os-duration-normal) var(--os-ease);
  overflow: hidden;
}

/*
 * 品牌区高度必须与 --os-header-height 严格一致。
 * 改造前这里是 56px、顶栏 60px：两条水平线在拐角处错开 4px，
 * 表现为侧栏右边框与顶栏下边框接不上（截图里是一道很轻的台阶，但确实在）。
 */
.app-shell__brand {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
  flex: none;
  height: var(--os-header-height);
  padding: 0 var(--os-space-4);
  border-bottom: 1px solid transparent;
  text-decoration: none;
  color: inherit;
}

.app-shell__brand--header {
  padding: 0;
  border-bottom: none;
}

/*
 * Logo：主色渐变 + 内高光。纯色方块在浅色侧栏上略显扁平，
 * 一层 135° 渐变足以让它看起来像个「应用图标」而不是「一个蓝色 div」。
 */
.app-shell__logo {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: var(--os-radius-md);
  background: linear-gradient(135deg, var(--el-color-primary) 0%, var(--el-color-primary-dark-2) 100%);
  box-shadow: 0 1px 3px 0 rgb(79 70 229 / 32%);
  color: #fff;
  font-size: var(--os-font-xs);
  font-weight: 700;
  letter-spacing: 0.2px;
  flex: none;
}

.app-shell__title {
  font-size: var(--os-font-md);
  font-weight: 600;
  letter-spacing: -0.1px;
  white-space: nowrap;
}

/* 垂直菜单：铺满侧栏中部，可滚动；EP 默认底色去掉，用侧栏自己的 */
.app-shell__nav {
  flex: 1;
  overflow-y: auto;
  border-right: none;
  background: transparent;
  padding: var(--os-space-2) 0;
}

/* 分组标题：小号、全大写间距、不可点 —— 只做视觉分隔 */
.app-shell__group-label {
  margin: var(--os-space-3) 0 var(--os-space-1);
  padding: 0 var(--os-space-2) 0 calc(var(--os-space-4) + var(--os-space-2));
  color: var(--el-text-color-placeholder);
  font-size: var(--os-font-xs);
  font-weight: 500;
  letter-spacing: 0.4px;
  list-style: none;
  white-space: nowrap;
}

/* 第一组的标题不需要上方留白（品牌区下方已有 padding） */
.app-shell__nav > .app-shell__group-label:first-child {
  margin-top: var(--os-space-1);
}

.app-shell__nav :deep(.el-menu-item) {
  /* 高度与 line-height 都在 theme.css 里锁成 40px；
     这里只管外边距与圆角，避免两处各写一个高度导致再次错位 */
  margin: 2px var(--os-space-2);
  border-radius: var(--os-radius-md);
  color: var(--el-text-color-regular);
}

.app-shell__nav :deep(.el-menu-item:hover) {
  background: var(--el-fill-color-light);
  color: var(--el-text-color-primary);
}

.app-shell__nav :deep(.el-menu-item.is-active) {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-weight: 500;
}

/* 折叠态：图标居中、去掉左右外边距（64px 里只剩图标） */
.app-shell__nav :deep(.el-menu--collapse .el-menu-item) {
  margin-left: var(--os-space-2);
  margin-right: var(--os-space-2);
}

.app-shell__aside-footer {
  flex: none;
  padding: var(--os-space-2);
  border-top: 1px solid var(--el-border-color-lighter);
}

/*
 * 折叠按钮：宽度占满侧栏，但高度得单独给。
 * link 款按钮的 padding 被压到 0 后实测只有 22px 高，低于 24px 最小可点尺寸
 * —— 这是侧栏唯一的常驻按钮，点不中会很烦。
 */
.app-shell__collapse {
  width: 100%;
  min-height: 28px;
  justify-content: center;
  gap: var(--os-space-2);
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}

.app-shell__collapse:hover {
  color: var(--el-color-primary);
}

/* ---- 顶栏 ---- */

.app-shell__body {
  min-width: 0;
}

.app-shell__header {
  display: flex;
  align-items: center;
  gap: var(--os-space-3);
  height: var(--os-header-height);
  border-bottom: 1px solid var(--el-border-color-lighter);
  background: var(--el-bg-color);
  /* 顶栏吸顶：长页面（知识库 / 日程）滚动时操作区不消失 */
  position: sticky;
  top: 0;
  z-index: 10;
  /* 半透明 + 毛玻璃：内容从下方滚过时文字不会与顶栏糊在一起 */
  backdrop-filter: saturate(180%) blur(8px);
  background: color-mix(in srgb, var(--el-bg-color) 88%, transparent);
}

.app-shell__header-spacer {
  flex: 1;
}

.app-shell__right {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
  flex: none;
}

/* ---- 顶栏搜索（胶囊 + 快捷键提示） ---- */

.app-shell__search {
  position: relative;
  flex: none;
  width: 260px;
}

/*
 * 胶囊形搜索框：EP 输入框默认圆角 8px，这里单独抬到 round。
 * 选中态（聚焦）由 EP 自己的 focus 边框处理，不重复写。
 */
.app-shell__search :deep(.el-input__wrapper) {
  border-radius: var(--os-radius-round);
  background: var(--el-fill-color-light);
  box-shadow: none;
  padding-right: 52px;
  transition: background var(--os-duration-fast) var(--os-ease);
}

.app-shell__search :deep(.el-input__wrapper:hover) {
  background: var(--el-fill-color);
}

.app-shell__search :deep(.el-input__wrapper.is-focus) {
  background: var(--el-bg-color);
  box-shadow: 0 0 0 1px var(--el-color-primary) inset;
}

/*
 * 快捷键提示贴在输入框右内侧。
 * pointer-events:none 是必须的 —— 否则点击 kbd 区域不会聚焦输入框，
 * 用户会觉得「搜索框右边一截点不动」。
 */
.app-shell__kbd {
  position: absolute;
  top: 50%;
  right: var(--os-space-2);
  transform: translateY(-50%);
  padding: 1px 6px;
  border: 1px solid var(--el-border-color-light);
  border-radius: var(--os-radius-sm);
  background: var(--el-bg-color);
  color: var(--el-text-color-placeholder);
  font-family: inherit;
  font-size: var(--os-font-xs);
  line-height: 16px;
  pointer-events: none;
}

/* ---- 用户区 ---- */

.app-shell__user {
  display: inline-flex;
  align-items: center;
  gap: var(--os-space-2);
  padding: 3px var(--os-space-2) 3px 3px;
  border: 1px solid transparent;
  border-radius: var(--os-radius-round);
  background: none;
  color: var(--el-text-color-regular);
  font-family: inherit;
  font-size: var(--os-font-xs);
  cursor: pointer;
  transition: background var(--os-duration-fast) var(--os-ease);
}

.app-shell__user:hover {
  background: var(--el-fill-color-light);
  border-color: var(--el-border-color-lighter);
}

/* 头像：主色浅底 + 主色字，与 Logo 同色系但不抢它 */
.app-shell__avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border-radius: 50%;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-size: var(--os-font-xs);
  font-weight: 600;
  flex: none;
}

.app-shell__user-name {
  max-width: 100px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.app-shell__user-email {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}

/* 未开启认证时的占位图标（不是可点元素，只是说明「没有用户概念」） */
.app-shell__guest {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-placeholder);
}

/* 折叠按钮（宽屏）与汉堡（窄屏）按视口二选一 */
.app-shell__burger {
  display: none;
  flex: none;
  color: var(--el-text-color-regular);
}

/* 顶栏折叠按钮：只放一个图标，宽度会缩到 20px，补到 24px 见方 */
.app-shell__collapse-top {
  display: inline-flex;
  flex: none;
  min-width: 24px;
  min-height: 24px;
  color: var(--el-text-color-regular);
}

.app-shell__nav-icon {
  margin-right: 6px;
}

/*
 * 主内容区限宽 1080px 并居中：适合「一列内容」的页面（任务/知识库/设置）。
 * 对话页是左右分栏 + 全高对话列，限宽会让两侧各空一大块，
 * 因此用 --wide 退出限宽（见路由 meta.fullWidth）。
 */
/*
 * main 撑满外壳剩余空间，并自身成为 flex 列容器 ——
 * 子视图（如对话页）用 height:100% 即可铺满，不再依赖 calc。
 */
.app-shell__main {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  max-width: var(--os-content-max-width);
  width: 100%;
  margin: 0 auto;
  /*
   * 内容区上下留白给足（24px），左右 20px。
   * 改造前是 8px/12px：卡片几乎贴着顶栏与窗口边缘，页面显得局促。
   * 顶部多给一点是因为顶栏有下边框，视觉上需要「呼吸」。
   */
  padding: var(--os-space-5) var(--os-space-5) var(--os-space-6);
}

/*
 * 沉浸式页面（对话）：内边距再压一档，让对话区尽量贴住视口边缘。
 * 上下留 12px 而不贴死：卡片圆角与阴影需要空间，否则像被切掉一角。
 */
.app-shell__main--wide {
  max-width: none;
  padding: var(--os-space-3);
}

/*
 * 页脚收成一条细行。
 * 改造前它是 EP 默认 60px 高、居中一行 12px 灰字 —— 占掉 60px 却只放三个词，
 * 在 900px 视口里纯属浪费。
 */
.app-shell__footer {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--os-space-2);
  flex: none;
  height: 34px;
  padding: 0 var(--os-space-5);
  border-top: 1px solid var(--el-border-color-lighter);
  background: var(--el-bg-color);
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}

/*
 * 分隔点：用 secondary 而不是 border-color。
 * 改造后实测 border-color(#eceff4) 在底色上只有 1.37:1 —— 三个词读起来是
 * 「Personal AI OSApache-2.0本地单用户」，点几乎不存在。用 secondary 后
 * 它读作真正的分隔符，同时仍明显弱于正文（不抢注意力）。
 */
.app-shell__footer-dot {
  color: var(--el-text-color-placeholder);
  user-select: none;
}

/* 对话页是固定视口高，页脚留着会挤压消息区 → 该页隐藏 */
.app-shell--fixed .app-shell__footer {
  display: none;
}

/*
 * 窄屏（<768px，FR-014）：侧栏收起为抽屉，顶栏左侧改汉堡；
 * 品牌标题隐藏（抽屉里有全称），搜索框收缩。
 */
@media (max-width: 768px) {
  .app-shell__aside {
    display: none;
  }

  .app-shell__burger {
    display: inline-flex;
  }

  .app-shell__collapse-top {
    display: none;
  }

  .app-shell__header {
    gap: var(--os-space-2);
    padding: 0 var(--os-space-3);
  }

  .app-shell__search {
    width: auto;
    flex: 1;
    min-width: 0;
  }

  /* 窄屏不显示快捷键提示（没有物理键盘的场景居多，且挤占输入区） */
  .app-shell__kbd {
    display: none;
  }

  .app-shell__search :deep(.el-input__wrapper) {
    padding-right: 11px;
  }

  .app-shell__main {
    padding: var(--os-space-4) var(--os-space-3) var(--os-space-5);
  }

  .app-shell__main--wide {
    padding: var(--os-space-2);
  }
}

/* 路由切换过渡：短促的淡入上移，给页面切换一点空间感 */
.page-enter-active,
.page-leave-active {
  transition:
    opacity var(--os-duration-fast) var(--os-ease),
    transform var(--os-duration-fast) var(--os-ease);
}

.page-enter-from {
  opacity: 0;
  transform: translateY(4px);
}

.page-leave-to {
  opacity: 0;
}

/* 尊重系统「减少动态效果」偏好（spec Edge Cases） */
@media (prefers-reduced-motion: reduce) {
  .page-enter-active,
  .page-leave-active {
    transition: none;
  }
}
</style>
