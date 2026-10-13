<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { computed, onMounted } from 'vue'

import { useAppStore } from '@/stores/app'

const appStore = useAppStore()
const { health, loading, error } = storeToRefs(appStore)

/** 状态标签颜色：UP 走成功色，其余走危险色 */
const tagType = computed<'success' | 'danger'>(() =>
  health.value?.status === 'UP' ? 'success' : 'danger',
)

onMounted(() => {
  void appStore.loadHealth()
})
</script>

<template>
  <div class="home">
    <el-card class="home__card" shadow="never">
      <template #header>
        <div class="home__card-header">
          <span>后端连通性</span>
          <el-button :loading="loading" @click="appStore.loadHealth()">刷新</el-button>
        </div>
      </template>

      <el-alert
        v-if="error"
        type="error"
        :closable="false"
        show-icon
        title="无法连接后端服务"
        :description="error"
      />

      <el-descriptions v-else-if="health" :column="1" border>
        <el-descriptions-item label="状态">
          <el-tag :type="tagType">{{ health.status }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="应用">{{ health.application }}</el-descriptions-item>
        <el-descriptions-item label="版本">{{ health.version }}</el-descriptions-item>
        <el-descriptions-item label="服务端时间">{{ health.timestamp }}</el-descriptions-item>
      </el-descriptions>

      <el-skeleton v-else :rows="4" animated />
    </el-card>

    <el-card class="home__card" shadow="never">
      <template #header>阶段 0 已完成</template>
      <ul class="home__list">
        <li>Monorepo 骨架：backend / frontend / docs / compose.yml</li>
        <li>后端：Spring Boot 4.1.1 + Spring AI BOM 2.0.1 + Java 21，按业务模块分包</li>
        <li>数据库：PostgreSQL 17 + pgvector，结构变更由 Flyway 管理</li>
        <li>前端：Vue 3 + TypeScript + Vite + Router + Pinia + Element Plus</li>
      </ul>
      <p class="home__hint">
        下一步（阶段 1）将接入 Spring AI ChatClient、工具调用与聊天历史。
      </p>
    </el-card>
  </div>
</template>

<style scoped>
.home {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.home__card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.home__list {
  margin: 0;
  padding-left: 18px;
  line-height: 1.9;
}

.home__hint {
  margin: 12px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
