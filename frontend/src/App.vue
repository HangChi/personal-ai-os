<script setup lang="ts">
/**
 * 应用根组件：整体布局 + 顶部导航 + 路由出口。
 * 业务状态一律放在 Pinia store 中，不做跨组件透传。
 *
 * 阶段 18 改动：
 * - 导航加图标（EP 图标按需 import，避免全量图标包进主包）
 * - 顶栏搜索加宽 + Ctrl/Cmd+K 全局聚焦（FR-004）
 * - 挂载 ThemeToggle，移除硬编码「阶段 17」标识——该文案每轮都会过期，
 *   是项目记忆里记着的坑（硬编码当前状态 = 必然过期的文档）
 * - 布局尺寸全部走 --os-* 令牌
 */
import {
  Calendar,
  ChatDotRound,
  DataLine,
  Fold,
  HomeFilled,
  Memo,
  Reading,
  Search,
  Tickets,
} from '@element-plus/icons-vue'
import { storeToRefs } from 'pinia'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
const { authEnabled, username } = storeToRefs(authStore)
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
]

/** 顶栏全局搜索词（阶段 17）：回车后跳到结果页，关键词走 query 而非 store ——
 *  直接分享 /search?q=xxx 也该能用 */
const searchKeyword = ref('')
const searchInput = ref<InstanceType<typeof HTMLInputElement> | null>(null)

/** 窄屏抽屉导航（阶段 18，FR-014）：<768px 时横向菜单放不下，改汉堡 + 抽屉 */
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

async function handleLogout(): Promise<void> {
  await authStore.logout()
  await router.push({ name: 'login' })
}
</script>

<template>
  <el-container class="app-shell">
    <el-header class="app-shell__header">
      <el-button
        v-if="!isLoginPage"
        class="app-shell__burger"
        link
        aria-label="打开导航菜单"
        @click="drawerOpen = true"
      >
        <el-icon :size="20"><Fold /></el-icon>
      </el-button>

      <div class="app-shell__brand">
        <span class="app-shell__logo">AI</span>
        <span class="app-shell__title">Personal AI OS</span>
      </div>

      <el-menu
        v-if="!isLoginPage"
        class="app-shell__nav"
        mode="horizontal"
        :router="true"
        :ellipsis="false"
      >
        <el-menu-item v-for="item in navItems" :key="item.index" :index="item.index">
          <el-icon class="app-shell__nav-icon"><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </el-menu-item>
      </el-menu>
      <div v-else class="app-shell__nav app-shell__nav--empty"></div>

      <el-input
        v-if="!isLoginPage"
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
        <ThemeToggle v-if="!isLoginPage" />
        <template v-if="authEnabled && username">
          <span class="app-shell__user">{{ username }}</span>
          <el-button link size="small" @click="handleLogout">登出</el-button>
        </template>
      </div>
    </el-header>

    <!-- 窄屏导航抽屉（<768px 才看得到） -->
    <el-drawer v-model="drawerOpen" title="导航" direction="ltr" size="220px">
      <el-menu :default-active="route.path" :router="true" @select="drawerOpen = false">
        <el-menu-item v-for="item in navItems" :key="item.index" :index="item.index">
          <el-icon class="app-shell__nav-icon"><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </el-menu-item>
      </el-menu>
    </el-drawer>

    <el-main class="app-shell__main">
      <router-view v-slot="{ Component }">
        <transition name="page" mode="out-in">
          <component :is="Component" />
        </transition>
      </router-view>
    </el-main>

    <el-footer class="app-shell__footer"> Personal AI OS · Apache-2.0 · 本地单用户 </el-footer>
  </el-container>
</template>

<style scoped>
.app-shell {
  min-height: 100vh;
}

.app-shell__header {
  display: flex;
  align-items: center;
  gap: var(--os-space-5);
  border-bottom: 1px solid var(--el-border-color-light);
  background: var(--el-bg-color);
  /* 顶栏吸顶：长页面（知识库 / 日程）滚动时导航不消失 */
  position: sticky;
  top: 0;
  z-index: 10;
}

.app-shell__brand {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
  flex: none;
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
}

.app-shell__title {
  font-size: var(--os-font-md);
  font-weight: 600;
  white-space: nowrap;
}

.app-shell__nav {
  flex: 1;
  border-bottom: none;
}

.app-shell__nav-icon {
  margin-right: 6px;
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

/* 登录页没有菜单，用等宽占位把右侧内容推到末尾，避免品牌区与右侧贴在一起 */
.app-shell__nav--empty {
  flex: 1;
}

.app-shell__search {
  flex: none;
  width: 220px;
}

/* 汉堡按钮：宽屏隐藏 */
.app-shell__burger {
  display: none;
  flex: none;
  color: var(--el-text-color-regular);
}

.app-shell__main {
  max-width: var(--os-content-max-width);
  width: 100%;
  margin: 0 auto;
  padding-top: var(--os-space-4);
}

.app-shell__footer {
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}

/*
 * 窄屏（<768px，FR-014）：横向菜单 7 项放不下 → 隐藏，改汉堡 + 抽屉。
 * 品牌标题同时隐藏（只留 Logo），搜索框收缩。
 */
@media (max-width: 768px) {
  .app-shell__header {
    gap: var(--os-space-2);
    padding: 0 var(--os-space-3);
  }

  .app-shell__nav {
    display: none;
  }

  .app-shell__burger {
    display: inline-flex;
  }

  .app-shell__title {
    display: none;
  }

  .app-shell__search {
    width: auto;
    flex: 1;
    min-width: 0;
    margin-left: auto;
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
