<script setup lang="ts">
import { Plus, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { storeToRefs } from 'pinia'
import { computed, onMounted, ref } from 'vue'
import { useMemoryStore } from '@/stores/memory'
import type { Memory } from '@/types/api'

/**
 * 长期记忆管理页。
 *
 * 这个页面的存在意义不是「又一个 CRUD」：记忆会被注入**之后每一次对话**，
 * 一条错的记忆会污染全部回答。所以列表必须能看到原文与更新时间，
 * 且删除要给二次确认（不可撤销，且影响面是全局的）。
 */
const store = useMemoryStore()
const { memories, loading, saving, error } = storeToRefs(store)

/** 内容搜索（阶段 18，T035）：纯前端过滤——记忆最多 30 条，没有必要为它请求后端 */
const memoryKeyword = ref('')
const filteredMemories = computed(() => {
  const term = memoryKeyword.value.trim().toLowerCase()
  if (!term) {
    return memories.value
  }
  return memories.value.filter((item) => item.content.toLowerCase().includes(term))
})

/** 与后端 Texts 上限一致（去空白后计算） */
const CONTENT_MAX_LENGTH = 2000

const dialogVisible = ref(false)
const editingId = ref<string | null>(null)
const draft = ref('')

const dialogTitle = computed(() => (editingId.value ? '编辑记忆' : '新增记忆'))
const canSave = computed(() => {
  const trimmed = draft.value.trim()
  return trimmed.length > 0 && trimmed.length <= CONTENT_MAX_LENGTH
})

onMounted(async () => {
  await store.load()
})

function openCreate(): void {
  editingId.value = null
  draft.value = ''
  dialogVisible.value = true
}

function openEdit(memory: Memory): void {
  editingId.value = memory.id
  draft.value = memory.content
  dialogVisible.value = true
}

async function handleSave(): Promise<void> {
  const content = draft.value.trim()
  if (content.length === 0) {
    ElMessage.warning('记忆内容不能为空')
    return
  }
  if (content.length > CONTENT_MAX_LENGTH) {
    ElMessage.warning(`记忆内容长度不能超过 ${CONTENT_MAX_LENGTH}`)
    return
  }

  const success = editingId.value ? await store.update(editingId.value, content) : await store.create(content)

  if (success) {
    dialogVisible.value = false
    ElMessage.success('已保存')
  }
}

async function handleRemove(memory: Memory): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认删除这条记忆？删除后它不再参与任何对话。\n\n${memory.content}`, '删除记忆', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  if (await store.remove(memory.id)) {
    ElMessage.success('已删除')
  }
}

function formatTime(value: string): string {
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}
</script>

<template>
  <div class="memory-page">
    <header class="memory-page__head">
      <div>
        <h1 class="memory-page__title">长期记忆</h1>
        <p class="memory-page__subtitle">
          这里的内容会注入每一次对话（最多 30 条，越靠前越先生效）。一条错的记忆会污染全部回答，改动前请确认。
        </p>
      </div>
      <el-button type="primary" @click="openCreate">
        <el-icon><Plus /></el-icon>
        新增记忆
      </el-button>
    </header>

    <el-card class="memory" shadow="never">
      <template #header>
        <div class="memory__header">
          <div class="memory__heading">
            <span class="memory__title">已保存</span>
            <el-tag v-if="memories.length > 0" size="small" type="info" effect="plain" round>
              {{ filteredMemories.length }} / {{ memories.length }}
            </el-tag>
          </div>
          <el-input v-model="memoryKeyword" class="memory__search" clearable placeholder="搜索记忆内容">
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
        </div>
      </template>

      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="memory__error" />

      <div v-loading="loading" class="memory__content">
        <!-- 空态区分「一条都没有」与「筛掉了」 -->
        <el-empty
          v-if="filteredMemories.length === 0"
          :description="memoryKeyword ? '没有匹配的记忆' : '还没有长期记忆'"
          :image-size="70"
        >
          <p v-if="!memoryKeyword" class="memory__empty-tip">在对话里说「记住：我的周报每周五交」，也会出现在这里。</p>
        </el-empty>

        <ul v-else class="memory__list">
          <li v-for="(memory, index) in filteredMemories" :key="memory.id" class="memory__item">
            <span class="memory__index">{{ index + 1 }}</span>
            <div class="memory__body">
              <div class="memory__text">{{ memory.content }}</div>
              <div class="memory__meta">
                <el-tag v-if="memory.source === 'AUTO'" size="small" type="info" effect="plain" class="memory__source">
                  自动沉淀
                </el-tag>
                <span>最近更新 {{ formatTime(memory.updatedAt) }}</span>
              </div>
            </div>
            <div class="memory__actions">
              <el-button link type="primary" size="small" @click="openEdit(memory)">编辑</el-button>
              <el-button link type="danger" size="small" @click="handleRemove(memory)">删除</el-button>
            </div>
          </li>
        </ul>
      </div>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="560px">
      <el-input
        v-model="draft"
        type="textarea"
        :rows="5"
        :maxlength="CONTENT_MAX_LENGTH"
        show-word-limit
        placeholder="例如：我的周报每周五下午交"
      />
      <p class="memory__dialog-tip">写成一两句自足的陈述 —— 它会单独出现在每次对话里，没有上下文可以依赖。</p>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!canSave" @click="handleSave"> 保存 </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.memory-page {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-5);
  max-width: 900px;
  margin: 0 auto;
  width: 100%;
}

/* ---- 页面头（与其他页面统一：标题 22px + 一句说明 + 右侧主操作） ---- */

.memory-page__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--os-space-4);
  flex-wrap: wrap;
}

.memory-page__title {
  margin: 0;
  font-size: var(--os-font-2xl);
  font-weight: 650;
  letter-spacing: -0.2px;
  line-height: 1.3;
}

.memory-page__subtitle {
  margin: var(--os-space-2) 0 0;
  max-width: 62ch;
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-sm);
  line-height: 1.65;
}

.memory {
  width: 100%;
}

.memory__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--os-space-4);
  flex-wrap: wrap;
}

.memory__heading {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
}

.memory__title {
  font-size: var(--os-font-xl);
  font-weight: 600;
}

.memory__search {
  width: 220px;
}

.memory__error {
  margin-bottom: var(--os-space-3);
}

.memory__content {
  min-height: 120px;
}

.memory__empty-tip {
  margin-top: var(--os-space-2);
  font-size: var(--os-font-xs);
  color: var(--el-text-color-secondary);
}

.memory__list {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-2);
  margin: 0;
  padding: 0;
  list-style: none;
}

/*
 * 单条记忆：从「浅灰填充块」改为「描边 + 悬停上浮」。
 * 改造前每条都是 #f5f7fa 实底，十几条连在一起像一张大灰条；
 * 描边卡片在视觉上能一条条数清，也更接近「条目」而非「段落」。
 */
.memory__item {
  display: flex;
  align-items: flex-start;
  gap: var(--os-space-3);
  padding: var(--os-space-3) var(--os-space-4);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--os-radius-lg);
  background: var(--el-bg-color);
  transition:
    border-color var(--os-duration-fast) var(--os-ease),
    box-shadow var(--os-duration-fast) var(--os-ease);
}

.memory__item:hover {
  border-color: var(--el-color-primary-light-7);
  box-shadow: var(--os-shadow-card);
}

/* 序号：记忆的顺序有语义（越靠前越先生效），所以编号必须显式可见 */
/* 序号是「第几条」的索引，属于要读的信息；placeholder 实测仅 4.39:1 */
.memory__index {
  flex: none;
  width: 22px;
  height: 22px;
  border-radius: var(--os-radius-sm);
  background: var(--el-fill-color);
  color: var(--el-text-color-regular);
  font-size: var(--os-font-xs);
  font-variant-numeric: tabular-nums;
  line-height: 22px;
  text-align: center;
}

.memory__body {
  flex: 1;
  min-width: 0;
}

.memory__text {
  font-size: var(--os-font-md);
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--el-text-color-primary);
}

.memory__meta {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
  margin-top: var(--os-space-2);
  font-size: var(--os-font-xs);
  color: var(--el-text-color-secondary);
}

.memory__source {
  font-size: var(--os-font-xs);
}

.memory__actions {
  flex: none;
  display: flex;
  gap: var(--os-space-1);
  /* 与正文首行对齐（正文 line-height 1.65 × 14px ≈ 23px，按钮自身高 24px） */
  margin-top: -2px;
}

.memory__dialog-tip {
  margin: var(--os-space-2) 0 0;
  font-size: var(--os-font-xs);
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

@media (max-width: 640px) {
  .memory__search {
    width: 100%;
  }

  .memory__item {
    flex-wrap: wrap;
  }

  .memory__actions {
    width: 100%;
    justify-content: flex-end;
    margin-top: 0;
  }
}
</style>
