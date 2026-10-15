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

/** 依赖分量颜色：UP/CONFIGURED 成功，NOT_CONFIGURED 信息（可选依赖未装配），DOWN 危险 */
function componentTagType(value: string): 'success' | 'info' | 'danger' {
  if (value === 'UP' || value === 'CONFIGURED') return 'success'
  if (value === 'NOT_CONFIGURED') return 'info'
  return 'danger'
}

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
        <el-descriptions-item v-if="health.components" label="依赖">
          <el-tag
            v-for="(value, key) in health.components"
            :key="key"
            :type="componentTagType(value)"
            size="small"
            style="margin-right: 6px"
          >
            {{ key }}: {{ value }}
          </el-tag>
        </el-descriptions-item>
      </el-descriptions>

      <el-skeleton v-else :rows="4" animated />
    </el-card>

    <el-card class="home__card" shadow="never">
      <template #header>已有能力</template>
      <ul class="home__list">
        <li><strong>对话</strong>：多轮上下文、聊天历史持久化、SSE 流式输出</li>
        <li><strong>任务</strong>：自然语言增删改查；删除与关键字段修改需人工确认</li>
        <li><strong>知识库</strong>：上传 Markdown / TXT 建立向量索引，问答附带引用来源</li>
        <li><strong>日程与提醒</strong>：重复规则、到点提醒（页面轮询）</li>
        <li><strong>MCP 外部工具</strong>：接入外部工具 server；只读工具直接执行，变更类需人工确认</li>
      </ul>
      <p class="home__hint">
        助手内置工具共 14 个（任务 8 + 知识库 1 + 日程提醒 5），另可经 MCP 接入外部工具；
        其中删除类操作与 MCP 变更类工具只会生成「待确认事项」，必须由你在界面上确认后才会真正生效。
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
