<script setup lang="ts">
/**
 * 状态标签（阶段 18，contracts C-03）。
 *
 * 把「值 → {文案, 颜色}」的映射收在一处：改造前 ActivityView 的 outcomeMeta、
 * TasksView 的 priorityTagType、HomeView 的 componentTagType 各写各的，
 * 且都只覆盖了已知枚举。
 *
 * 契约：未映射的值回落 `{label: 原值, type: 'info'}`，**不抛错**——
 * 后端加新枚举时前端降级展示而不是白屏。
 */
import { computed } from 'vue'

type TagType = 'success' | 'info' | 'warning' | 'danger'

const props = defineProps<{
  /** 状态原始值（后端枚举） */
  status: string
  /** 映射表：值 → {label, type} */
  map: Record<string, { label: string; type: TagType }>
  size?: 'small' | 'default' | 'large'
}>()

const meta = computed<{ label: string; type: TagType }>(
  () => props.map[props.status] ?? { label: props.status, type: 'info' },
)
</script>

<template>
  <el-tag :type="meta.type" :size="size ?? 'small'" effect="plain">
    {{ meta.label }}
  </el-tag>
</template>
