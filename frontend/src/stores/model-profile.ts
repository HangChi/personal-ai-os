import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import {
  activateModelProfile,
  createModelProfile,
  deleteModelProfile,
  listModelProfiles,
  reorderModelProfiles,
  testModelProfileConnection,
  updateModelProfile,
} from '@/api/model-profile'
import { moveItem } from '@/lib/model-profile'
import type { ModelProfile, SaveModelProfilePayload } from '@/types/api'

/**
 * 多套模型配置状态。
 *
 * <h2>为什么状态放 store 而不是组件里</h2>
 * 模型配置有**跨页副作用**：切换生效配置后，聊天页立刻会走新模型，
 * 设置页的「测试连通性」也要用新配置。这些页面都需要知道「当前是哪一套」。
 * 放在页面局部 state 里，每处各拉一遍、各自维护，很容易出现
 * 「A 页说百炼、B 页说 DeepSeek」的错位。
 *
 * <h2>为什么会有乐观更新（只限顺序）</h2>
 * 拖拽排序若等服务端往返才重排，卡片会先弹回原位再跳到新位 —— 一次明显的抖动。
 * 顺序是**纯展示状态**、失败后果极轻（重新拉一次就好），因此先本地生效、
 * 失败再回滚。其余操作（启用/删除/编辑）不做乐观更新：
 * 它们改变的是「哪套配置真的在跑」，猜错会让界面撒谎。
 */
export const useModelProfileStore = defineStore('modelProfile', () => {
  const profiles = ref<ModelProfile[]>([])
  const activeId = ref<string | null>(null)
  const loading = ref(false)
  const loadError = ref<string | null>(null)
  /** 正在进行的切换目标 id（用于按钮 loading，避免重复点） */
  const activatingId = ref<string | null>(null)

  const activeProfile = computed(() => profiles.value.find((profile) => profile.id === activeId.value) ?? null)

  /** 是否还没有任何配置（首次进入应引导用户从预设库里选一个） */
  const isEmpty = computed(() => profiles.value.length === 0)

  async function load(): Promise<void> {
    loading.value = true
    loadError.value = null
    try {
      const data = await listModelProfiles()
      profiles.value = data.profiles
      activeId.value = data.activeId
    } catch (cause) {
      loadError.value = cause instanceof Error ? cause.message : '读取模型配置失败'
    } finally {
      loading.value = false
    }
  }

  /**
   * 启用一套配置。
   *
   * <p>失败时也要重新拉列表：后端「切换已落库、但按该配置初始化模型失败」
   * 会返回错误，而数据库里 active 其实已经变了。不重拉的话界面会停在旧状态，
   * 与真实情况相反 —— 用户会以为没切换成功，再点一次。
   *
   * @returns 成功时返回 null，失败时返回错误信息
   */
  async function activate(id: string): Promise<string | null> {
    if (activatingId.value !== null) {
      return '正在切换中，请稍候'
    }
    activatingId.value = id
    try {
      await activateModelProfile(id)
      await load()
      return null
    } catch (cause) {
      await load()
      return cause instanceof Error ? cause.message : '切换失败'
    } finally {
      activatingId.value = null
    }
  }

  /**
   * 新建配置。
   *
   * @returns 错误信息；成功返回 null
   */
  async function create(payload: SaveModelProfilePayload): Promise<string | null> {
    try {
      await createModelProfile(payload)
      await load()
      return null
    } catch (cause) {
      return cause instanceof Error ? cause.message : '新建失败'
    }
  }

  /**
   * 更新配置。
   *
   * @returns 错误信息；成功返回 null
   */
  async function update(id: string, payload: SaveModelProfilePayload): Promise<string | null> {
    try {
      await updateModelProfile(id, payload)
      await load()
      return null
    } catch (cause) {
      // 与 activate 同理：更新生效配置失败时，配置内容可能已经落库
      await load()
      return cause instanceof Error ? cause.message : '保存失败'
    }
  }

  /**
   * 删除配置。
   *
   * @returns 错误信息；成功返回 null
   */
  async function remove(id: string): Promise<string | null> {
    try {
      await deleteModelProfile(id)
      await load()
      return null
    } catch (cause) {
      return cause instanceof Error ? cause.message : '删除失败'
    }
  }

  /**
   * 拖拽换位（乐观更新）。
   *
   * <p>先本地重排让卡片立刻跟手，再提交；失败则重新拉回真实顺序。
   *
   * @returns 错误信息；成功返回 null
   */
  async function move(fromIndex: number, toIndex: number): Promise<string | null> {
    const before = profiles.value
    const reordered = moveItem(
      before.map((profile) => profile.id),
      fromIndex,
      toIndex,
    )
    profiles.value = reordered
      .map((id) => before.find((profile) => profile.id === id))
      .filter((profile): profile is ModelProfile => profile !== undefined)
      .map((profile, index) => ({ ...profile, sortOrder: index }))

    try {
      const data = await reorderModelProfiles(reordered)
      profiles.value = data.profiles
      activeId.value = data.activeId
      return null
    } catch (cause) {
      // 回滚到服务端的真实顺序，而不是回滚到 `before`：
      // `before` 可能已经是过期快照（别人改过）。重拉永远是对的。
      await load()
      return cause instanceof Error ? cause.message : '调整顺序失败'
    }
  }

  /** 用当前生效配置测连通性 */
  async function test(): Promise<Awaited<ReturnType<typeof testModelProfileConnection>>> {
    return testModelProfileConnection()
  }

  return {
    profiles,
    activeId,
    activeProfile,
    isEmpty,
    loading,
    loadError,
    activatingId,
    load,
    activate,
    create,
    update,
    remove,
    move,
    test,
  }
})
