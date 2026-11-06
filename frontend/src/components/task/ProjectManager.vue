<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { saveProject } from '@/api/workspace'
import type { ProjectItem } from '@/api/workspace'
const props = defineProps<{ projects: ProjectItem[]; error: string; loading: boolean }>()
const emit = defineEmits<{ (e: 'refresh'): void }>()
const expanded = ref(false)
const editing = ref<string>()
const name = ref('')
const description = ref('')
const saving = ref(false)
const operationError = ref('')
function reset(): void {
  editing.value = undefined
  name.value = ''
  description.value = ''
  operationError.value = ''
}
function edit(project: ProjectItem): void {
  editing.value = project.id
  name.value = project.name
  description.value = project.description ?? ''
  operationError.value = ''
}
async function submit(): Promise<void> {
  if (saving.value || !name.value.trim()) return
  saving.value = true
  operationError.value = ''
  try {
    await saveProject({ name: name.value.trim(), description: description.value }, editing.value)
    reset()
    emit('refresh')
    ElMessage.success('项目已保存')
  } catch (cause) {
    operationError.value = cause instanceof Error ? cause.message : '项目保存失败'
  } finally {
    saving.value = false
  }
}
async function archive(project: ProjectItem): Promise<void> {
  if (saving.value) return
  saving.value = true
  operationError.value = ''
  try {
    await saveProject({ archived: !project.archived }, project.id)
    emit('refresh')
  } catch (cause) {
    operationError.value = cause instanceof Error ? cause.message : '更新项目失败'
  } finally {
    saving.value = false
  }
}
</script>
<template>
  <el-card shadow="never">
    <div class="project-manager__header">
      <div>
        <strong>项目</strong
        ><span class="project-manager__hint">
          {{ projects.filter((p) => !p.archived).length }} 个进行中 · 归档保留任务历史</span
        >
      </div>
      <el-button :aria-expanded="expanded" @click="expanded = !expanded">{{
        expanded ? '收起项目管理' : '管理项目'
      }}</el-button>
    </div>
    <el-alert v-if="error" type="error" :closable="false" :title="error" /><el-button
      v-if="error"
      @click="emit('refresh')"
      >重试加载项目</el-button
    >
    <div v-if="expanded" class="project-manager__body">
      <el-form label-position="top" :disabled="saving" @submit.prevent="submit">
        <el-form-item :label="editing ? '编辑项目名称' : '新项目名称'" required
          ><el-input v-model="name" maxlength="100" placeholder="例如：个人网站改版"
        /></el-form-item>
        <el-form-item label="项目描述"
          ><el-input v-model="description" type="textarea" :rows="2" maxlength="2000"
        /></el-form-item>
        <el-button native-type="submit" type="primary" :loading="saving" :disabled="!name.trim()">{{
          editing ? '保存项目' : '新建项目'
        }}</el-button
        ><el-button v-if="editing" @click="reset">取消编辑</el-button>
      </el-form>
      <p v-if="operationError" role="alert">{{ operationError }}</p>
      <el-table v-loading="loading" :data="props.projects" empty-text="还没有项目，可以先创建一个">
        <el-table-column prop="name" label="项目" min-width="150" />
        <el-table-column label="状态" width="90"
          ><template #default="{ row }">{{ row.archived ? '已归档' : '进行中' }}</template></el-table-column
        >
        <el-table-column label="操作" width="140"
          ><template #default="{ row }"
            ><el-button link :disabled="saving" @click="edit(row)">编辑</el-button
            ><el-button link :disabled="saving" @click="archive(row)">{{
              row.archived ? '恢复' : '归档'
            }}</el-button></template
          ></el-table-column
        >
      </el-table>
    </div>
  </el-card>
</template>
<style scoped>
.project-manager__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--os-space-3);
  flex-wrap: wrap;
}
.project-manager__hint {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}
.project-manager__body {
  display: grid;
  gap: var(--os-space-4);
  margin-top: var(--os-space-4);
}
</style>
