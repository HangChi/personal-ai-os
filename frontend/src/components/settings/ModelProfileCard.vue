<script setup lang="ts">
/**
 * 一张模型配置卡片（cc-switch 风格的 provider 卡片）。
 *
 * <h2>为什么卡片而不是表格里的一行</h2>
 * 一套配置有「名称 + 模型 + 密钥状态 + 生效标记」四个需要一眼看到的信息，
 * 外加三个动作（启用 / 编辑 / 删除）。塞进表格会挤成一列列的短文本，
 * 「当前生效」这个最重要的信息只能靠一个小圆点表达。
 * 卡片上可以用边框高亮 + 徽章把「这套正在跑」表达得非常明确。
 *
 * <h2>为什么启用按钮是卡片上的主按钮</h2>
 * 这是整个页面的核心动作 —— 用户来这里就是为了换一套。其余动作
 * （编辑、删除）都是低频的，用 link 按钮收在角落，不抢视觉权重。
 */
import { Check, Delete, Edit } from '@element-plus/icons-vue'
import { computed } from 'vue'

import { findPreset } from '@/lib/provider-presets'
import { hasChatModel, keySummary, modelSummary } from '@/lib/model-profile'
import type { ModelProfile } from '@/types/api'

const props = defineProps<{
  profile: ModelProfile
  /** 切换中（按钮 loading） */
  activating: boolean
  /** 有其它切换正在进行，禁止再点（避免并发触发两次模型重建） */
  disabled: boolean
  /** 不可删除的原因；null 表示可删 */
  deleteBlocked: string | null
}>()

const emit = defineEmits<{
  (e: 'activate'): void
  (e: 'edit'): void
  (e: 'delete'): void
}>()

const preset = computed(() => findPreset(props.profile.presetId))

/** 端点显示：太长时中间省略，域名与路径开头更重要 */
const endpointDisplay = computed(() => {
  const url = props.profile.baseUrl ?? ''
  return url.length > 0 ? url : '使用环境变量中的端点'
})

/**
 * 卡片上是否要显示「未指定模型」这类缺省提示。
 *
 * <p>走 {@code hasChatModel} 而不是再看一遍 {@code chatModel}：有了清单之后，
 * 「有没有模型」的真相在清单里，单值列只是它的第一项。两处各写一套判断
 * 迟早会不一致（表现为文字不灰、却写着「未指定模型」）。
 */
const hasModel = computed(() => hasChatModel(props.profile))
</script>

<template>
  <article class="profile-card" :class="{ 'profile-card--active': profile.active }">
    <header class="profile-card__head">
      <div class="profile-card__title-row">
        <h3 class="profile-card__name">{{ profile.name }}</h3>
        <el-tag v-if="profile.active" size="small" type="success" effect="dark">生效中</el-tag>
        <el-tag v-else-if="preset" size="small" type="info">{{ preset.vendor }}</el-tag>
      </div>
      <div class="profile-card__actions">
        <el-button
          v-if="!profile.active"
          type="primary"
          size="small"
          :loading="activating"
          :disabled="disabled"
          @click="emit('activate')"
        >
          <el-icon v-if="!activating"><Check /></el-icon>
          启用
        </el-button>
        <el-button link :disabled="disabled" @click="emit('edit')">
          <el-icon><Edit /></el-icon>
          编辑
        </el-button>
        <el-tooltip :content="deleteBlocked ?? '删除这套配置'" placement="top">
          <!-- 用 span 包一层：disabled 的按钮不触发 mouseenter，tooltip 会失效 -->
          <span>
            <el-button link type="danger" :disabled="deleteBlocked !== null" @click="emit('delete')">
              <el-icon><Delete /></el-icon>
              删除
            </el-button>
          </span>
        </el-tooltip>
      </div>
    </header>

    <dl class="profile-card__meta">
      <div class="profile-card__meta-item">
        <dt>模型</dt>
        <dd :class="{ 'profile-card__meta-value--missing': !hasModel }">{{ modelSummary(profile) }}</dd>
      </div>
      <div class="profile-card__meta-item">
        <dt>密钥</dt>
        <dd :class="{ 'profile-card__meta-value--missing': !profile.hasApiKey }">
          {{ keySummary(profile) }}
        </dd>
      </div>
      <div class="profile-card__meta-item profile-card__meta-item--wide">
        <dt>端点</dt>
        <dd class="profile-card__endpoint">{{ endpointDisplay }}</dd>
      </div>
    </dl>
  </article>
