<script setup lang="ts">
import { ArrowDown, ArrowRight } from '@element-plus/icons-vue'
import { computed, ref, watch } from 'vue'
import { getPlan } from '@/api/plan'
import type { AgentPlan, PlanStatus, PlanStepStatus } from '@/types/api'

/**
 * 会话执行计划面板（阶段 22）。
 *
 * <h2>为什么放在消息区顶部而不是侧栏</h2>
 * 计划回答的是「这一轮任务做到哪了」，与当前对话的上下文强绑定。放在侧栏会变成
 * 「另一个需要切换过去看的页面」，而用户真正需要的是「读回答时顺带看到进度」。
 *
 * <h2>刷新时机</h2>
 * 只在两个时刻拉取：切换会话、以及一轮结束（streaming 由 true 变 false）。
 * 不做轮询：计划只在工具调用时变化，而工具调用必然发生在流式过程中，
 * 轮询会在每一轮之间空转。
 *
 * <h2>为什么失败步骤单独强调</h2>
 * 计划的价值一半在「知道卡在哪」。把 FAILED 与 DONE 用同一种灰显示，
 * 等于把最重要的信息藏起来。
 */
const props = defineProps<{
  conversationId: string | null
  /** 是否正在流式回复：结束时刷新一次计划 */
  streaming: boolean
}>()

const plan = ref<AgentPlan | null>(null)
const collapsed = ref(false)
const loading = ref(false)

/** 进度：已完成（含跳过）/ 总数 */
const doneCount = computed(
  () => plan.value?.steps.filter((step) => step.status === 'DONE' || step.status === 'SKIPPED').length ?? 0,
)

const statusText = computed<Record<PlanStatus, string>>(() => ({
  ACTIVE: '进行中',
  COMPLETED: '已完成',
  ABORTED: '已放弃',
}))

const statusTagType = computed(() => {
  switch (plan.value?.status) {
    case 'COMPLETED':
      return 'success'
    case 'ABORTED':
      return 'info'
    default:
      return 'primary'
  }
})

/** 步骤状态 → 徽章样式与文案 */
const stepMeta: Record<PlanStepStatus, { label: string; className: string }> = {
  PENDING: { label: '待执行', className: 'is-pending' },
  RUNNING: { label: '进行中', className: 'is-running' },
  DONE: { label: '已完成', className: 'is-done' },
  FAILED: { label: '失败', className: 'is-failed' },
  SKIPPED: { label: '已跳过', className: 'is-skipped' },
}

function metaOf(status: PlanStepStatus): { label: string; className: string } {
  return stepMeta[status]
}

async function load(): Promise<void> {
  if (!props.conversationId) {
    plan.value = null
    return
  }
  loading.value = true
  try {
    plan.value = await getPlan(props.conversationId)
    // 新计划默认展开；已结束的默认收起，避免长期占屏
    if (plan.value) {
      collapsed.value = plan.value.status !== 'ACTIVE'
    }
  } catch {
    // 计划是增强信息：拉取失败不打断对话，也不弹错误（用户会以为是聊天坏了）
    plan.value = null
  } finally {
    loading.value = false
  }
}

watch(() => props.conversationId, load, { immediate: true })

// 一轮结束后刷新：工具调用只发生在流式过程中，结束时是唯一需要更新的时刻。
// 加 600ms 延迟不是为了「等一下」，而是给后端的工具结果落库留出提交时间 ——
// 流关闭与事务提交不保证同序，立即拉取可能拿到「还差最后一步」的旧快照。
watch(
  () => props.streaming,
  (now, before) => {
    if (before && !now) {
      window.setTimeout(load, 600)
    }
  },
)
</script>

