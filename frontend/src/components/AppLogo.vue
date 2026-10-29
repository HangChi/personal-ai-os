<script lang="ts">
/**
 * 渐变 id 必须**每个实例唯一**。
 *
 * <p>原先用 `app-logo-grad-${size}` 拼——看着够用，实际是个定时炸弹：
 * 侧栏与登录页顶栏**都是 size=30**，靠「两者是互斥路由、不会同时挂载」才不冲突。
 * 这个假设一旦被打破（比如以后在设置页也放一个品牌块，或做 SSR 把它同时渲染进
 * 两处），两个 `<linearGradient id="app-logo-grad-30">` 会撞在一起：
 * 浏览器只认文档中第一个，第二处 `url(#...)` 静默指到别人的渐变上 ——
 * 颜色看起来「差不多」，因为它恰好是同色系，但暗色或自定义主题下就会显形。
 *
 * <p>计数器的位置有讲究：它**必须在这个普通 `<script>` 块里**，不能放 `<script setup>`。
 * `<script setup>` 的顶层代码就是 setup 函数体，每次实例化都会重跑 ——
 * 放进去等于每个实例都从 0 开始，跟没做一样（实测两个实例都拿到 `grad-1`）。
 * 只有普通 `<script>` 块是真正的模块级，全模块只求值一次。
 *
 * <p><b>为什么不用 Vue 的 `useId`</b>：它按渲染上下文自增，同页两个实例实测都拿到
 * `app-logo-grad-v-0` —— 正是要防的那种撞车（见 app-logo.test.ts 对应用例）。
 */
let instanceSeq = 0
</script>

<script setup lang="ts">
/**
 * 品牌标记（2026-10-29）。
 *
 * <h2>造型</h2>
 * 一个**带缺口的圆环**，缺口里嵌一颗实心点：
 * <ul>
 *   <li>环 = OS 外壳（一个闭合的系统）</li>
 *   <li>缺口 = 唯一的入口（不是随便开个口，只有一处）</li>
 *   <li>点 = 使用者本人，落在缺口里而不是环心 —— 强调「你在这个系统的边界上，
 *       既是使用者也是打开的开关」</li>
 * </ul>
 *
 * <h2>为什么不用文字块或通用图标（改造前）</h2>
 * 改造前侧栏是 `<span>AI</span>` 一个纯色方块加两个字，favicon 是同样思路的
 * 「AI」文字图 —— 两个问题：
 * <ol>
 *   <li>没有形状识别度：缩到 16px 只剩一坨蓝，与任何一个 AI 产品都撞；</li>
 *   <li>favicon 用的是 EP 蓝 #409eff，而主题主色是靛蓝 #4f46e5 ——
 *       页签图标和界面根本不是一套色，属于肉眼能察觉但没人会主动去查的漂移。</li>
 * </ol>
 *
 * <h2>为什么环用 circle 而不是圆角方环</h2>
 * 圆角方环更像「窗口」，语义更好，但它的缺口要落在圆角上，路径需要手工
 * 拼四段直线 + 四段弧再接一个断口 —— 而我改完**无法用眼睛验收**（看不到图），
 * 只能靠像素统计。圆弧的断口角度可以直接算出来并验证，方环的拼接口径太多，
 * 出错时表现为「某个角有点怪」，靠像素也难判定。**可验证性优先。**
 *
 * <h2>两个变体</h2>
 * <ul>
 *   <li>`tile`：圆角方块底 + 白色标记（侧栏、登录页、favicon 用）。</li>
 *   <li>`mark`：只有标记本身，用 currentColor（消息头像用 —— 头像底已经是浅主色，
 *       再叠一个实心方块会与用户头像（主色实底）抢重心）。</li>
 * </ul>
 */
withDefaults(
  defineProps<{
    /** 渲染尺寸（px）。默认 30：与侧栏导航图标同一量级 */
    size?: number
    variant?: 'tile' | 'mark'
  }>(),
  { size: 30, variant: 'tile' },
)

/** 每个实例一个唯一 id —— 计数器在顶部普通 `<script>` 块里，见那里的注释 */
const gradientId = `app-logo-grad-${++instanceSeq}`

