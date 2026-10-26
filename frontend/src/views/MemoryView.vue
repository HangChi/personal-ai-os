<script setup lang="ts">
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
  <el-card class="memory" shadow="never">
    <template #header>
      <div class="memory__header">
        <div class="memory__heading">
          <span class="memory__title">长期记忆</span>
          <span class="memory__hint"> 这里的内容会注入每一次对话（最多 30 条，越靠前越先生效） </span>
        </div>
        <div class="memory__header-actions">
          <el-input v-model="memoryKeyword" class="memory__search" size="small" clearable placeholder="搜索记忆内容" />
          <el-button type="primary" size="small" @click="openCreate">新增</el-button>
        </div>
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
            <div class="memory__meta">最近更新 {{ formatTime(memory.updatedAt) }}</div>
          </div>
          <div class="memory__actions">
            <el-button link type="primary" size="small" @click="openEdit(memory)">编辑</el-button>
            <el-button link type="danger" size="small" @click="handleRemove(memory)">删除</el-button>
          </div>
        </li>
      </ul>
    </div>

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
  </el-card>
</template>

<style scoped>
.memory {
  max-width: 900px;
  margin: 0 auto;
}

.memory__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--os-space-4);
  flex-wrap: wrap;
}

.memory__header-actions {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
}

.memory__search {
  width: 180px;
}

.memory__heading {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.memory__title {
  font-size: 15px;
  font-weight: 600;
}

.memory__hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.memory__error {
  margin-bottom: 12px;
}

.memory__content {
  min-height: 120px;
}

.memory__empty-tip {
  margin-top: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.memory__list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.memory__item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
}

.memory__index {
  flex: none;
  width: 20px;
  font-size: 12px;
  line-height: 22px;
  color: var(--el-text-color-secondary);
  text-align: right;
}

.memory__body {
  flex: 1;
  min-width: 0;
}

.memory__text {
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.memory__meta {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.memory__actions {
  flex: none;
  display: flex;
  gap: 4px;
}

.memory__dialog-tip {
  margin-top: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
