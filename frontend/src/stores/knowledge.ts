import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import {
  askKnowledge,
  deleteDocument,
  listDocuments,
  searchKnowledge,
  uploadDocument,
} from '@/api/knowledge'
import type {
  KnowledgeAskResponse,
  KnowledgeDocument,
  KnowledgeHit,
} from '@/types/api'

/** 知识库状态 */
export const useKnowledgeStore = defineStore('knowledge', () => {
  const documents = ref<KnowledgeDocument[]>([])
  /** 符合条件的文档总数（分页响应带来） */
  const total = ref(0)
  /** 当前页码，从 0 开始 */
  const page = ref(0)
  /** 每页条数 */
  const size = ref(20)
  /** 标题/文件名关键词 */
  const keyword = ref('')
  const hits = ref<KnowledgeHit[]>([])
  const answer = ref<KnowledgeAskResponse | null>(null)

  const loadingDocuments = ref(false)
  const uploading = ref(false)
  const searching = ref(false)
  const asking = ref(false)
  const error = ref<string | null>(null)

  /** 当前页的片段总数。注意：分页后这只是**这一页**的规模，不是整个知识库 */
  const totalChunks = computed(() =>
    documents.value.reduce((sum, document) => sum + document.chunkCount, 0),
  )

  const isEmpty = computed(() => documents.value.length === 0)

  async function loadDocuments(targetPage: number = page.value): Promise<void> {
    loadingDocuments.value = true
    error.value = null
    try {
      const result = await listDocuments({
        keyword: keyword.value.trim() || undefined,
        page: targetPage,
        size: size.value,
      })
      documents.value = result.items
      page.value = result.page
      size.value = result.size
      total.value = result.total
    } catch (cause) {
      documents.value = []
      total.value = 0
      error.value = cause instanceof Error ? cause.message : '加载文档列表失败'
    } finally {
      loadingDocuments.value = false
    }
  }

  /** 关键词变化：先归零页码再加载，否则会停在越界页看到空表 */
  async function searchDocuments(): Promise<void> {
    page.value = 0
    await loadDocuments(0)
  }

  async function goToPage(next: number): Promise<void> {
    await loadDocuments(next)
  }

  async function upload(file: File, title?: string): Promise<boolean> {
    uploading.value = true
    error.value = null
    try {
      await uploadDocument(file, title)
      await loadDocuments()
      return true
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '上传失败'
      return false
    } finally {
      uploading.value = false
    }
  }

  async function remove(id: string): Promise<number | null> {
    error.value = null
    try {
      const result = await deleteDocument(id)
      await loadDocuments()
      // 删除后清空上一次的检索/问答结果，避免展示已经不存在的来源
      hits.value = []
      answer.value = null
      return result.deletedChunks
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '删除失败'
      return null
    }
  }

  async function search(query: string, topK?: number): Promise<void> {
    searching.value = true
    error.value = null
    try {
      hits.value = (await searchKnowledge(query, topK)).hits
    } catch (cause) {
      hits.value = []
      error.value = cause instanceof Error ? cause.message : '检索失败'
    } finally {
      searching.value = false
    }
  }

  async function ask(question: string, topK?: number): Promise<void> {
    asking.value = true
    error.value = null
    try {
      answer.value = await askKnowledge(question, topK)
    } catch (cause) {
      answer.value = null
      error.value = cause instanceof Error ? cause.message : '问答失败'
    } finally {
      asking.value = false
    }
  }

  return {
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
    totalChunks,
    isEmpty,
    loadDocuments,
    searchDocuments,
    goToPage,
    upload,
    remove,
    search,
    ask,
  }
})