/**
 * 几何常量（viewBox 0 0 32 32）。
 *
 * 这些数不是随手取的，改之前请先看下面的约束：
 * - `RING_R + STROKE / 2` 必须留出边距：7.6 + 1.45 = 9.05，
 *   距 16 的中心还剩 6.95，即标记最外沿落在 x≈6.95 —— 在 32 的方块里方正居中。
 * - `GAP_HALF` 决定缺口宽度，`DOT_R` 决定塞在缺口里的点有多大，
 *   **两者必须一起定**（见下方「缺口必须比点宽出可见缝隙」）。
 *
 * <h3>为什么端点用 butt 而不是 round</h3>
 * `round` 端点更精致，但它在**小尺寸下必然失败**，原因是它能吃掉缺口：
 * 缺口在 r=7.6 上的中心线弧长 = 2×GAP_HALF×π/180×7.6；
 * 而 `stroke-linecap="round"` 让弧两端各外扩 `STROKE/2 = 1.45` 单位，
 * 两端共吃掉 **2.90 单位**（16px 渲染时合 1.45px，等于整个缺口的一半）。
 *
 * 实测扫描「端点样式 × 缺口半角 × 点半径」共 5 组 × 6 个尺寸，按
 * 「环与点是否为两个独立连通域」判定：
 * <pre>
 *   A 38° round  14px✗ 16px✗ 20px✗ 24px✗ 30px✗ 32px✓   ← 只有 32 能分开
 *   B 45° round  14px✗ 16px✗ 其余✓
 *   C 48° round  14px✓ 16px✗ 其余✓                       ← 临界，靠抗锯齿偶然
 *   D 42° butt   全部✓                                    ← 采用
 *   E 40° butt   14px✓ 16px✗ 其余✓
 * </pre>
 * `butt` 把「缺口」和「端点位置」解耦：端点精确落在 GAP_CENTER ± GAP_HALF，
 * 没有 cap 外扩这个隐藏变量 —— 这本身也是可验证性上的优点。
 *
 * <h3>16px 的物理下限（别再试图调参绕过）</h3>
 * 16px 时环线宽 = 2.9/32×16 = **1.45px**。要让缺口可见，缝宽得 ≥1px，
 * 也就是「1.45px 的墨 + 1px 的白」这样一个 2.5px 周期 —— 抗锯齿后必然糊成灰。
 * 所以 16px 下这个造型本就是「环优先、缺口勉强」。
 * **这不是参数没调好，是像素预算不够**；要真正解决只能做 hinting 版
 * （加粗线 + 扩缺口 + 缩点），v1 不做。行为上：≤16px 时浏览器页签会自动
 * 优先选 32px 图标，页内最小使用尺寸是侧栏的 30px。
 */
const RING_R = 7.6
const STROKE = 2.9
/**
 * 缺口半角（度），缺口以 -30° 方向（右上）为中心。
 *
 * 42° 的依据不是「看着像 C 还是像环」，而是上面扫描出的**全尺寸通过**那一档：
 * 84° 的缺口配 276° 的弧，弧仍是压倒性多数，不会读成 C；
 * 缺口里又有实体点填位，读者看到的是「环 · 口 · 点」三件。
 */
const GAP_HALF = 42

/**
 * 极坐标 → viewBox 坐标（16,16 为圆心）。
 *
 * 角度按**屏幕坐标**理解（y 向下）：0° 指向正右，-90° 指向正上。
 */
function polar(radius: number, deg: number): [number, number] {
  const rad = (deg * Math.PI) / 180
  return [16 + radius * Math.cos(rad), 16 + radius * Math.sin(rad)]
}

/** 缺口中心方向：-30°（右上） */
const GAP_CENTER_DEG = -30
/**
 * 弧的起终点**不能按角度从小到大写**。
 *
 * 这里踩过一个坑，值得留档：原先把起点设为 -60°、终点设为 0°，
 * 配 `large-arc=1 sweep=1`（顺时针大弧）—— 结果环根本没画出来，
 * 只在右上角出现一小团白色。原因是 sweep=1 表示「角度增大的方向」，
 * 从 -60° 增大到 0° 只扫过 **60°**，这与 large-arc=1（要求 >180°）自相矛盾，
 * 浏览器于是退化成一团。正确做法是让「角度增大的路径」恰好是那 276°：
 * 起点取 GAP_CENTER+GAP_HALF（12°）、终点取 GAP_CENTER-GAP_HALF（-72°），
 * 顺时针扫过 276°，与 large-arc=1 一致。
 *
 * 这类错误的可怕之处：SVG 不报错、DOM 结构看起来完全正常，
 * 只有真去数像素才发现「环没画出来」。所以这个文件里的几何常量都有对应的
 * 像素验证记录，改几何前请先跑一遍 scripts 里的验证。
 */
const ARC_START_DEG = GAP_CENTER_DEG + GAP_HALF // 12°，缺口上沿（顺时针起点）
const ARC_END_DEG = GAP_CENTER_DEG - GAP_HALF // -72°，缺口下沿（顺时针终点）

const [sx, sy] = polar(RING_R, ARC_START_DEG)
const [ex, ey] = polar(RING_R, ARC_END_DEG)
/** 顺时针扫过 360 - 2*GAP_HALF = 276°，大于半圈 → large-arc=1，屏幕坐标顺时针 → sweep=1 */
const ARC_PATH = `M ${sx.toFixed(3)} ${sy.toFixed(3)} A ${RING_R} ${RING_R} 0 1 1 ${ex.toFixed(3)} ${ey.toFixed(3)}`

