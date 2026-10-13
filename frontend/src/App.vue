<script setup lang="ts">
/**
 * 应用根组件：仅负责整体布局与路由出口。
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
      <span class="app-shell__phase">阶段 0 · 项目骨架</span>
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
  justify-content: space-between;
  border-bottom: 1px solid var(--el-border-color-light);
  background: #ffffff;
}

.app-shell__brand {
  display: flex;
  align-items: center;
  gap: 10px;
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
}

.app-shell__phase {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.app-shell__main {
  max-width: 960px;
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
