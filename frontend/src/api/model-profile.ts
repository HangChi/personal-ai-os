import { requestData } from '@/api/http'
import type {
  ModelListResult,
  ModelProfile,
  ModelProfileList,
  ModelProtocol,
  SaveModelProfilePayload,
} from '@/types/api'

/**
 * 多套模型配置接口客户端（设置页 → 模型配置）。
 *
 * 后端契约见 `ModelProfileController`（`/api/v1/settings/model/profiles`）。
 *
 * <h2>安全边界</h2>
 * 后端任何响应都不含明文密钥，只回 `****8f3a` 掩码。因此本模块没有任何
 * 「读回密钥」的函数 —— 读不到，也不需要：表单密钥框留空就是「不改动」。
 *
 * <h2>超时口径</h2>
 * 纯读写接口用全局默认；`activate` 与 `update` 放宽到 30 秒，
 * 因为它们内部可能重建模型实例（跨境端点偶发慢），
 * 而 10 秒会让「明明保存成功」被前端判成超时。
 */

/** 配置列表 + 当前生效者 */
export function listModelProfiles(): Promise<ModelProfileList> {
  return requestData<ModelProfileList>({ url: '/settings/model/profiles', method: 'get' })
}

/**
 * 新建一套配置。
 *
 * <p>新建**不会自动启用**：从预设库加一套备份配置不该把正在用的那套换掉。
 * 要切换请调 {@link activateModelProfile}。
 */
export function createModelProfile(payload: SaveModelProfilePayload): Promise<ModelProfile> {
  return requestData<ModelProfile>({
    url: '/settings/model/profiles',
    method: 'post',
    data: payload,
  })
}

/**
 * 更新一套配置。
 *
 * <p>若该配置正生效，后端会顺手重建模型（30 秒超时就是为这一步留的）。
 */
export function updateModelProfile(id: string, payload: SaveModelProfilePayload): Promise<ModelProfile> {
  return requestData<ModelProfile>({
    url: `/settings/model/profiles/${id}`,
    method: 'put',
    data: payload,
    timeout: 30_000,
  })
}

/**
 * 启用某一套配置并立即生效。
 *
 * <p>失败（如端点连不上）时后端返回业务错误，但**数据库里的切换已提交** ——
 * 前端要重新拉列表，否则界面会停在旧状态。
 */
export function activateModelProfile(id: string): Promise<ModelProfile> {
  return requestData<ModelProfile>({
    url: `/settings/model/profiles/${id}/activate`,
    method: 'post',
    timeout: 30_000,
  })
}

/** 删除一套配置。生效中的与最后一套会被后端拒绝（前端也提前拦）。 */
export function deleteModelProfile(id: string): Promise<{ deleted: boolean }> {
  return requestData<{ deleted: boolean }>({
    url: `/settings/model/profiles/${id}`,
    method: 'delete',
  })
}

/**
 * 调整顺序。
 *
 * <p>提交的是**完整期望顺序**而不是相对指令：相对指令在有并发或失败重试时
 * 会累积成错乱顺序，而绝对顺序是幂等的。
 */
export function reorderModelProfiles(ids: string[]): Promise<ModelProfileList> {
  return requestData<ModelProfileList>({
    url: '/settings/model/profiles/order',
    method: 'put',
    data: { ids },
  })
}

/** 用当前生效配置发一次最小请求（可能计费，故用 POST 不被缓存） */
export function testModelProfileConnection(): Promise<import('@/types/api').ModelConnectionTestResult> {
  return requestData({ url: '/settings/model/profiles/test', method: 'post', timeout: 30_000 })
}

/**
 * 探测某个端点支持哪些模型（cc-switch 的「获取模型列表」）。
 *
 * <h2>为什么可以传「还没保存的草稿」</h2>
 * 用户通常是在填表过程中点这个按钮：先填端点与密钥，看看这个端点有什么模型，
 * 再从里面挑。若必须先保存才能探测，「新建一套」就变成「先瞎填一个模型名保存
 * → 再回来探测 → 再改」，而瞎填模型名正是要避免的事。
 *
 * <h2>为什么 {@code profileId} 要一起传</h2>
 * 编辑已有配置时密钥框按安全约定永不回填（前端拿到空串）。传 {@code profileId}
 * 让后端用该配置已保存的密钥探测，用户不必重新输一遍 key。
 *
 * <p>返回 {@code ok=false} 属正常情况：很多端点没有 {@code /models} 接口，
 * 但能正常聊天。此时照常手打模型名即可。
 *
 * @param payload 协议 / 端点 / 密钥 / 编辑中的配置 id
 */
export function probeModelList(payload: {
  protocol?: ModelProtocol | null
  baseUrl?: string | null
  apiKey?: string | null
  profileId?: string | null
  /**
   * 当前配置已选中的模型名。
   *
   * <p>后端要用它决定「优先给哪几个模型补查上下文窗口」。不传的话后端只能
   * 按字母序取前 40 个，而大端点上真正在用的模型常排在一百名开外
   * （DashScope 实测：qwen3.8-flash 第 148 位，qwen-max 第 65 位）——
   * 表现为「有些模型的窗口能自动识别、有些恒为空」。
   */
  models?: string[]
}): Promise<ModelListResult> {
  return requestData<ModelListResult>({
    url: '/settings/model/profiles/probe-models',
    method: 'post',
    data: payload,
    // 后端硬卡 10 秒，这里放宽到 15 秒：留足网络与序列化开销，
    // 又比后端的 10 秒长，确保超时由后端报出可读原因而不是前端 AbortError
    timeout: 15_000,
  })
}
