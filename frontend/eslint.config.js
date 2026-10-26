import prettier from 'eslint-config-prettier'
import pluginVue from 'eslint-plugin-vue'
import globals from 'globals'
import tseslint from 'typescript-eslint'

/**
 * ESLint 扁平配置（2026-10-26 补护栏）。
 *
 * 原则：先让「会出 bug 的规则」 enforceable，再谈风格。
 * - 风格交给 Prettier（`npm run format`），因此最后用 eslint-config-prettier
 *   关掉所有与格式化冲突的规则 —— 两套工具各管一件事，不互相打脸。
 * - 规则集取 recommended 而非 all：本仓库既有代码量 1 万行以上，
 *   一次引入全部规则会产生大量与正确性无关的警告，最终会被忽略。
 *
 * 与既有代码风格的兼容点（改规则前请先看这里）：
 * - 无分号、单引号、2 空格缩进 → 由 .prettierrc.json 表达，不写进 ESLint。
 */

export default tseslint.config(
  {
    ignores: ['dist/**', 'dist-*', 'coverage/**', 'node_modules/**', '*.tsbuildinfo'],
  },

  // TS 源码与 .mjs 测试脚本共用 recommended 基线
  ...tseslint.configs.recommended,

  // Vue SFC：先套官方 recommended，再把 <script lang="ts"> 的解析器接到 tseslint
  ...pluginVue.configs['flat/recommended'],
  {
    files: ['**/*.vue'],
    languageOptions: {
      parserOptions: {
        parser: tseslint.parser,
      },
    },
  },

  // 浏览器端源码
  {
    files: ['src/**/*.{ts,vue}'],
    languageOptions: {
      globals: globals.browser,
    },
  },

  // 构建配置与测试脚本跑在 Node 里
  {
    files: ['*.config.{js,ts}', 'test/**/*.mjs', 'scripts/**/*.js'],
    languageOptions: {
      globals: globals.node,
    },
  },

  {
    rules: {
      // 显式声明「这份代码不需要这些约束」，避免后人误以为是漏配：
      // 项目大量使用 v-for + :key 的自定义组件名，视图文件不算「多词组件」问题
      'vue/multi-word-component-names': 'off',
      // 允许以 _ 前缀标注「刻意未使用」的参数与解构占位
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
    },
  },

  prettier,
)
