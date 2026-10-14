import { requestData } from '@/api/http'
import type {
  KnowledgeAskResponse,
  KnowledgeDocument,
  KnowledgeSearchResponse,
} from '@/types/api'

/**
 * 知识库接口客户端。
 */

/** 文档列表，按上传时间倒序 */
export function listDocuments(): Promise<KnowledgeDocument[]> {
  return requestData<KnowledgeDocument[]>({ url: '/knowledge/documents', method: 'get' })
}

/**
 * 上传文档并建立索引。
 *
 * 不手动设置 Content-Type：multipart 的 boundary 必须由浏览器计算，
 * 手动指定反而会让服务端无法解析（见 api/http.ts 的注释）。
 */
export function uploadDocument(file: File, title?: string): Promise<KnowledgeDocument> {
  const form = new FormData()
  form.append('file', file)
  if (title && title.trim()) {
    form.append('title', title.trim())
  }
  return requestData<KnowledgeDocument>({
    url: '/knowledge/documents',
    method: 'post',
    data: form,
    // 建立索引可能耗时（解析 + 分块 + 向量化），给一个更宽的超时
    timeout: 120_000,
  })
}

export interface DeleteDocumentResult {
  deleted: boolean
  deletedChunks: number
}

export function deleteDocument(id: string): Promise<DeleteDocumentResult> {
  return requestData<DeleteDocumentResult>({
    url: `/knowledge/documents/${id}`,
    method: 'delete',
  })
}

/** 检索（不触发模型生成） */
export function searchKnowledge(query: string, topK?: number): Promise<KnowledgeSearchResponse> {
  return requestData<KnowledgeSearchResponse>({
    url: '/knowledge/search',
    method: 'post',
    data: { query, topK },
    timeout: 30_000,
  })
}

/** 基于知识库问答，返回回答与引用 */
export function askKnowledge(question: string, topK?: number): Promise<KnowledgeAskResponse> {
  return requestData<KnowledgeAskResponse>({
    url: '/knowledge/ask',
    method: 'post',
    data: { question, topK },
    // 需要调用生成模型，耗时可能较长
    timeout: 120_000,
  })
}
