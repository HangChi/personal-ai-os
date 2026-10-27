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
  Tickets,
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

/** 导航项：图标 + 文案 + 路由。index 与 el-menu 的 router 模式对齐 */
const navItems = [
  { index: '/', label: '总览', icon: HomeFilled },
  { index: '/chat', label: '对话', icon: ChatDotRound },
  { index: '/tasks', label: '任务', icon: Tickets },
  { index: '/knowledge', label: '知识库', icon: Reading },
  { index: '/calendar', label: '日程', icon: Calendar },
  { index: '/memory', label: '记忆', icon: Memo },
  { index: '/activity', label: '活动', icon: DataLine },
  { index: '/settings', label: '设置', icon: Setting },
]

/** 顶栏全局搜索词（阶段 17）：回车后跳到结果页，关键词走 query 而非 store ——
 *  直接分享 /search?q=xxx 也该能用 */
const searchKeyword = ref('')
const searchInput = ref<InstanceType<typeof HTMLInputElement> | null>(null)

/** 窄屏抽屉导航（阶段 18）：<768px 侧栏隐藏，改汉堡 + 抽屉 */
const drawerOpen = ref(false)

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
    <el-aside v-if="!isLoginPage" class="app-shell__aside" :width="navCollapsed ? '64px' : '208px'">
      <router-link to="/" class="app-shell__brand" title="返回总览">
        <span class="app-shell__logo">AI</span>
        <span v-show="!navCollapsed" class="app-shell__title">Personal AI OS</span>
      </router-link>

      <el-menu class="app-shell__nav" :collapse="navCollapsed" :default-active="route.path" :router="true">
        <el-menu-item v-for="item in navItems" :key="item.index" :index="item.index" :title="item.label">
          <el-icon><component :is="item.icon" /></el-icon>
          <template #title>{{ item.label }}</template>
        </el-menu-item>
      </el-menu>

      <div class="app-shell__aside-footer">
        <el-button link class="app-shell__collapse" :title="navCollapsed ? '展开侧栏' : '收起侧栏'" @click="toggleNav">
          <el-icon :size="16"><Expand v-if="navCollapsed" /><Fold v-else /></el-icon>
          <span v-show="!navCollapsed">收起</span>
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

          <el-input
            ref="searchInput"
            v-model="searchKeyword"
            class="app-shell__search"
            size="small"
            clearable
            placeholder="全局搜索（Ctrl K）"
            :prefix-icon="Search"
            @keydown.enter="runGlobalSearch"
            @clear="searchKeyword = ''"
          />

          <div class="app-shell__right">
            <ThemeToggle />
            <template v-if="authEnabled && username">
              <span class="app-shell__user">{{ username }}</span>
              <el-button link size="small" @click="handleLogout">登出</el-button>
            </template>
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

      <el-footer class="app-shell__footer"> Personal AI OS · Apache-2.0 · 本地单用户 </el-footer>
    </el-container>

    <!-- 窄屏导航抽屉（<768px 才看得到） -->
    <el-drawer v-model="drawerOpen" title="导航" direction="ltr" size="220px">
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

.app-shell__aside {
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--el-border-color-light);
  background: var(--el-bg-color);
  /* 折叠/展开的宽度过渡 */
  transition: width var(--os-duration-normal) var(--os-ease);
  overflow: hidden;
}

.app-shell__brand {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
  flex: none;
  height: 56px;
  padding: 0 var(--os-space-4);
  text-decoration: none;
  color: inherit;
}

.app-shell__brand--header {
  height: auto;
  padding: 0;
}

.app-shell__logo {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: var(--os-radius-md);
  background: var(--el-color-primary);
  color: var(--el-color-white);
  font-size: var(--os-font-xs);
  font-weight: 700;
  flex: none;
}

.app-shell__title {
  font-size: var(--os-font-md);
  font-weight: 600;
  white-space: nowrap;
}

/* 垂直菜单：铺满侧栏中部，可滚动；EP 默认底色去掉，用侧栏自己的 */
.app-shell__nav {
  flex: 1;
  overflow-y: auto;
  border-right: none;
  background: transparent;
}

.app-shell__nav :deep(.el-menu-item) {
  height: 44px;
  margin: 2px var(--os-space-2);
  border-radius: var(--os-radius-md);
}

.app-shell__nav :deep(.el-menu-item.is-active) {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-weight: 500;
}

.app-shell__aside-footer {
  flex: none;
  padding: var(--os-space-2);
  border-top: 1px solid var(--el-border-color-lighter);
}

.app-shell__collapse {
  width: 100%;
  justify-content: center;
  gap: var(--os-space-2);
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}

/* ---- 顶栏 ---- */

.app-shell__body {
  min-width: 0;
}

.app-shell__header {
  display: flex;
  align-items: center;
  gap: var(--os-space-3);
  border-bottom: 1px solid var(--el-border-color-light);
  background: var(--el-bg-color);
  /* 顶栏吸顶：长页面（知识库 / 日程）滚动时操作区不消失 */
  position: sticky;
  top: 0;
  z-index: 10;
}

.app-shell__header-spacer {
  flex: 1;
}

.app-shell__right {
  display: flex;
  align-items: center;
  gap: var(--os-space-3);
  flex: none;
}

.app-shell__user {
  font-size: var(--os-font-xs);
  color: var(--el-text-color-regular);
}

.app-shell__search {
  flex: none;
  width: 220px;
}

/* 折叠按钮（宽屏）与汉堡（窄屏）按视口二选一 */
.app-shell__burger {
  display: none;
  flex: none;
  color: var(--el-text-color-regular);
}

.app-shell__collapse-top {
  display: inline-flex;
  flex: none;
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
  /* EP 的 el-main 自带 20px 四周内边距；对话页要贴边，这里显式压掉左右与底部 */
  padding: var(--os-space-2) var(--os-space-3) var(--os-space-2);
}

/*
 * 沉浸式页面（对话）：内边距再压一档，让对话区尽量贴住视口边缘。
 * 原先左右各 20px、上方 16px，宽屏下两边白边很明显。
 */
.app-shell__main--wide {
  max-width: none;
  padding: var(--os-space-2) var(--os-space-2) var(--os-space-2);
}

.app-shell__footer {
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
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

  .app-shell__main {
    padding-left: var(--os-space-3);
    padding-right: var(--os-space-3);
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
