<script setup lang="ts">
/**
 * 知识库 · 文档 Tab（上传 + 列表）。
 *
 * <p>从 KnowledgeView 拆出（2026-10-26）。上传相关的三条路径都在这里：
 * 点击选择、拖拽投放、以及两者的公共校验。校验刻意只写一份 ——
 * 拖拽与点击走同一个 `selectFile`，避免「拖进来能传、点进来不能传」这类不一致。
 */
import { ElMessage } from 'element-plus'
import { storeToRefs } from 'pinia'
import { onMounted, ref } from 'vue'

import AppPagination from '@/components/AppPagination.vue'
import PageHeader from '@/components/PageHeader.vue'
import { confirmDanger } from '@/lib/confirm-box'
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
  // 替换同样是「一步做错就回不去」的动作（旧分块与向量整组丢弃），走危险确认
  const ok = await confirmDanger({
    title: '替换文档内容',
    message:
      `用「${file.name}」替换该文档的内容？\n\n` +
      '文档本身与历史对话里的引用都会保留；旧的分块与向量会被整组替换，' +
      '替换后需要重新建立索引（耗时取决于文件大小）。',
    confirmText: '开始替换',
  })
  if (!ok) {
    return
  }
  const replaced = await knowledgeStore.replace(id, file)
  if (replaced) {
    ElMessage.success('已替换文档内容，向量已重建')
  }
}

async function handleDelete(id: string, title: string): Promise<void> {
  const ok = await confirmDanger({
    title: '删除知识文档',
    message: `删除「${title}」会同时清理它的全部向量，之后将无法再被检索到。此操作不可恢复。`,
    confirmText: '删除',
  })
  if (!ok) {
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
        <!--
          列宽分配（2026-10-29 按实测重算，表宽 998）。

          改造前：六列全写死 width，只有标题列 min-width。EP 把剩下的空间
          全塞给标题，但那时固定列合计 756，标题只拿到 242px —— 而标题的
          **内容**实测需要 341px，于是「列宽看着富裕、标题却被截断」，同时
          数字列 80px 里只放「1.9 KB」（内容 51px）。相邻列文字间距最大 84px。

          现在的原则：**只在唯一一列上留弹性**，其余列按实测内容压到刚好。
          - 标题 = min-width（唯一弹性列，吃掉全部剩余；实测拿到 346px，
            已略大于内容所需的 341px，长标题不再截断）；
          - 文件名 150：够显示 juejin-7672711130193936424 这类写入标识符，
            更长的走 `show-overflow-tooltip`；
          - 数字列 80（表头 53 + 内边距 24 = 77，留 3 余量）；
          - 上传时间 166（内容 139 + 24）、操作 96（两个 link 按钮实测 88）。

          固定列合计 150+80+80+80+166+96 = 652，余下 346 全给标题。

          **不要把这些列改成 min-width**：试过全部改 min-width，EP 会按
          minWidth 比例**给每列都分富余**，标题反而从 346 掉到 238 ——
          长文本列必须独占弹性，「均摊」在这里是反效果。
        -->
        <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
        <el-table-column prop="filename" label="文件名" width="150" show-overflow-tooltip />
        <el-table-column label="大小" width="80" align="right">
          <template #default="{ row }">{{ formatBytes(row.sizeBytes) }}</template>
        </el-table-column>
        <el-table-column label="片段数" width="80" align="right">
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
        <el-table-column label="字符数" width="80" align="right">
          <template #header>
            <el-tooltip
              content="解析出的文本字符数（分块前）；扫描版 PDF 体积大但字符数接近 0，说明没解析出内容"
              placement="top"
            >
              <span class="kd__column-hint">字符数 ?</span>
            </el-tooltip>
          </template>
          <template #default="{ row }">{{ row.charCount.toLocaleString('zh-CN') }}</template>
        </el-table-column>
        <!--
          152px 是「刚好」放下 2026/10/26 02:04:54 的宽度，结果每行都折成两行、
          把行高撑到 59px。给到 164px 留出余量（字体回退、locale 差异都吃得下）。
        -->
        <el-table-column label="上传时间" width="166">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="96" fixed="right">
          <template #default="{ row }">
            <div class="os-table-actions">
              <el-button link type="primary" size="small" @click="pickReplaceFile(row.id)">替换</el-button>
              <el-button link type="danger" size="small" @click="handleDelete(row.id, row.title)">删除</el-button>
            </div>
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
  flex: 1 1 160px;
  min-width: 0;
  overflow-wrap: anywhere;
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

@media (max-width: 600px) {
  .kd__filters {
    width: 100%;
    min-width: 0;
  }

  .kd__filter {
    flex: 1;
    min-width: 0;
    width: auto;
  }

  .kd__dropzone .kd__title,
  .kd__dropzone > .el-button:last-child {
    width: 100%;
    margin-left: 0;
  }
}

/* 操作列样式见 theme.css 的 `.os-table-actions`（与任务表格共用同一份） */
</style>
