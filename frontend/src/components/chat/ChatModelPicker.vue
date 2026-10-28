<script setup lang="ts">
/**
 * 会话级模型选择器（V23）：给这个对话单独挑一个模型。
 *
 * <h2>为什么放在对话顶栏而不是输入区</h2>
 * 输入区承载的是「这一条消息怎么发」（知识库、思考程度、附件），
 * 而模型是**整个会话**的属性 —— 换一次之后后面所有轮次都用它。
 * 两者语义层级不同，混在一行会让人以为「模型只对这一条消息生效」。
 *
 * <h2>为什么默认项叫「跟随配置」而不是把默认模型名写死</h2>
 * 会话可以不选模型（= 用当前生效配置的默认模型）。若这里显示具体模型名，
 * 用户在设置页换掉默认模型后，这个下拉还写着旧名字 —— 但实际已经在用新模型了。
 * 「跟随配置」把「我没指定」这个事实如实表达出来，并让变化自动生效。
 */
import { computed } from 'vue'

import { sessionModelOptions } from '@/lib/model-profile'

const props = defineProps<{
  /** 会话当前选中的模型；null 表示跟随生效配置的默认模型 */
  modelValue: string | null
  /** 可选项：当前生效配置里的对话模型清单 */
  models: string[]
  /** 生效配置的默认模型名（清单为空时用它做提示） */
  defaultModel: string
  /** 生成中：禁止切换 */
  disabled: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: string | null): void
}>()

/** 下拉里那份模型清单（含「已从配置里删掉但会话还记着」的那一项） */
const options = computed<string[]>(() => sessionModelOptions(props.models, props.modelValue))

/** 「跟随配置」那一项右侧的说明：告诉用户跟随的到底是什么 */
const followHint = computed(() => {
  const fallback = (props.defaultModel ?? '').trim()
  return fallback.length > 0 ? `当前为 ${fallback}` : '生效配置未指定默认模型'
})

/** 下拉当前的展示值：null（跟随）在 el-select 里需要一个具体值才能回显 */
const selected = computed<string>({
  get: () => (props.modelValue ?? '').trim(),
  set: (value: string) => emit('update:modelValue', value.trim().length === 0 ? null : value.trim()),
})

/**
 * 清单为空时是否禁用。
 *
 * <p>禁用而不是隐藏：隐藏会让用户以为「这个功能不存在」，
 * 而实际情况是「这套配置还没配模型，去设置页加一个就有了」。
 */
const blocked = computed(() => props.disabled || (options.value.length === 0 && selected.value.length === 0))
/**
 * 空清单时给出说明，而不只是禁用。
 *
 * <h3>为什么不用 el-tooltip 包住整个下拉</h3>
 * 实测过：tooltip 挂在 select 上时，鼠标移入出提示 → 点击展开下拉，
 * 提示**不会消失**（鼠标仍在 select 上），而它正好浮在下拉列表第一项上方
 * （实测 tooltip rect y=131..163，dropdown y=132..280，重叠 31px），
 * 直接把「跟随配置」那一项盖住。改用原生 `title`：它由浏览器渲染、
 * 只在悬停且无其它浮层时出现，不会与下拉抢位置。
 *
 * <p>提示内容仍然必要 —— 「只影响这个会话」是这个功能最容易被误解的点，
 * 用户很容易以为它改的是全局配置。
 */
const hint = computed(() =>
  options.value.length === 0
    ? '当前生效的模型配置里还没有对话模型，去「设置 → 模型配置」加一个'
    : '只影响这个会话，其它会话各自独立',
)
</script>

<template>
  <el-select
    v-model="selected"
    class="model-picker"
    size="small"
    :disabled="blocked"
    :teleported="true"
    :title="hint"
    placeholder="跟随配置"
    aria-label="本会话使用的模型"
  >
    <el-option label="跟随配置" :value="''">
      <span class="model-picker__option">跟随配置</span>
      <span class="model-picker__hint">{{ followHint }}</span>
    </el-option>
    <el-option v-for="name in options" :key="name" :label="name" :value="name" />
  </el-select>
</template>

<style scoped>
/*
 * 宽度上限而不是固定宽度：`qwen3.8-flash` 与 `qwen3.8-text-embedding-flash`
 * 长度差一倍，固定宽度会把长的截断、短的留一片空白。
 *
 * 上限从 180 收到 160：它会与「附件」「知识库增强」并排出现在输入区工具栏，
 * 180 会让工具栏在窄屏（<1100px）提前折行，而折行后 chip 与下拉分处两行、
 * 高度不齐，比截断几个字符更难看。
 */
.model-picker {
  width: 160px;
  max-width: 100%;
}

/*
 * 高度对齐顶栏的搜索框（实测 28px）。
 *
 * 必须写在 `:deep(.el-select__wrapper)` 上而不是 `.model-picker` 上：
 * `size="small"` 的 `el-select` 高度由内部 wrapper 决定，外层 div 只是跟着内容走
 * —— 给外层写 `min-height` 只会让下面多出一条空白，框本身仍是 24px
 * （实测未改前：`.el-select` 24px，`.el-input` 28px，一眼看出矮一截）。
 *
 * 平铺写法（不嵌套在 `.model-picker { :deep(...) }` 里）：嵌套会让 Vue 编译时
 * 丢掉宿主类名，特异性掉到 0-2-0，与 EP 打平后因加载顺序被静默覆盖。
 */
.model-picker :deep(.el-select__wrapper) {
  min-height: var(--os-button-height-inline);
}

/*
 * 下拉里的「跟随配置」项：标题 + 右侧灰字说明。
 * 用 inline-flex 让说明紧跟标题，而不是被撑到最右（那会让人以为是两列）。
 */
.model-picker__option {
  margin-right: var(--os-space-2);
}

.model-picker__hint {
  font-size: var(--os-font-xs);
  color: var(--el-text-color-secondary);
}
</style>
