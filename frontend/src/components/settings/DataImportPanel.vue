<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { importData } from '@/api/export'
import { confirmAction } from '@/lib/confirm-box'
/** 导入同理：一次性动作，且只在空库上有意义 */
const importing = ref(false)
const importInput = ref<HTMLInputElement | null>(null)

/**
 * 导入：选文件 → 二次确认 → 提交。
 *
 * <p>确认弹窗里必须写明「只支持空库」：这是用户最容易误解的一步 ——
 * 他们看到「导入」会以为可以合并，而服务端会直接拒绝。与其等提交后报错，
 * 不如在点下去之前就说清楚。
 */
async function handleImportFile(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  // 立刻清空，否则连续两次选同一个文件不会触发 change
  input.value = ''
  if (!file) {
    return
  }
  // 导入不是删除，确认键不该用危险色 —— 满屏红色确认键会让真正的删除失去分量，
  // 所以走 confirmAction（同骨架、主色键）而不是 confirmDanger。
  const ok = await confirmAction({
    title: '确认导入',
    message: `将把「${file.name}」里的数据写入当前数据库。\n\n导入只支持空库：库里已有数据时会被拒绝，不会覆盖或合并。`,
    confirmText: '开始导入',
  })
  if (!ok) {
    return
  }

  importing.value = true
  try {
    const result = await importData(await file.text())
    ElMessage.success(
      `导入完成：${result.sessions} 会话 / ${result.messages} 消息 / ` +
        `${result.tasks} 任务 / ${result.calendarEvents} 日程 / ` +
        `${result.memories} 记忆 / ${result.documents} 文档`,
    )
    for (const note of result.notes ?? []) {
      ElMessage.info(note)
    }
    // 导入后各页的缓存全是旧的，直接重载最省事也最不易错
    window.location.reload()
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '导入失败，请稍后重试')
  } finally {
    importing.value = false
  }
}
</script>
<template>
  <details class="os-disclosure data-import">
    <summary>向空库导入业务数据</summary>
    <p>只支持空库，不会覆盖或合并。备份不含原始文档附件，完整迁移需使用数据库与附件备份包。</p>
    <input
      ref="importInput"
      class="sr-only"
      type="file"
      accept=".json,application/json"
      aria-label="选择业务数据导入"
      :disabled="importing"
      @change="handleImportFile"
    />
    <el-button :loading="importing" @click="importInput?.click()">选择 JSON 并导入</el-button>
  </details>
</template>