<template>
  <section v-if="plan" class="plan" :class="{ 'plan--collapsed': collapsed }" aria-label="执行计划">
    <header class="plan__head">
      <button type="button" class="plan__toggle" :aria-expanded="!collapsed" @click="collapsed = !collapsed">
        <el-icon class="plan__caret"><component :is="collapsed ? ArrowRight : ArrowDown" /></el-icon>
        <span class="plan__title">执行计划</span>
      </button>
      <span class="plan__goal" :title="plan.goal">{{ plan.goal }}</span>
      <span class="plan__progress">{{ doneCount }} / {{ plan.steps.length }}</span>
      <el-tag size="small" :type="statusTagType" effect="plain" round>{{ statusText[plan.status] }}</el-tag>
    </header>

    <ol v-show="!collapsed" class="plan__steps">
      <li v-for="step in plan.steps" :key="step.seq" class="plan__step" :class="metaOf(step.status).className">
        <span class="plan__seq">{{ step.seq }}</span>
        <div class="plan__body">
          <div class="plan__row">
            <span class="plan__step-title">{{ step.title }}</span>
            <span class="plan__badge">{{ metaOf(step.status).label }}</span>
          </div>
          <!-- 工具由执行外壳回填，所以它显示的是「实际调用了什么」而不是模型的自述 -->
          <div v-if="step.toolName" class="plan__tool">调用 {{ step.toolName }}</div>
          <div v-if="step.error" class="plan__error">{{ step.error }}</div>
        </div>
      </li>
    </ol>
  </section>
</template>

<style scoped>
.plan {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--os-radius-lg);
  background: var(--el-bg-color);
  padding: var(--os-space-3) var(--os-space-4);
  margin-bottom: var(--os-space-3);
}

.plan__head {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
  min-width: 0;
}

.plan__toggle {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex: none;
  padding: 0;
  border: none;
  background: none;
  color: var(--el-text-color-primary);
  font-size: var(--os-font-sm);
  font-weight: 500;
  cursor: pointer;
}

.plan__caret {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

/* 目标可能很长：单行截断 + title 兜住全文，不让面板被一句话撑高 */
.plan__goal {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--os-font-sm);
  color: var(--el-text-color-regular);
}

.plan__progress {
  flex: none;
  font-size: var(--os-font-xs);
  font-variant-numeric: tabular-nums;
  color: var(--el-text-color-secondary);
}

.plan__steps {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin: var(--os-space-3) 0 0;
  padding: 0;
  list-style: none;
}

.plan__step {
  display: flex;
  gap: var(--os-space-2);
  padding: 4px 0;
}

.plan__seq {
  flex: none;
  width: 20px;
  height: 20px;
  border-radius: var(--os-radius-sm);
  background: var(--el-fill-color-light);
  color: var(--el-text-color-regular);
  font-size: var(--os-font-xs);
  font-variant-numeric: tabular-nums;
  line-height: 20px;
  text-align: center;
}

.plan__body {
  flex: 1;
  min-width: 0;
}

.plan__row {
  display: flex;
  align-items: baseline;
  gap: var(--os-space-2);
}

.plan__step-title {
  font-size: var(--os-font-sm);
  line-height: 1.5;
  color: var(--el-text-color-primary);
  word-break: break-word;
}

.plan__badge {
  flex: none;
  font-size: var(--os-font-xs);
  color: var(--el-text-color-secondary);
}

.plan__tool,
.plan__error {
  margin-top: 2px;
  font-size: var(--os-font-xs);
  line-height: 1.5;
  word-break: break-word;
}

.plan__tool {
  color: var(--el-text-color-secondary);
}

.plan__error {
  color: var(--el-color-danger);
}

/* 已完成/跳过的步骤整体降级，让「还要做什么」一眼可见 */
.plan__step.is-done .plan__step-title,
.plan__step.is-skipped .plan__step-title {
  color: var(--el-text-color-secondary);
}

.plan__step.is-running .plan__badge {
  color: var(--el-color-primary);
}

.plan__step.is-failed .plan__badge {
  color: var(--el-color-danger);
}

.plan__step.is-done .plan__badge {
  color: var(--el-color-success);
}
</style>
