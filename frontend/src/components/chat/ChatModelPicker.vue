<script setup lang="ts">
/**
 * 会话级模型选择器（V23）：给这个对话单独挑一个模型。
 *
 * <h2>为什么放在输入区而不是对话顶栏</h2>
 * 输入区承载的是「这一条消息怎么发」（知识库、思考程度、附件），
 * 而模型是**整个会话**的属性 —— 换一次之后后面所有轮次都用它。
 * 两者语义层级不同，混在一行会让人以为「模型只对这一条消息生效」。
 *
 * <h2>为什么不显示「跟随配置」而是直接显示模型名</h2>
 * 这是 2026-10-28 用户明确要求的（「模型不要显示跟随配置，显示当前用的模型」）。
 * 原设计是：会话没选模型时下拉回显「跟随配置」，右侧小字注明「当前为 xxx」。
 * 问题在于它把一个**内部表示**推到了界面上 —— 用户想知道的是「现在是谁在答」，
 * 而不是「我有没有显式指定过」。主流 agent（ChatGPT / Claude / Cursor /
 * WorkBuddy）的模型选择器都直接显示模型名，跟随逻辑是后台行为，不占标签。
 *
 * <p>跟随语义没丢，只是换了表达：下拉里仍然有一项「跟随生效配置」，
 * 选中它就把会话的模型清空（回到跟随），回显则永远是具体模型名。
 * 区别在于**回显**与**选项**从此是两件事，不再用同一个字符串兼职。
 */
import { computed } from 'vue'

import { effectiveChatModel, sessionModelOptions } from '@/lib/model-profile'

const props = defineProps<{
  /** 会话当前选中的模型；null 表示跟随生效配置的默认模型 */
  modelValue: string | null
  /** 可选项：当前生效配置里的对话模型清单 */
  models: string[]
  /** 生效配置的默认模型名 */
  defaultModel: string
  /** 生成中：禁止切换 */
  disabled: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: string | null): void
}>()

/**
 * 「跟随」选项的哨兵值。
 *
 * <p>不能用空串：`el-select` 的 `value=""` 在部分版本里与「未选」同义，
 * 会让 v-model 表现为 null 而不是「选了跟随」；也不能用 `null`（同上）。
 * 用一个不可能与真实模型名冲突的前缀字符串，选中后立刻翻译成 null。
 */
const FOLLOW = '__follow__'

/** 下拉里那份模型清单（含「已从配置里删掉但会话还记着」的那一项） */
const options = computed<string[]>(() => sessionModelOptions(props.models, props.modelValue))

/** 回显值：永远是具体模型名，不是「跟随配置」 */
const displayModel = computed(() => effectiveChatModel(props.models, props.modelValue, props.defaultModel))

/**
 * `el-select` 的 v-model 桥接。
 *
 * <p>读：会话显式选了 → 那个值；没选（null）→ 哨兵 FOLLOW（即回显当前默认模型，
 * 同时菜单里「跟随生效配置」那一项显示为已选中）。
 * 写：收到哨兵 → 发 null（清空会话选择，回到跟随）；否则发模型名。
 */
const selected = computed<string>({
  get: () => (props.modelValue ?? '').trim() || FOLLOW,
  set: (value: string) => emit('update:modelValue', value === FOLLOW ? null : value.trim()),
})

/** 清单为空且没有任何可跟随的模型：整块禁用，不是隐藏 */
const blocked = computed(() => props.disabled || displayModel.value.length === 0)

/** 是否处于「跟随」状态：给跟随选项打勾，并决定下拉项的说明文字 */
const following = computed(() => (props.modelValue ?? '').trim().length === 0)

/**
 * 空清单时给出说明，而不只是禁用。
 *
 * <h3>为什么不用 el-tooltip 包住整个下拉</h3>
 * 实测过：tooltip 挂在 select 上时，鼠标移入出提示 → 点击展开下拉，
 * 提示**不会消失**（鼠标仍在 select 上），而它正好浮在下拉列表第一项上方
 * （实测 tooltip rect y=131..163，dropdown y=132..280，重叠 31px），
 * 直接把第一项盖住。改用原生 `title`：它由浏览器渲染、
 * 只在悬停且无其它浮层时出现，不会与下拉抢位置。
 */
const hint = computed(() => {
  if (displayModel.value.length === 0) {
    return '当前生效的模型配置里还没有对话模型，去「设置 → 模型配置」加一个'
  }
  return following.value
    ? `当前跟随生效配置的默认模型（${displayModel.value}）。切换只影响这个会话`
    : `本会话固定使用 ${displayModel.value}。切换只影响这个会话，其它会话各自独立`
})
</script>

<template>
  <el-select
    v-model="selected"
    class="model-picker"
    size="small"
    :disabled="blocked"
    :teleported="true"
    :title="hint"
    :placeholder="defaultModel || '未配置模型'"
    aria-label="本会话使用的模型"
  >
    <!--
      回显用的是一个独立的「展示层」：select 的输入框显示 displayModel，
      用户看到的永远是模型名。做法是让 el-select 的 value 与 option 的 label
      解耦 —— 实际值可以是 FOLLOW 哨兵，但选中项的 label 我们手动覆盖成
      模型名 + 一个「默认」小标，避免下拉里出现「跟随生效配置」这种内部词汇。
    -->
    <el-option :value="FOLLOW" :label="displayModel || '跟随生效配置'">
      <span class="model-picker__option">{{ displayModel || '未配置模型' }}</span>
      <span v-if="following" class="model-picker__badge">默认</span>
      <span v-else class="model-picker__hint">跟随生效配置</span>
    </el-option>
    <el-option v-for="name in options" :key="name" :label="name" :value="name" />
  </el-select>
</template>

<style scoped>
/*
 * 宽度上限而不是固定宽度：`qwen3.8-flash` 与 `qwen3.8-text-embedding-flash`
 * 长度差一倍，固定宽度会把长的截断、短的留一片空白。
 *
 * <p>取 160：它与「附件」「知识库增强」并排出现在输入区工具栏，
 * 太宽会让工具栏在窄屏提前折行。实测工具栏在 1024px 下可用 436px，
 * 左侧标签组（63+8+99）= 170，加模型下拉 160 + 8 = 338 —— 还能容下
 * 检索条数的 78px 空档。再宽 12px 就会把下拉挤到第二行。
 */
.model-picker {
  width: 160px;
  max-width: 100%;
}

/*
 * 高度对齐工具栏（32px = `--os-button-height`）。
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
  min-height: var(--os-button-height);
}

/*
 * 下拉项：模型名 + 右侧一个小标记。
 * 用 inline-flex 让标记紧跟模型名，而不是被撑到最右（那会让人以为是两列）。
 */
.model-picker__option {
  margin-right: var(--os-space-2);
}

/*
 * 「默认」标：告诉用户这一项就是生效配置里的默认模型。
 *
 * <p>为什么需要它：既然回显永远显示模型名，用户就无法从界面上分辨
 * 「这个模型是我给本会话特意选的」还是「这是配置里的默认值」——
 * 而这两者在**改配置之后的行为不同**（前者不变、后者跟着变）。
 * 一个小标把这条信息补回来，且不占用标签空间。
 */
.model-picker__badge {
  padding: 0 5px;
  border-radius: 999px;
  background: var(--el-fill-color);
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
  line-height: 16px;
}

/* 「跟随生效配置」那一项右侧的说明：只在用户主动展开菜单时出现 */
.model-picker__hint {
  color: var(--el-text-color-placeholder);
  font-size: var(--os-font-xs);
}
</style>
