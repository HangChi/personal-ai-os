<script setup lang="ts">
/**
 * 知识库 · 文档 Tab（上传 + 列表）。
 *
 * <p>从 KnowledgeView 拆出（2026-10-26）。上传相关的三条路径都在这里：
 * 点击选择、拖拽投放、以及两者的公共校验。校验刻意只写一份 ——
 * 拖拽与点击走同一个 `selectFile`，避免「拖进来能传、点进来不能传」这类不一致。
 */
import { ElMessage, ElMessageBox } from 'element-plus'
import { storeToRefs } from 'pinia'
import { onMounted, ref } from 'vue'

import AppPagination from '@/components/AppPagination.vue'
import PageHeader from '@/components/PageHeader.vue'
import { formatBytes, formatDateTime } from '@/lib/format'
import { useKnowledgeStore } from '@/stores/knowledge'

const knowledgeStore = useKnowledgeStore()
const { documents, total, page, size, keyword, loadingDocuments, uploading, error, isEmpty } =
  storeToRefs(knowledgeStore)

const fileInput = ref<HTMLInputElement | null>(null)
const selectedFile = ref<File | null>(null)
const uploadTitle = ref('')

const ACCEPT = '.md,.markdown,.txt,.pdf,.docx,.png,.jpg,.jpeg,.webp,.gif'
const MAX_BYTES = 5 * 1024 * 1024

onMounted(() => {
  void knowledgeStore.loadDocuments()
})

function pickFile(): void {
  fileInput.value?.click()
}

/**
 * 选中一个文件（点击与拖拽共用这一条校验路径）。
 *
 * @returns 是否接受该文件
 */
function acceptFile(file: File): boolean {
  if (file.size > MAX_BYTES) {
    ElMessage.warning('文件超过 5 MiB 上限，请压缩或拆分后再上传')
    return false
  }
  selectedFile.value = file
  if (!uploadTitle.value) {
    uploadTitle.value = file.name.replace(/\.[^.]+$/, '')
  }
  return true
}

