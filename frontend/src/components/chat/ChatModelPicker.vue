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
 * <h2>为什么从 32px 表单框改成 24px 文字按钮（2026-10-29）</h2>
 * 它原来是一个 `el-select`（内部 wrapper 32px 高、16px 字号），右边并排的是
 * 字数统计（15px 高、12px 字号）—— 同一行里一个**控件**加一个**读数**，
 * 两种高度两种字号，视觉上就是「一大两小、散乱」。用户的原话是
 * 「模型和 0/4000 还有上下文消耗样式还是太丑了」。
 *
 * <p>现在它和另外两处读数统一成**同一规格的文字读数**：24px 热区、12px 字号、
 * 无边框无底色、hover 才给一层极浅底纹。这一行因此是「一行读数」而不是
 * 「控件 + 读数混排」—— 后者无论怎么调间距都会显得脏，因为眼睛在两种
 * 视觉语言之间跳。
 *
 * <p>换成 `el-dropdown` 而不是继续用 `el-select`：`el-select` 的输入框外观
 * 由 `.el-select__wrapper` 提供（0-1-0 的 box-shadow 描边、min-height、
 * padding），要把它彻底改造成裸文字需要覆盖 5 个属性，而每覆盖一个都在
 * 与 EP 的加载顺序博弈。`el-dropdown` 的触发器完全由我们自己渲染，
 * 没有任何默认外观 —— 用对的组件比覆盖错的组件省事且不易回归。
 */
import { ArrowDown, Cpu } from '@element-plus/icons-vue'
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

/** 下拉里那份模型清单（含「已从配置里删掉但会话还记着」的那一项） */
const options = computed<string[]>(() => sessionModelOptions(props.models, props.modelValue))

/** 回显值：永远是具体模型名，不是「跟随配置」 */
const displayModel = computed(() => effectiveChatModel(props.models, props.modelValue, props.defaultModel))

/** 清单为空且没有任何可跟随的模型：整块禁用，不是隐藏 */
const blocked = computed(() => props.disabled || displayModel.value.length === 0)

/**
 * 是否处于「跟随」状态：决定「默认」徽章要不要跟着移动。
 *
 * <p>跟随时当前生效的就是配置默认模型，徽章天然落在它那一行；
 * 非跟随时徽章仍标在配置默认模型那一行 —— 它标的是「哪一项是配置默认」，
 * 与「当前选中」是两件事。两者可能落在不同行，这是对的：
 * 用户需要知道自己是否已经偏离了默认。
 */
const following = computed(() => (props.modelValue ?? '').trim().length === 0)

/**
 * 点某一项之后要发什么。
 *
 * <h3>为什么点「配置默认」那一项发 null</h3>
 * 这样「回到跟随」就有了一个自然入口 —— 不需要在下拉里专门放一项
 * 「跟随生效配置」（那正是上一版被用户否掉的写法：内部词汇不该占界面位置）。
 * 语义上「我就用默认那个」与「跟随默认」在选择的那一刻完全等价，
 * 差别只在「以后配置改了要不要跟着变」—— 而那时候用户已经不在这个菜单里了。
 *
 * <p>反过来说，点非默认模型就发模型名，把会话钉死在它上面。
 */
function handleCommand(name: string): void {
  const target = name === props.defaultModel ? null : name
  if (target === (props.modelValue ?? null)) {
    return
  }
  emit('update:modelValue', target)
}

/**
 * 空清单时给出说明，而不只是禁用。
 *
 * <h3>为什么不用 el-tooltip 包住整个下拉</h3>
 * 实测过：tooltip 挂在触发器上时，鼠标移入出提示 → 点击展开下拉，
 * 提示**不会消失**（鼠标仍在触发器上），而它正好浮在菜单第一项上方
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
  <el-dropdown
    class="model-picker-dropdown"
    trigger="click"
    placement="top-start"
    :disabled="blocked"
    max-height="320px"
    @command="handleCommand"
  >
    <!--
      触发器是原生 button 而不是 el-button：el-button 会带一层 `--small` 的
      32px min-height 与外边距，要把它们全压掉等同于与全站按钮规格打架。
      原生 button + 完全自定的样式，反而没有一处要覆盖。
    -->
    <button
      type="button"
      class="model-picker"
      :disabled="blocked"
      :title="hint"
      :aria-label="`本会话使用的模型：${displayModel || '未配置'}。点击切换`"
    >
      <el-icon class="model-picker__icon" :size="12"><Cpu /></el-icon>
      <span class="model-picker__name">{{ displayModel || '未配置模型' }}</span>
      <el-icon v-if="!blocked" class="model-picker__caret" :size="10"><ArrowDown /></el-icon>
    </button>

    <template #dropdown>
      <el-dropdown-menu class="model-picker__menu">
        <div class="model-picker__head">本会话使用的模型</div>
        <el-dropdown-item
          v-for="name in options"
          :key="name"
          :command="name"
          :class="{ 'model-picker__item--on': name === displayModel }"
        >
          <span class="model-picker__option">{{ name }}</span>
          <!--
            「默认」标：告诉用户这一项就是生效配置里的默认模型（点它 = 跟随）。
            为什么需要它：既然回显永远显示模型名，用户就无法从界面上分辨
            「这个模型是我给本会话特意选的」还是「这是配置里的默认值」——
            而这两者在**改配置之后的行为不同**（前者不变、后者跟着变）。
          -->
          <span v-if="name === defaultModel" class="model-picker__badge">默认</span>
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<style scoped>
/*
 * el-dropdown 的根元素是 `display: inline-flex`（EP 的 `.el-dropdown` 定义），
 * 它作为输入区第一行的一个 flex 项时**默认不收缩**（`flex-shrink` 虽为 1，
 * 但 `min-width: auto` 会把它顶在内容宽度上）。实测 960px 下这一行只有 372px，
 * 而「deepseek-v4.1-flash-1017」名字就占 188px + 读数组 187px = 383px，
 * 溢出 11.5px —— 表现为字数与上下文跑出了发送框的右边框。
 *
 * <p>给根元素 `min-width: 0` 让收缩真正生效，模型名再按 ellipsis 省略。
 * 宁可省模型名也不省读数：模型名在展开的下拉里能看全，字数与用量没有
 * 第二个地方可看。
 */