/**
 * 点半径 1.95：在 42° 缺口里点对圆心张角 = 2×asin(1.95/7.6) ≈ 29.7°，
 * 缺口半宽 42° 减去即每侧留 12.2°（≈1.61 单位）——
 * 这才是「16px 下仍能分成两个连通域」的那个余量。
 * 不要再放大到 2.5：那是初版的值，配 30° 缺口时余量只剩 0.029 单位，
 * 点把缺口完全堵死，环读成实心圆（见文件头的参数扫描记录）。
 */
const DOT_R = 1.95
const [dx, dy] = polar(RING_R, GAP_CENTER_DEG)
</script>

<template>
  <svg
    v-if="variant === 'tile'"
    class="app-logo app-logo--tile"
    :width="size"
    :height="size"
    viewBox="0 0 32 32"
    role="img"
    aria-label="Personal AI OS"
  >
    <defs>
      <!--
        渐变两端**写死亮色主色的值**，不引用 --el-color-primary 阶梯。
        理由见下方 <style> 里 .app-logo--tile 的注释（暗色下白标记对比度只剩 2.10）。
      -->
      <linearGradient :id="gradientId" x1="0" y1="0" x2="1" y2="1">
        <stop offset="0%" stop-color="#4f46e5" />
        <stop offset="100%" stop-color="#3f38b7" />
      </linearGradient>
    </defs>
    <rect width="32" height="32" rx="7.5" :fill="`url(#${gradientId})`" />
    <path :d="ARC_PATH" fill="none" stroke="#fff" :stroke-width="STROKE" stroke-linecap="butt" />
    <circle :cx="dx" :cy="dy" :r="DOT_R" fill="#fff" />
  </svg>

  <svg
    v-else
    class="app-logo app-logo--mark"
    :width="size"
    :height="size"
    viewBox="0 0 32 32"
    role="img"
    aria-label="Personal AI OS"
  >
    <path :d="ARC_PATH" fill="none" stroke="currentColor" :stroke-width="STROKE" stroke-linecap="butt" />
    <circle :cx="dx" :cy="dy" :r="DOT_R" fill="currentColor" />
  </svg>
</template>

<style scoped>
.app-logo {
  display: block;
  flex: none;
}

/*
 * tile 变体的立体感：极轻的内高光 + 一层低透明度投影。
 * 与改造前侧栏 logo 的处理一致（纯色方块在浅色侧栏上显扁平），
 * 但把投影从 `rgb(79 70 229 / 32%)` 收到 24% —— 那个值是主色实底时代的，
 * 现在主色更常用在描边与小面积上，32% 会让 30px 的方块看起来有点脏。
 *
 * <h3>底色为什么不跟主题（写死 #4f46e5 → #3f38b7）</h3>
 * 初版把渐变写成 `var(--el-color-primary)` → `var(--el-color-primary-dark-2)`，
 * 理由是「两端都在主色阶梯上，不引入第三种颜色」。在亮色下没问题，
 * 但**暗色主题里这个阶梯的方向是反的**：`--el-color-primary` 变亮到 #818cf8、
 * `--el-color-primary-dark-2` 更亮到 #a5aef9（它表示「比主色再亮／再深一档用来压边」，
 * 不是「更深」）。于是暗色下白标记到底色的对比度从 6.29 掉到 **2.10**，
 * 低于 WCAG AA 对图形要求的 3.0 —— 定格到 16px 就是一个「发白发虚的小圆点」。
 * 而 `box-shadow` 仍固定用主色，投影和底色也会脱节。
 *
 * 结论是**品牌标记的底不该随主题亮度走**：logo 是身份识别，不是 UI 表面。
 * 暗色下它需要的不是「跟着变亮」，而是靠周围底色把这块饱和色衬托出来
 * （近黑侧栏上 #4f46e5 反而更跳）。favicon 同样写死这两个值，两处保持一致。
 *
 * <h3>圆角为什么用百分比，而不是 --os-radius-md</h3>
 * 这个圆角有**两个消费者**：SVG 内部 `<rect rx="7.5">`（负责底色形状）和
 * 元素的 `border-radius`（负责 `box-shadow` 的形状）。两者必须严格相等，
 * 否则差的那 1px 会表现为「投影的角比底色的角更圆或更方」。
 * 用 `--os-radius-md`（8px 定值）时，30px 下与 rect 换算出的 7.03px 差 0.97px ——
 * 不显著，但这是「同一件事被定义两遍」的典型：改一处、另一处静默失配。
 * 写成 `7.5 / 32 * 100%` 后，两者都源于 viewBox 里的 7.5 这一个数，
 * 且是**尺寸无关**的：16px 下 3.75px、30px 下 7.03px、32px 下 7.5px，全自动一致。
 */
.app-logo--tile {
  border-radius: calc(7.5 / 32 * 100%);
  box-shadow:
    0 1px 3px 0 rgb(79 70 229 / 24%),
    inset 0 1px 0 0 rgb(255 255 255 / 18%);
}

/* mark 变体跟随文字色，方便放进任何底色的容器里 */
.app-logo--mark {
  color: inherit;
}
</style>
