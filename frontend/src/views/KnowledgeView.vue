<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { storeToRefs } from 'pinia'
import { onMounted, ref } from 'vue'

import { useKnowledgeStore } from '@/stores/knowledge'

const knowledgeStore = useKnowledgeStore()
const {
  documents,
  total,
  page,
  size,
  keyword,
  hits,
  answer,
  loadingDocuments,
  uploading,
  searching,
  asking,
  error,
  isEmpty,
} = storeToRefs(knowledgeStore)

const fileInput = ref<HTMLInputElement | null>(null)
const selectedFile = ref<File | null>(null)
const uploadTitle = ref('')

const searchQuery = ref('')
const searchTopK = ref(4)

const question = ref('')
const askTopK = ref(4)

const ACCEPT = '.md,.markdown,.txt,.pdf,.docx,.png,.jpg,.jpeg,.webp,.gif'
const MAX_BYTES = 5 * 1024 * 1024

onMounted(() => {
  void knowledgeStore.loadDocuments()
})

function pickFile(): void {
  fileInput.value?.click()
}

function onFileChange(event: Event): void {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0] ?? null
  if (!file) {
    return
  }
  if (file.size > MAX_BYTES) {
    ElMessage.warning('文件超过 5 MiB 上限，请压缩或拆分后再上传')
    input.value = ''
    return
  }
  selectedFile.value = file
  if (!uploadTitle.value) {
    uploadTitle.value = file.name.replace(/\.[^.]+$/, '')
  }
}

async function handleUpload(): Promise<void> {
  if (!selectedFile.value) {
    ElMessage.warning('请先选择要上传的文件')
    return
  }
  const ok = await knowledgeStore.upload(selectedFile.value, uploadTitle.value)
  if (ok) {
    ElMessage.success('已上传并建立索引')
    selectedFile.value = null
    uploadTitle.value = ''
    if (fileInput.value) {
      fileInput.value.value = ''
    }
  }
}

async function handleDelete(id: string, title: string): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `删除「${title}」会同时清理它的全部向量，之后将无法再被检索到。`,
      '删除知识文档',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  const deletedChunks = await knowledgeStore.remove(id)
  if (deletedChunks !== null) {
    ElMessage.success(`已删除，清理向量 ${deletedChunks} 条`)
  }
}

async function handleSearch(): Promise<void> {
  const query = searchQuery.value.trim()
  if (!query) {
    ElMessage.warning('请输入检索词')
    return
  }
  await knowledgeStore.search(query, searchTopK.value)
}

async function handleAsk(): Promise<void> {
  const text = question.value.trim()
  if (!text) {
    ElMessage.warning('请输入问题')
    return
  }
  await knowledgeStore.ask(text, askTopK.value)
}

function formatSize(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`
  }
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`
}

function formatDateTime(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString('zh-CN', { hour12: false })
}

/**
 * 命中来源徽标（混合检索）。
 *
 * 三种情况文案不同，不能拼在一起：
 * - BOTH：两条通道都命中，可信度最高
 * - 仅关键词命中：score 为 null（没做过嵌入），不能显示「相似度 —」
 * - 仅向量命中：正常显示相似度
 */
function hitBadge(hit: { score: number | null; matchedBy?: string }): {
  text: string
  type: 'success' | 'warning' | 'info'
} {
  if (hit.matchedBy === 'BOTH') {
    return { text: '向量+关键词', type: 'success' }
  }
  if (hit.score === null || hit.score === undefined) {
    return { text: '关键词命中', type: 'warning' }
  }
  return { text: `相似度 ${hit.score.toFixed(3)}`, type: 'info' }
}
</script>

