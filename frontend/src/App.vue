<script setup lang="ts">
/**
 * 应用根组件：整体布局 + 顶部导航 + 路由出口。
 * 业务状态一律放在 Pinia store 中，不做跨组件透传。
 */
import { storeToRefs } from 'pinia'
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
const { authEnabled, username } = storeToRefs(authStore)
const route = useRoute()
const router = useRouter()

/** 登录页不显示导航：点任何一个都会立刻被守卫弹回登录页，只是噪音 */
const isLoginPage = computed(() => route.name === 'login')

/** 顶栏全局搜索词（阶段 17）：回车后跳到结果页，关键词走 query 而非 store ——
 *  直接分享 /search?q=xxx 也该能用 */
const searchKeyword = ref('')

function runGlobalSearch(): void {
  const term = searchKeyword.value.trim()
  if (!term) {
    return
  }
  void router.push({ path: '/search', query: { q: term } })
}

async function handleLogout(): Promise<void> {
  await authStore.logout()
  await router.push({ name: 'login' })
}
</script>

<template>
  <el-container class="app-shell">
    <el-header class="app-shell__header">
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
        <el-menu-item index="/">总览</el-menu-item>
        <el-menu-item index="/chat">对话</el-menu-item>
        <el-menu-item index="/tasks">任务</el-menu-item>
        <el-menu-item index="/knowledge">知识库</el-menu-item>
        <el-menu-item index="/calendar">日程</el-menu-item>
        <el-menu-item index="/memory">记忆</el-menu-item>
        <el-menu-item index="/activity">活动</el-menu-item>
      </el-menu>
      <div v-else class="app-shell__nav app-shell__nav--empty"></div>

      <el-input
        v-if="!isLoginPage"
        v-model="searchKeyword"
        class="app-shell__search"
        size="small"
        clearable
        placeholder="全局搜索"
        @keydown.enter="runGlobalSearch"
        @clear="searchKeyword = ''"
      />

      <div class="app-shell__right">
        <span class="app-shell__phase">阶段 17 · 跨模块搜索</span>
        <template v-if="authEnabled && username">
          <span class="app-shell__user">{{ username }}</span>
          <el-button link size="small" @click="handleLogout">登出</el-button>
        </template>
      </div>
    </el-header>

    <el-main class="app-shell__main">
      <router-view />
    </el-main>

    <el-footer class="app-shell__footer">
      Personal AI OS · Apache-2.0 · 本地单用户
    </el-footer>
  </el-container>
</template>

<style scoped>
.app-shell {
  min-height: 100vh;
}

.app-shell__header {
  display: flex;
  align-items: center;
  gap: 24px;
  border-bottom: 1px solid var(--el-border-color-light);
  background: #ffffff;
}

.app-shell__brand {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: none;
}

.app-shell__logo {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: var(--el-color-primary);
  color: #ffffff;
  font-size: 13px;
  font-weight: 700;
}

.app-shell__title {
  font-size: 16px;
  font-weight: 600;
  white-space: nowrap;
}

.app-shell__nav {
  flex: 1;
  border-bottom: none;
}

.app-shell__phase {
  flex: none;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.app-shell__right {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: none;
}

.app-shell__user {
  font-size: 13px;
  color: var(--el-text-color-regular);
}

/* 登录页没有菜单，用等宽占位把右侧内容推到末尾，避免品牌区与右侧贴在一起 */
.app-shell__nav--empty {
  flex: 1;
}

.app-shell__search {
  flex: none;
  width: 160px;
}

.app-shell__main {
  max-width: 1080px;
  width: 100%;
  margin: 0 auto;
}

.app-shell__footer {
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
