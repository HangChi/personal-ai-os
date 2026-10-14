<script setup lang="ts">
/**
 * 应用根组件：整体布局 + 顶部导航 + 路由出口。
 * 业务状态一律放在 Pinia store 中，不做跨组件透传。
 */
</script>

<template>
  <el-container class="app-shell">
    <el-header class="app-shell__header">
      <div class="app-shell__brand">
        <span class="app-shell__logo">AI</span>
        <span class="app-shell__title">Personal AI OS</span>
      </div>

      <el-menu class="app-shell__nav" mode="horizontal" :router="true" :ellipsis="false">
        <el-menu-item index="/">总览</el-menu-item>
        <el-menu-item index="/chat">对话</el-menu-item>
        <el-menu-item index="/tasks">任务</el-menu-item>
        <el-menu-item index="/knowledge">知识库</el-menu-item>
      </el-menu>

      <span class="app-shell__phase">阶段 3 · 知识库 RAG</span>
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