<template>
  <div class="knowledge">
    <el-card shadow="never">
      <template #header>
        <div class="knowledge__header">
          <span>上传文档（Markdown / TXT / PDF / Word / 图片）</span>
          <span class="knowledge__stats">
            共 {{ total }} 篇
          </span>
        </div>
      </template>

      <div class="knowledge__upload">
        <input
          ref="fileInput"
          class="knowledge__file-input"
          type="file"
          :accept="ACCEPT"
          @change="onFileChange"
        />
        <el-button @click="pickFile">选择文件</el-button>
        <span class="knowledge__filename">{{ selectedFile ? selectedFile.name : '未选择文件' }}</span>
        <el-input v-model="uploadTitle" class="knowledge__title" placeholder="文档标题（可选）" />
        <el-button type="primary" :loading="uploading" @click="handleUpload">上传并建立索引</el-button>
      </div>

      <p class="knowledge__hint">
        支持 .md / .markdown / .txt（需 UTF-8）、.pdf（文本层，扫描件需 OCR 暂不支持）、
        .docx（段落与表格）、以及 .png / .jpg / .jpeg / .webp / .gif（需配置可用的视觉模型）。
        单文件不超过 5 MiB；上传会触发解析、分块与向量化，时长取决于文档大小。
      </p>
    </el-card>

    <el-alert
      v-if="error"
      type="error"
      :closable="false"
      show-icon
      :title="error"
    />

    <el-card shadow="never">
      <template #header>
        <div class="knowledge__header">
          <span>文档列表</span>
          <div class="knowledge__filters">
            <el-input
              v-model="keyword"
              class="knowledge__filter"
              clearable
              placeholder="按标题 / 文件名搜索"
              @keydown.enter="knowledgeStore.searchDocuments()"
              @clear="knowledgeStore.searchDocuments()"
            />
            <el-button :loading="loadingDocuments" @click="knowledgeStore.loadDocuments()">
              刷新
            </el-button>
          </div>
        </div>
      </template>

      <!-- 空态要区分「一篇都没有」与「筛掉了」：分页后仍说「还没有上传」会让人以为知识库坏了 -->
      <el-empty
        v-if="isEmpty && !loadingDocuments"
        :description="keyword ? '没有匹配的文档' : '还没有上传任何文档'"
        :image-size="70"
      />
      <el-table v-else v-loading="loadingDocuments" :data="documents">
        <el-table-column prop="title" label="标题" min-width="180" />
        <el-table-column prop="filename" label="文件名" min-width="160" />
        <el-table-column label="大小" width="100">
          <template #default="{ row }">{{ formatSize(row.sizeBytes) }}</template>
        </el-table-column>
        <el-table-column prop="chunkCount" label="片段数" width="90" />
        <!--
          字符数列：解析出的文本长度（分块前）。它与「大小」不是一回事 ——
          扫描版 PDF 体积很大但字符数接近 0，一眼能看出「这篇没解析出什么内容」。
          该字段阶段 3 起后端一直没写入（恒为 0），阶段 10 才修复；展示出来也让回归能被肉眼发现。
        -->
        <el-table-column prop="charCount" label="字符数" width="100" />
        <el-table-column label="上传时间" width="180">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="danger" size="small" @click="handleDelete(row.id, row.title)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="total > 0" class="knowledge__pagination">
        <span class="knowledge__total">共 {{ total }} 篇</span>
        <el-pagination
          layout="prev, pager, next"
          :total="total"
          :current-page="page + 1"
          :page-size="size"
          background
          @current-change="(next: number) => knowledgeStore.goToPage(next - 1)"
        />
      </div>
    </el-card>

    <el-card shadow="never">
      <template #header>检索（不经过模型，直接看检索到了什么）</template>

      <div class="knowledge__query">
        <el-input
          v-model="searchQuery"
          placeholder="检索词，例如：隔离级别"
          maxlength="500"
          @keydown.enter="handleSearch"
        />
        <el-input-number v-model="searchTopK" :min="1" :max="20" class="knowledge__topk" />
        <el-button :loading="searching" @click="handleSearch">检索</el-button>
      </div>

      <el-empty v-if="hits.length === 0" description="暂无检索结果" :image-size="60" />
      <ul v-else class="knowledge__hits">
        <li v-for="(hit, index) in hits" :key="`${hit.documentId}-${hit.chunkIndex}-${index}`">
          <div class="knowledge__hit-head">
            <span class="knowledge__hit-source">
              {{ hit.documentTitle }}
              <span class="knowledge__hit-meta">#{{ hit.chunkIndex + 1 }}</span>
            </span>
            <el-tag size="small" :type="hitBadge(hit).type" effect="plain">
              {{ hitBadge(hit).text }}
            </el-tag>
          </div>
          <div class="knowledge__hit-content">{{ hit.content }}</div>
        </li>
      </ul>
    </el-card>

    <el-card shadow="never">
      <template #header>问答（附引用来源）</template>

      <div class="knowledge__query">
        <el-input
          v-model="question"
          placeholder="基于知识库提问，例如：支持哪些隔离级别？"
          maxlength="500"
          @keydown.enter="handleAsk"
        />
        <el-input-number v-model="askTopK" :min="1" :max="20" class="knowledge__topk" />
        <el-button type="primary" :loading="asking" @click="handleAsk">提问</el-button>
      </div>

      <template v-if="answer">
        <el-alert
          v-if="!answer.answered"
          type="info"
          :closable="false"
          show-icon
          title="知识库中没有找到相关内容"
          description="未调用模型生成回答，以避免编造。"
        />
        <template v-else>
          <div class="knowledge__answer">{{ answer.answer }}</div>
          <div class="knowledge__citations">
            <div class="knowledge__citations-title">引用来源（{{ answer.citations.length }}）</div>
            <div
              v-for="citation in answer.citations"
              :key="citation.index"
              class="knowledge__citation"
            >
              <div class="knowledge__hit-head">
                <span class="knowledge__hit-source">
                  [{{ citation.index }}] {{ citation.documentTitle }}
                  <span class="knowledge__hit-meta">{{ citation.filename }} · #{{ citation.chunkIndex + 1 }}</span>
                </span>
                <el-tag size="small" :type="hitBadge(citation).type" effect="plain">
                  {{ hitBadge(citation).text }}
                </el-tag>
              </div>
              <div class="knowledge__hit-content">{{ citation.content }}</div>
            </div>
          </div>
        </template>
      </template>
    </el-card>
  </div>
</template>

<style scoped>
.knowledge {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.knowledge__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.knowledge__filters {
  display: flex;
  align-items: center;
  gap: 8px;
}

.knowledge__filter {
  width: 200px;
}

.knowledge__pagination {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 12px;
}

.knowledge__total {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.knowledge__stats {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.knowledge__upload {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.knowledge__file-input {
  display: none;
}

.knowledge__filename {
  min-width: 160px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.knowledge__title {
  width: 220px;
}

.knowledge__hint {
  margin: 12px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.knowledge__query {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

.knowledge__topk {
  width: 110px;
  flex: none;
}

.knowledge__hits {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.knowledge__hit-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.knowledge__hit-source {
  font-size: 13px;
  font-weight: 600;
}

.knowledge__hit-meta {
  margin-left: 6px;
  color: var(--el-text-color-secondary);
  font-weight: 400;
  font-size: 12px;
}

.knowledge__hit-content {
  margin-top: 4px;
  padding: 8px 10px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
  font-size: 13px;
  white-space: pre-wrap;
  word-break: break-word;
}

.knowledge__answer {
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--el-color-primary-light-9);
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.7;
}

.knowledge__citations {
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.knowledge__citations-title {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.knowledge__citation {
  border-left: 3px solid var(--el-color-primary-light-5);
  padding-left: 10px;
}
</style>
