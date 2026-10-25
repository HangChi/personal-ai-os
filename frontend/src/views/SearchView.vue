<script setup lang="ts">
import { onMounted } from 'vue'
import { useRoute } from 'vue-router'

import { highlightSegments } from '@/lib/highlight-text'
import { useSearchStore } from '@/stores/search'
import { routeForHit } from '@/lib/search-routes'

/**
 * 全局搜索结果页（阶段 17）。
 *
 * 关键词来自顶栏搜索框跳转时带的 `?q=`；直接打开本页（无 q）时显示引导文案。
 */
const searchStore = useSearchStore()
const route = useRoute()

onMounted(() => {
  const query = typeof route.query.q === 'string' ? route.query.q : ''
  if (query && query !== searchStore.keyword) {
    void searchStore.search(query)
  }
})
</script>

<template>
  <div class="search-page">
    <el-card shadow="never">
      <template #header>
        <div class="search-page__header">
          <span v-if="searchStore.keyword">
            搜索「{{ searchStore.keyword }}」· 共 {{ searchStore.totalHits }} 条
          </span>
          <span v-else>全局搜索</span>
        </div>
      </template>

      <p class="search-page__hint">
        跨任务、对话、日程、记忆与知识库搜索。每类最多显示 5 条 ——
        全局搜索用来定位到哪个模块，更细的筛选请去对应页面。
      </p>

      <el-alert
        v-if="searchStore.error"
        type="error"
        :closable="false"
        show-icon
        :title="searchStore.error"
      />

      <!-- 三种空态要分开：没搜 / 搜了没有 / 正在搜 -->
      <el-empty v-if="!searchStore.keyword" description="在顶部搜索框输入关键词开始搜索" :image-size="70" />
      <el-empty
        v-else-if="searchStore.searched && searchStore.groups.length === 0 && !searchStore.loading"
        :description="`没有找到与「${searchStore.keyword}」相关的内容`"
        :image-size="70"
      />
      <!-- 加载中先给骨架，避免「点了没反应」的观感 -->
      <el-skeleton v-else-if="searchStore.loading && searchStore.groups.length === 0" :rows="6" animated />
      <div v-else v-loading="searchStore.loading" class="search-page__groups">
        <section v-for="group in searchStore.groups" :key="group.kind" class="search-page__group">
          <header class="search-page__group-header">
            <span class="search-page__group-name">{{ group.module }}</span>
            <span class="search-page__group-count">{{ group.hits.length }} 条</span>
          </header>

          <ul class="search-page__hits">
            <li v-for="hit in group.hits" :key="`${hit.kind}-${hit.id}`" class="search-page__hit">
              <router-link class="search-page__hit-link" :to="routeForHit(hit.kind)">
                <!-- 关键词高亮（C-07）：片段数组 + <mark>，不碰 v-html -->
                <span class="search-page__hit-title">
                  <template v-for="(segment, index) in highlightSegments(hit.title, searchStore.keyword)" :key="index">
                    <mark v-if="segment.hit">{{ segment.text }}</mark>
                    <template v-else>{{ segment.text }}</template>
                  </template>
                </span>
                <span v-if="hit.subtitle" class="search-page__hit-subtitle">
                  <template v-for="(segment, index) in highlightSegments(hit.subtitle, searchStore.keyword)" :key="index">
                    <mark v-if="segment.hit">{{ segment.text }}</mark>
                    <template v-else>{{ segment.text }}</template>
                  </template>
                </span>
                <el-tag size="small" type="info" effect="plain">{{ hit.matchedField }}</el-tag>
              </router-link>
            </li>
          </ul>
        </section>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.search-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.search-page__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.search-page__hint {
  margin: 0 0 16px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.search-page__groups {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.search-page__group-header {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding-bottom: 6px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.search-page__group-name {
  font-weight: 600;
}

.search-page__group-count {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.search-page__hits {
  margin: 0;
  padding: 0;
  list-style: none;
}

.search-page__hit {
  border-bottom: 1px dashed var(--el-border-color-lighter);
}

.search-page__hit:last-child {
  border-bottom: none;
}

.search-page__hit-link {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 8px 4px;
  color: inherit;
  text-decoration: none;
}

.search-page__hit-link:hover {
  color: var(--el-color-primary);
}

.search-page__hit-title {
  flex: 0 1 auto;
  font-size: var(--os-font-md);
}

.search-page__hit-subtitle {
  flex: 1 1 auto;
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}

/* 关键词高亮（C-07）：warning 浅底 + 继承字色，不用第三方组件 */
.search-page__hit-title mark,
.search-page__hit-subtitle mark {
  padding: 0 1px;
  border-radius: 2px;
  background: var(--el-color-warning-light-9);
  color: inherit;
}
</style>
