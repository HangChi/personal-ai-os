<script setup lang="ts">
import { computed, watch } from 'vue'
import { useRoute } from 'vue-router'

import PageHeader from '@/components/PageHeader.vue'
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

/** 标题：有词时带上关键词，否则是页面名 */
const pageTitle = computed(() => (searchStore.keyword ? `「${searchStore.keyword}」` : '全局搜索'))

/** 描述：有词时报命中数，无词时给操作引导 */
const pageDescription = computed(() =>
  searchStore.keyword
    ? `在任务、对话、日程、记忆与知识库中找到 ${searchStore.totalHits} 条`
    : '在顶部搜索框输入关键词，或按 ⌘ K / Ctrl K 唤起。',
)

// 用 watch 而不是 onMounted（审查 P2）：同路由二次搜索（顶栏再回车）时组件实例被复用，
// onMounted 不再触发，URL 变了但结果不变。immediate 保证首次进入也执行。
watch(
  () => route.query.q,
  (query) => {
    const q = typeof query === 'string' ? query : ''
    if (q !== searchStore.keyword) {
      void searchStore.search(q)
    }
  },
  { immediate: true },
)
</script>

<template>
  <div class="search-page">
    <PageHeader size="page" :title="pageTitle" :description="pageDescription" />

    <el-card shadow="never">
      <p class="search-page__hint">每类最多显示 5 条 —— 全局搜索用来定位到哪个模块，更细的筛选请去对应页面。</p>

      <el-alert v-if="searchStore.error" type="error" :closable="false" show-icon :title="searchStore.error" />

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
                  <template
                    v-for="(segment, index) in highlightSegments(hit.subtitle, searchStore.keyword)"
                    :key="index"
                  >
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
  gap: var(--os-space-5);
}

/*
 * 页头由 PageHeader 统一渲染（size="page"）。原先这里手写了
 * `.search-page__head / __title / __count` 三条规则 —— 其中「关键词可能很长，
 * 长到换行也不要撑破内容区」这条不能丢，已上移到 PageHeader 的标题上
 * （对所有页面的长标题都成立，不只是搜索页）。
 */

.search-page__hint {
  margin: 0 0 var(--os-space-4);
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
  line-height: 1.6;
}

.search-page__groups {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-5);
}

/*
 * 分组头（阶段 23）：模块名 + 命中数，下边框收紧为一条虚线。
 * 改造前是实线 + 12px 数字，六组叠起来上半屏全是横线；虚线弱得多。
 */
.search-page__group-header {
  display: flex;
  align-items: baseline;
  gap: var(--os-space-2);
  margin-bottom: var(--os-space-1);
  padding-bottom: var(--os-space-2);
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.search-page__group-name {
  font-size: var(--os-font-md);
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.search-page__group-count {
  color: var(--el-text-color-placeholder);
  font-size: var(--os-font-xs);
  font-variant-numeric: tabular-nums;
}

.search-page__hits {
  margin: 0;
  padding: 0;
  list-style: none;
}

/*
 * 命中行：整行可点 + 悬停浅底。
 * 改造前只有文字变蓝（没有背景），鼠标划过时几乎看不出「这一行是链接」；
 * 而且 padding 只有 8px 4px，可点区高度不足 32px。
 */
.search-page__hit-link {
  display: flex;
  align-items: center;
  gap: var(--os-space-3);
  padding: var(--os-space-2) var(--os-space-3);
  border-radius: var(--os-radius-md);
  color: inherit;
  text-decoration: none;
  transition: background var(--os-duration-fast) var(--os-ease);
}

.search-page__hit-link:hover {
  background: var(--el-fill-color-light);
}

.search-page__hit-link:hover .search-page__hit-title {
  color: var(--el-color-primary);
}

.search-page__hit-title {
  flex: 0 1 auto;
  font-size: var(--os-font-md);
  font-weight: 500;
  transition: color var(--os-duration-fast) var(--os-ease);
}

.search-page__hit-subtitle {
  flex: 1 1 auto;
  min-width: 0;
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/*
 * 关键词高亮（C-07）：warning 浅底 + 继承字色，不用第三方组件。
 *
 * 底色是浅黄，副标题本身是 secondary，两者叠起来实测只有 3.83:1。
 * 高亮的目的是「跳出来」，所以这里改用 regular 字色，不靠继承。
 */
.search-page__hit-title mark,
.search-page__hit-subtitle mark {
  padding: 0 2px;
  border-radius: 3px;
  background: var(--el-color-warning-light-8);
  color: var(--el-text-color-regular);
  font-weight: 600;
}

@media (max-width: 640px) {
  /* 窄屏 subtitle 换行到标题下方，否则标题被挤成两三个字一行 */
  .search-page__hit-link {
    flex-direction: column;
    align-items: flex-start;
    gap: var(--os-space-1);
  }

  .search-page__hit-subtitle {
    white-space: normal;
  }
}
</style>