function onFileChange(event: Event): void {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0] ?? null
  if (!file) {
    return
  }
  if (!acceptFile(file)) {
    input.value = ''
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

/** 待替换的文档 id（文件选择器是共享的，选完要记住这一行） */
const replaceTargetId = ref<string | null>(null)
const replaceInput = ref<HTMLInputElement | null>(null)

function pickReplaceFile(id: string): void {
  replaceTargetId.value = id
  replaceInput.value?.click()
}

/**
 * 用新文件替换内容。
 *
 * <p>不弹「删除」那种措辞：替换不删文档，id 与历史引用都保留，
 * 只是分块与向量整组重算。确认框里写清这一点，用户才敢点。
 */
async function onReplaceFileChange(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  // 清空以便连续两次选同一个文件
  input.value = ''
  const id = replaceTargetId.value
  replaceTargetId.value = null
  if (!file || !id) {
    return
  }
  try {
    await ElMessageBox.confirm(
      `用「${file.name}」替换该文档的内容？\n\n` +
        '文档本身与历史对话里的引用都会保留；旧的分块与向量会被整组替换，' +
        '替换后需要重新建立索引（耗时取决于文件大小）。',
      '替换文档内容',
      { type: 'warning', confirmButtonText: '开始替换', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  const ok = await knowledgeStore.replace(id, file)
  if (ok) {
    ElMessage.success('已替换文档内容，向量已重建')
  }
}

async function handleDelete(id: string, title: string): Promise<void> {
  try {
    await ElMessageBox.confirm(`删除「${title}」会同时清理它的全部向量，之后将无法再被检索到。`, '删除知识文档', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  const deletedChunks = await knowledgeStore.remove(id)
  if (deletedChunks !== null) {
    ElMessage.success(`已删除，清理向量 ${deletedChunks} 条`)
  }
}

// ------------------------------------------------------------------ 拖拽上传（C-06）

/** 拖拽高亮：用计数器而非布尔——dragleave 在跨子元素时会提前触发，直接置假会闪烁 */
const dragDepth = ref(0)
const dragging = ref(false)

function onDragOver(event: DragEvent): void {
  event.preventDefault()
}

function onDragEnter(event: DragEvent): void {
  event.preventDefault()
  dragDepth.value += 1
  dragging.value = true
}

function onDragLeave(event: DragEvent): void {
  event.preventDefault()
  dragDepth.value = Math.max(0, dragDepth.value - 1)
  if (dragDepth.value === 0) {
    dragging.value = false
  }
}

/**
 * drop：只取第一个文件，复用 acceptFile 的全部校验
 * （类型靠 input 的 accept + 后端二次校验，大小在这里挡）。
 * 拒绝时撤高亮并提示，不静默吞掉。
 */
function onDrop(event: DragEvent): void {
  event.preventDefault()
  dragDepth.value = 0
  dragging.value = false
  const file = event.dataTransfer?.files?.[0]
  if (!file) {
    return
  }
  const name = file.name.toLowerCase()
  const allowed = ACCEPT.split(',')
  if (!allowed.some((ext) => name.endsWith(ext))) {
    ElMessage.warning('不支持的文件类型，请上传 Markdown / TXT / PDF / Word / 图片')
    return
  }
  if (acceptFile(file)) {
    ElMessage.success(`已选择 ${file.name}`)
  }
}
</script>

<template>
  <div class="kd">
    <el-card shadow="never">
      <template #header>
        <PageHeader title="上传文档" description="Markdown / TXT / PDF / Word / 图片" />
      </template>

      <!-- 拖拽区：整块都是投放目标；点击「选择文件」走原路径 -->
      <div
        class="kd__dropzone"
        :class="{ 'kd__dropzone--active': dragging }"
        @dragover="onDragOver"
        @dragenter="onDragEnter"
        @dragleave="onDragLeave"
        @drop="onDrop"
      >
        <input ref="fileInput" class="kd__file-input" type="file" :accept="ACCEPT" @change="onFileChange" />
        <el-button @click="pickFile">选择文件</el-button>
        <span class="kd__filename">
          {{ selectedFile ? selectedFile.name : '未选择文件（也可直接拖到这里）' }}
        </span>
        <el-input v-model="uploadTitle" class="kd__title" placeholder="文档标题（可选）" />
        <el-button type="primary" :loading="uploading" @click="handleUpload">上传并建立索引</el-button>
      </div>

      <p class="kd__hint">
        支持 .md / .markdown / .txt（需 UTF-8）、.pdf（文本层，扫描件需 OCR 暂不支持）、 .docx（段落与表格）、以及 .png
        / .jpg / .jpeg / .webp / .gif（需配置可用的视觉模型）。 单文件不超过 5
        MiB；上传会触发解析、分块与向量化，时长取决于文档大小。
      </p>
    </el-card>

    <el-alert v-if="error" type="error" :closable="false" show-icon :title="error" />

    <el-card shadow="never">
      <template #header>
        <PageHeader :title="`文档列表（共 ${total} 篇）`">
          <template #default>
            <div class="kd__filters">
              <el-input
                v-model="keyword"
                class="kd__filter"
                clearable
                placeholder="按标题 / 文件名搜索"
                @keydown.enter="knowledgeStore.searchDocuments()"
                @clear="knowledgeStore.searchDocuments()"
              />
              <el-button :loading="loadingDocuments" @click="knowledgeStore.loadDocuments()">刷新</el-button>
            </div>
          </template>
        </PageHeader>
      </template>

      <!-- 空态要区分「一篇都没有」与「筛掉了」：分页后仍说「还没有上传」会让人以为知识库坏了 -->
      <el-empty
        v-if="isEmpty && !loadingDocuments"
        :description="keyword ? '没有匹配的文档' : '还没有上传任何文档'"
        :image-size="70"
      />
      <el-table v-else v-loading="loadingDocuments" :data="documents">
        <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />
        <el-table-column prop="filename" label="文件名" min-width="160" show-overflow-tooltip />
        <el-table-column label="大小" width="100">
          <template #default="{ row }">{{ formatBytes(row.sizeBytes) }}</template>
        </el-table-column>
        <el-table-column label="片段数" width="90">
          <template #header>
            <el-tooltip content="解析后切分的文本块数量，检索以片段为单位" placement="top">
              <span class="kd__column-hint">片段数 ?</span>
            </el-tooltip>
          </template>
          <template #default="{ row }">{{ row.chunkCount }}</template>
        </el-table-column>
        <!--
          字符数列：解析出的文本长度（分块前）。它与「大小」不是一回事 ——
          扫描版 PDF 体积很大但字符数接近 0，一眼能看出「这篇没解析出什么内容」。
          该字段阶段 3 起后端一直没写入（恒为 0），阶段 10 才修复；展示出来也让回归能被肉眼发现。
        -->
        <el-table-column label="字符数" width="100">
          <template #header>
            <el-tooltip
              content="解析出的文本字符数（分块前）；扫描版 PDF 体积大但字符数接近 0，说明没解析出内容"
              placement="top"
            >
              <span class="kd__column-hint">字符数 ?</span>
            </el-tooltip>
          </template>
          <template #default="{ row }">{{ row.charCount }}</template>
        </el-table-column>
        <el-table-column label="上传时间" width="180">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="pickReplaceFile(row.id)">替换</el-button>
            <el-button link type="danger" size="small" @click="handleDelete(row.id, row.title)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 替换用的文件选择器：整个表格共用一个，点「替换」时记住目标行 -->
      <input ref="replaceInput" class="kd__file-input" type="file" :accept="ACCEPT" @change="onReplaceFileChange" />

      <AppPagination
        :total="total"
        :page="page"
        :size="size"
        @change="(next: number) => knowledgeStore.goToPage(next)"
      />
    </el-card>
  </div>
</template>

<style scoped>
.kd {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-4);
}

.kd__filters {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
}

.kd__filter {
  width: 200px;
}

.kd__hint {
  margin: var(--os-space-3) 0 0;
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
  line-height: 1.6;
}

/* ---- 拖拽上传区（C-06）---- */

.kd__dropzone {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--os-space-2);
  padding: var(--os-space-4);
  border: 1px dashed var(--el-border-color);
  border-radius: var(--os-radius-lg);
  transition:
    border-color var(--os-duration-fast) var(--os-ease),
    background var(--os-duration-fast) var(--os-ease);
}

.kd__dropzone--active {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.kd__file-input {
  display: none;
}

.kd__filename {
  min-width: 160px;
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}

.kd__title {
  width: 220px;
}

/* 列头提示：问号 + tooltip，解释这两个不那么直观的列 */
.kd__column-hint {
  cursor: help;
  border-bottom: 1px dashed var(--el-text-color-placeholder);
}
</style>
