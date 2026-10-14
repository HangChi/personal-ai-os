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
  const hits = ref<KnowledgeHit[]>([])
  const answer = ref<KnowledgeAskResponse | null>(null)

  const loadingDocuments = ref(false)
  const uploading = ref(false)
  const searching = ref(false)
  const asking = ref(false)
  const error = ref<string | null>(null)

  /** 总片段数：让用户对"知识库有多大"有个直观概念 */
  const totalChunks = computed(() =>
    documents.value.reduce((sum, document) => sum + document.chunkCount, 0),
  )

  const isEmpty = computed(() => documents.value.length === 0)

  async function loadDocuments(): Promise<void> {
    loadingDocuments.value = true
    error.value = null
    try {
      documents.value = await listDocuments()
    } catch (cause) {
      documents.value = []
      error.value = cause instanceof Error ? cause.message : '加载文档列表失败'
    } finally {
      loadingDocuments.value = false
    }
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
    upload,
    remove,
    search,
    ask,
  }
})
