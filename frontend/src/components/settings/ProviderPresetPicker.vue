<script setup lang="ts">
/**
 * 供应商预设选择面板。
 *
 * <h2>为什么是「搜索 + 列表」而不是下拉框</h2>
 * 预设是一堆「端点 + 两个模型名」的组合，光看名字选容易选错
 * （同一个厂商常有多个套餐、多个区域）。列表里直接展示端点，
 * 用户一眼就能确认「这是不是我要的那个」。而预设一多，纯列表又难扫，
 * 所以配一个搜索框 —— 名称、厂商、域名、模型名四种都能搜到。
 */
import { Search } from '@element-plus/icons-vue'
import { computed, ref } from 'vue'

import { PROVIDER_PRESETS, searchPresets, type ProviderPreset } from '@/lib/provider-presets'

const keyword = ref('')

const results = computed(() => searchPresets(keyword.value))

const emit = defineEmits<{
  (e: 'pick', preset: ProviderPreset): void
}>()

/** 高亮搜索结果里的厂商名，帮用户区分「同名不同厂商」 */
function vendorOf(preset: ProviderPreset): string {
  return preset.vendor === preset.name ? '' : preset.vendor
}
</script>

<template>
  <div class="preset-picker">
    <el-input
      v-model="keyword"
      class="preset-picker__search"
      placeholder="搜索供应商名称、域名或模型名"
      clearable
      :prefix-icon="Search"
    />

    <ul v-if="results.length > 0" class="preset-picker__list">
      <li v-for="preset in results" :key="preset.id">
        <button type="button" class="preset-picker__item" @click="emit('pick', preset)">
          <span class="preset-picker__item-head">
            <span class="preset-picker__item-name">{{ preset.name }}</span>
            <span v-if="vendorOf(preset)" class="preset-picker__item-vendor">{{ vendorOf(preset) }}</span>
            <el-tag v-if="!preset.requiresKey" size="small" type="info">无需密钥</el-tag>
          </span>
          <span class="preset-picker__item-note">{{ preset.note }}</span>
          <span v-if="preset.baseUrl" class="preset-picker__item-url">{{ preset.baseUrl }}</span>
        </button>
      </li>
    </ul>

    <p v-else class="preset-picker__empty">没有匹配的供应商。可以直接选「自定义 / 中转站」自行填写。</p>

    <p class="preset-picker__foot">共 {{ PROVIDER_PRESETS.length }} 个预设。预设只是填好的默认值，选中后可任意修改。</p>
  </div>
</template>

<style scoped>
.preset-picker {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-3);
}

/*
 * 不设 max-height。
 *
 * 原本限 380px + overflow-y:auto，实测 8 个预设内容高 690px —— 最后一项
 * 正好被切在半截（顶部露出、描述与端点看不到），空态下这很像个 bug：
 * 用户看不到「下面还有」。既然 8 项全展开也只 690px，就不滚了。
 *
 * 预设涨到 15+ 项时再加回限高，但要配合底部渐隐遮罩提示「还能滚」，
 * 否则同样会切半且无提示。
 */
.preset-picker__list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--os-space-2);
}

.preset-picker__item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  width: 100%;
  padding: var(--os-space-3);
  text-align: left;
  background: var(--el-fill-color-blank);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--os-radius-md);
  cursor: pointer;
  transition:
    border-color var(--os-duration-fast) var(--os-ease),
    background-color var(--os-duration-fast) var(--os-ease);
}

.preset-picker__item:hover {
  border-color: var(--el-color-primary-light-5);
  background: var(--el-color-primary-light-9);
}

.preset-picker__item:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 1px;
}

.preset-picker__item-head {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
  flex-wrap: wrap;
}

.preset-picker__item-name {
  font-size: var(--os-font-md);
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.preset-picker__item-vendor {
  font-size: var(--os-font-xs);
  color: var(--el-text-color-secondary);
}

.preset-picker__item-note {
  font-size: var(--os-font-xs);
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}

.preset-picker__item-url {
  font-family: var(--os-font-mono, ui-monospace, monospace);
  font-size: var(--os-font-xs);
  color: var(--el-text-color-placeholder);
  word-break: break-all;
}

.preset-picker__empty,
.preset-picker__foot {
  margin: 0;
  font-size: var(--os-font-xs);
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}
</style>
