import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import {
  askKnowledge,
  deleteDocument,
  listDocuments,
  searchKnowledge,
  updateDocument,
  uploadDocument,
} from '@/api/knowledge'
import type { KnowledgeAskResponse, KnowledgeDocument, KnowledgeHit } from '@/types/api'

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
  /** 替换是「针对某一行」的长操作，与上传互不影响，单独一个 loading */
  const replacing = ref(false)
  const searching = ref(false)
  const asking = ref(false)
  /** 文档列表（上传/替换/删除/加载）的错误 */
  const error = ref<string | null>(null)

  /**
   * 检索 / 问答各自独立的错误（审查 #17）。
   *
   * <p>改造前三者共用同一个 {@code error}：提问失败会把检索 Tab 也标成失败，
   * 反之亦然 —— 三个互不相干的操作被一条错误串在一起。拆开之后，
   * 各自的失败只在各自的 Tab 里显示。
   */
  const searchError = ref<string | null>(null)
  const askError = ref<string | null>(null)
  /** 检索是否真的发过请求（区分「还没搜」与「搜了没结果」） */
  const searched = ref(false)

  /** 当前页的片段总数。注意：分页后这只是**这一页**的规模，不是整个知识库 */
  const totalChunks = computed(() => documents.value.reduce((sum, document) => sum + document.chunkCount, 0))

  const isEmpty = computed(() => documents.value.length === 0)

  let loadDocumentsRequestSeq = 0

  async function loadDocuments(targetPage: number = page.value): Promise<void> {
    const requestSeq = ++loadDocumentsRequestSeq
    loadingDocuments.value = true
    error.value = null
    try {
      const result = await listDocuments({
        keyword: keyword.value.trim() || undefined,
        page: targetPage,
        size: size.value,
      })
      if (requestSeq !== loadDocumentsRequestSeq) return
      documents.value = result.items
      page.value = result.page
      size.value = result.size
      total.value = result.total
    } catch (cause) {
      if (requestSeq !== loadDocumentsRequestSeq) return
      documents.value = []
      total.value = 0
      error.value = cause instanceof Error ? cause.message : '加载文档列表失败'
    } finally {
      if (requestSeq === loadDocumentsRequestSeq) loadingDocuments.value = false
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

  /**
   * 用新文件替换文档内容。
   *
   * <p>与「删了重传」的区别是 id 不变，因此历史对话里的引用仍然指向这份文档。
   * 替换后要重载列表：标题、片段数、字符数都会变。
   */
  async function replace(id: string, file: File): Promise<boolean> {
    replacing.value = true
    error.value = null
    try {
      await updateDocument(id, file)
      await loadDocuments()
      // 内容变了，上一轮的检索/问答结果已经过时
      hits.value = []
      answer.value = null
      return true
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '替换失败'
      return false
    } finally {
      replacing.value = false
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

  let searchRequestSeq = 0

  async function search(query: string, topK?: number): Promise<void> {
    const requestSeq = ++searchRequestSeq
    searching.value = true
    searchError.value = null
    try {
      const result = await searchKnowledge(query, topK)
      if (requestSeq !== searchRequestSeq) return
      hits.value = result.hits
      searched.value = true
    } catch (cause) {
      if (requestSeq !== searchRequestSeq) return
      hits.value = []
      searched.value = true
      searchError.value = cause instanceof Error ? cause.message : '检索失败'
    } finally {
      if (requestSeq === searchRequestSeq) searching.value = false
    }
  }

  let askRequestSeq = 0

  async function ask(question: string, topK?: number): Promise<void> {
    const requestSeq = ++askRequestSeq
    asking.value = true
    askError.value = null
    try {
      const result = await askKnowledge(question, topK)
      if (requestSeq !== askRequestSeq) return
      answer.value = result
    } catch (cause) {
      if (requestSeq !== askRequestSeq) return
      answer.value = null
      askError.value = cause instanceof Error ? cause.message : '问答失败'
    } finally {
      if (requestSeq === askRequestSeq) asking.value = false
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
    searchError,
    askError,
    searched,
    totalChunks,
    isEmpty,
    loadDocuments,
    searchDocuments,
    goToPage,
    upload,
    replace,
    replacing,
    remove,
    search,
    ask,
  }
})