.model-picker-dropdown {
  min-width: 0;
  vertical-align: middle;
}

/*
 * 触发器：一行裸文字，24px 热区。
 *
 * <h3>为什么 24px 而不是 32px</h3>
 * 它现在与「字数」「上下文」并排，而那两处是 15px 高的纯文字。
 * 用 32px 会让这一行的高度由最高的那个决定（32），而两个读数被
 * `align-items: center` 吊在中间、上下各空 8px —— 视觉上就是
 * 「一个框 + 两个飘着的数字」。24px 是「一行小字」的自然热区高度
 * （12px 字号 × 1.5 行高 = 18，加 6px 上下），三者等高后这一行才是一条线。
 *
 * <p>padding 左右 6px：hover 底纹不能贴着文字，但也不能宽到让
 * 「模型名 → 右侧读数」之间出现一段不可读的空隙。
 */
.model-picker {
  display: inline-flex;
  align-items: center;
  /*
   * `flex: 0 1 auto` + `min-width: 0` —— **必须可收缩**。
   *
   * <p>实测 960px 下这一行只有 372px：`deepseek-v4.1-flash-1017` 这个名字
   * 就让按钮自己占 188px，加右侧读数组 187px、gap 8px = 383px，
   * **溢出 11.5px**。而它原来写的是 `flex: none`（不收缩），于是读数组被
   * 推出容器右边缘 —— 表现为「字数与上下文超出了发送框的边框」。
   *
   * <p>收缩后模型名按 `.model-picker__name` 的 ellipsis 省略成
   * 「deepseek-v4.1-fl…」，`title` 里仍是全名。宁可省模型名也不省读数：
   * 模型名在展开的下拉里能看全，字数与用量没有第二个地方可看。
   */
  flex: 0 1 auto;
  min-width: 0;
  gap: 4px;
  height: 24px;
  padding: 0 6px;
  border: none;
  border-radius: var(--os-radius-sm);
  background: transparent;
  color: var(--el-text-color-regular);
  font-size: var(--os-font-xs);
  font-family: inherit;
  line-height: 1;
  cursor: pointer;
  transition: background var(--os-duration-fast) var(--os-ease);
}

.model-picker:hover:not(:disabled) {
  background: var(--el-fill-color-light);
}

/* 禁用态不隐藏，只弱化：用户仍要看得到「现在是谁在答」 */
.model-picker:disabled {
  cursor: not-allowed;
  color: var(--el-text-color-placeholder);
}

/* 键盘可达：与 chip 一致的焦点环，不用浏览器默认的黑色粗框 */
.model-picker:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 1px;
}

.model-picker__icon {
  flex: none;
  color: var(--el-text-color-placeholder);
}

/*
 * 模型名可能很长（`qwen3.8-text-embedding-flash-2025-09-01`），
 * 必须能省略而不是把这一行撑出容器。
 */
.model-picker__name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 箭头只提示「可展开」，不需要 EP 默认那么大的视觉重量 */
.model-picker__caret {
  flex: none;
  color: var(--el-text-color-placeholder);
}

/* 菜单里的一行：模型名 + 右侧「默认」标 */
.model-picker__option {
  margin-right: var(--os-space-2);
}

/*
 * 「默认」标：告诉用户这一项是配置里的默认模型。
 *
 * <p>比正文小一号 + 浅底：它是**注解**而不是选项内容，
 * 与模型名同权重会让人以为「默认」是模型名的一部分。
 */
.model-picker__badge {
  padding: 0 5px;
  border-radius: 999px;
  background: var(--el-fill-color);
  color: var(--el-text-color-secondary);
  font-size: 11px;
  line-height: 16px;
}

/* 当前生效项：用主色 + 中等字重，不用打勾图标（菜单已经很窄） */
.model-picker__item--on {
  color: var(--el-color-primary);
  font-weight: 500;
}

/* 菜单小标题：说明这些选项的作用域，避免用户以为改的是全局配置 */
.model-picker__head {
  padding: 6px 12px 4px;
  color: var(--el-text-color-placeholder);
  font-size: var(--os-font-xs);
}
</style>