</template>

<style scoped>
.profile-card {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-3);
  padding: var(--os-space-4);
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--os-radius-lg);
  transition:
    border-color var(--os-duration-fast) var(--os-ease),
    box-shadow var(--os-duration-fast) var(--os-ease);
}

.profile-card:hover {
  box-shadow: var(--os-shadow-card-hover);
}

/*
 * 生效中：左侧绿色竖条 + 绿色细边 + 「生效中」徽章。
 *
 * 这里刻意**不用主色紫**：紫色在本页是「可点击动作」的语义（启用按钮）。
 * 生效状态是**状态**不是动作，用主色会让「这块能点」与「这套在跑」混淆。
 * 竖条提供第二个不依赖颜色的信号（位置），暗色下也读得出。
 *
 * 边框只加重到 light-7 而不是实色：三张卡片并排时，实色边框会让生效卡
 * 看起来像被框死的错误态。淡绿 + 竖条已经足够「一眼看出」。
 *
 * <h2>为什么去掉了底部那行绿字</h2>
 * 原本生效卡多一行「聊天与知识库问答当前使用这套配置」，起初是为了解释
 * 生效范围。但面板导语已经写了同样的话，且这行让生效卡比其它卡高 29px，
 * 只能靠 `min-height` 把三张卡一起撑到 193px → 两张非生效卡底部留白 57px、
 * 生效卡 32px，一眼看去像「下面的卡没填满」。删掉后三张卡内容行数一致、
 * 自然等高，`min-height` 也就不需要了。
 */
.profile-card--active {
  border-color: var(--el-color-success-light-7);
  box-shadow: inset 3px 0 0 0 var(--el-color-success);
}

.profile-card__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--os-space-3);
  flex-wrap: wrap;
  /*
   * 生效卡没有「启用」按钮，头部只剩 28px 的 link 按钮；非生效卡有 32px 的主按钮
   * → 两种卡头部差 4px，卡片总高 153 vs 149，纵向排列时右边缘参差。
   * 用按钮标准高兜住，卡内所有行高低与内容无关地保持一致。
   */
  min-height: var(--os-button-height);
}

.profile-card__title-row {
  display: flex;
  align-items: center;
  gap: var(--os-space-2);
  flex-wrap: wrap;
  min-width: 0;
}

.profile-card__name {
  margin: 0;
  font-size: var(--os-font-md);
  font-weight: 600;
  color: var(--el-text-color-primary);
  word-break: break-word;
}

.profile-card__actions {
  display: flex;
  align-items: center;
  gap: var(--os-button-gap);
  flex-shrink: 0;
}

.profile-card__meta {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--os-space-2) var(--os-space-4);
  margin: 0;
}

.profile-card__meta-item {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.profile-card__meta-item--wide {
  grid-column: 1 / -1;
}

.profile-card__meta dt {
  font-size: var(--os-font-xs);
  color: var(--el-text-color-secondary);
}

.profile-card__meta dd {
  margin: 0;
  font-size: var(--os-font-sm);
  color: var(--el-text-color-primary);
  word-break: break-word;
}

.profile-card__meta-value--missing {
  color: var(--el-text-color-placeholder);
}

.profile-card__endpoint {
  font-family: ui-monospace, monospace;
  font-size: var(--os-font-xs);
  color: var(--el-text-color-regular);
  word-break: break-all;
}

@media (max-width: 768px) {
  .profile-card__meta {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
