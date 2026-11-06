<script setup lang="ts">
import { computed, defineAsyncComponent } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'

const ActivityView = defineAsyncComponent(() => import('@/views/ActivityView.vue'))
const UsageView = defineAsyncComponent(() => import('@/views/UsageView.vue'))
const route = useRoute()
const router = useRouter()
const activeView = computed({
  get: () => (route.query.view === 'usage' ? 'usage' : 'activity'),
  set: (view: string) => {
    void router.push({ query: { ...route.query, view: view === 'usage' ? view : undefined } })
  },
})
</script>

<template>
  <div class="os-page runtime">
    <PageHeader size="page" title="运行中心" description="追踪助手的工具调用，了解模型用量与执行结果。" />
    <el-tabs v-model="activeView" class="os-module-tabs">
      <el-tab-pane name="activity" label="工具活动" lazy><ActivityView embedded /></el-tab-pane>
      <el-tab-pane name="usage" label="模型用量" lazy><UsageView embedded /></el-tab-pane>
    </el-tabs>
  </div>
</template>
