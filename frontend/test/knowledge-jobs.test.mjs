import { createPinia, setActivePinia } from 'pinia'
import { useKnowledgeStore } from '@/stores/knowledge'
import { eq } from './assert.mjs'
import { handlers } from './stub-api.mjs'

setActivePinia(createPinia())
const store = useKnowledgeStore()
const job = { id: 'job', documentId: 'doc', filename: 'test.md', status: 'PENDING', progress: 0, stage: '等待执行' }
const keys = []
let fail = true
handlers.set('/knowledge/jobs', (config) => {
  if (config.method === 'get') return Promise.resolve([{ ...job }])
  keys.push(config.headers['Idempotency-Key'])
  if (fail) return Promise.reject(new Error('响应丢失'))
  return Promise.resolve({ ...job })
})
const file = new File(['足够的文件内容'], 'test.md')
eq('提交失败保留可重试状态', await store.upload(file), false)
fail = false
eq('成功入队后立即返回，不等待模型', await store.upload(file), true)
eq('提交重试复用标识', keys[0], keys[1])
eq('入队后展示持久化任务', store.indexJobs[0].status, 'PENDING')
let reloads = 0
handlers.set('/knowledge/documents/search', () => {
  reloads++
  return Promise.resolve({ items: [{ id: 'doc' }], page: 0, size: 20, total: 1 })
})
store.hits = [{ id: 'old' }]
job.status = 'SUCCEEDED'
await store.loadJobs()
eq('任务完成后刷新文档列表', reloads, 1)
eq('完成后清空旧检索结果', store.hits.length, 0)
await store.loadJobs()
eq('相同终态不反复刷新列表', reloads, 1)
handlers.set('/knowledge/jobs', () => Promise.reject(new Error('进度查询失败')))
await store.loadJobs()
eq('查询失败保留已知任务', store.indexJobs.length, 1)
eq('进度错误独立展示', store.jobsError, '进度查询失败')
handlers.delete('/knowledge/jobs')
handlers.delete('/knowledge/documents/search')
