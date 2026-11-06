<script setup lang="ts">
import PageHeader from '@/components/PageHeader.vue'
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  createSpace,
  listSpaces,
  listSources,
  listConflicts,
  updateSource,
  retryReview,
  resolveConflict,
  type KnowledgeSpace,
  type DocumentSource,
  type SourceConflict,
} from '@/api/knowledge-governance'
const spaces = ref<KnowledgeSpace[]>([])
const sources = ref<DocumentSource[]>([])
const conflicts = ref<SourceConflict[]>([])
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const spaceName = ref('')
const editing = ref<DocumentSource | null>(null)
const resolving = ref<SourceConflict | null>(null)
const decision = ref('KEEP_BOTH')
const winner = ref<string | null>(null)
const reason = ref('')
const reviewNames: Record<string, string> = {
  PENDING: '等待审查',
  PARTIAL: '已审查部分片段',
  CHECKED_CANDIDATES: '相关候选审查完成',
  UNAVAILABLE: '审查暂不可用，将重试',
}
const statusNames: Record<string, string> = { OPEN: '待核对', RESOLVED: '已人工处理', STALE: '资料已变更，原决定失效' }
async function load() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    ;[spaces.value, sources.value, conflicts.value] = await Promise.all([listSpaces(), listSources(), listConflicts()])
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '加载失败，请重试'
  } finally {
    loading.value = false
  }
}
async function action(work: () => Promise<unknown>) {
  if (saving.value) return
  saving.value = true
  error.value = ''
  try {
    await work()
    await load()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '保存失败，请重试'
  } finally {
    saving.value = false
  }
}
async function addSpace() {
  if (!spaceName.value.trim()) return
  await action(async () => {
    await createSpace(spaceName.value.trim())
    spaceName.value = ''
  })
}
async function saveSource() {
  if (!editing.value) return
  const entry = editing.value
  await action(async () => {
    await updateSource(entry.documentId, entry.source)
    editing.value = null
    ElMessage.success('已保存，相关资料重新排队审查')
  })
}
function beginEdit(row: DocumentSource) {
  editing.value = { ...row, source: { ...row.source } }
}
function beginResolve(conflict: SourceConflict) {
  resolving.value = conflict
  decision.value = 'KEEP_BOTH'
  winner.value = null
  reason.value = ''
}
async function saveResolution() {
  if (!resolving.value || !reason.value.trim()) {
    ElMessage.warning('请填写核对依据')
    return
  }
  if (decision.value === 'PREFER_SOURCE' && !winner.value) {
    ElMessage.warning('请选择经过核实的来源')
    return
  }
  const id = resolving.value.id
  await action(async () => {
    await resolveConflict(id, decision.value, winner.value, reason.value.trim())
    resolving.value = null
  })
}
function title(id: string) {
  return sources.value.find((s) => s.documentId === id)?.title ?? id
}
onMounted(load)
</script>
<template>
  <section class="governance" aria-label="资料治理">
    <PageHeader title="资料治理" description="补全资料的来源和适用范围，核对不同版本之间的分歧。"
      ><el-button :loading="loading" @click="load">刷新状态</el-button></PageHeader
    >
    <el-alert
      type="info"
      :closable="false"
      title="审查结果不等于事实认证"
      description="保留不同来源，补全版本、对象和有效期。相关候选审查完成不代表穷尽所有冲突；模型只提出线索，人工决定需填写依据。最多展示最近 500 份资料和 200 条分歧。"
    />
    <el-alert v-if="error" type="error" :closable="false" :title="error" show-icon />
    <form class="governance__actions" @submit.prevent="addSpace">
      <el-input v-model="spaceName" aria-label="新知识库名称" placeholder="新知识库名称" maxlength="120" />
      <el-button native-type="submit" :loading="saving" :disabled="!spaceName.trim()">新建知识库</el-button>
    </form>
    <el-table :data="sources" empty-text="暂无资料，请先上传文档" aria-label="资料来源列表">
      <el-table-column prop="title" label="资料" min-width="150" />
      <el-table-column prop="source.spaceName" label="知识库" min-width="110" />
      <el-table-column prop="source.version" label="版本" min-width="90" />
      <el-table-column prop="source.scope" label="适用范围" min-width="120" />
      <el-table-column label="状态" min-width="180"
        ><template #default="{ row }"
          >{{ row.source.lifecycle === 'SUPERSEDED' ? '已停用 · ' : ''
          }}{{ reviewNames[row.source.reviewState] ?? row.source.reviewState }}</template
        ></el-table-column
      >
      <el-table-column label="操作" width="150"
        ><template #default="{ row }">
          <el-button link type="primary" @click="beginEdit(row)">编辑</el-button>
          <el-button link :disabled="saving" @click="action(() => retryReview(row.documentId))">重新审查</el-button>
        </template></el-table-column
      >
    </el-table>
    <h3>来源分歧</h3>
    <el-empty
      v-if="!loading && !error && conflicts.length === 0"
      description="暂无已记录分歧；这不代表不存在冲突"
      :image-size="50"
    />
    <article v-for="conflict in conflicts" :key="conflict.id" class="governance__conflict">
      <div>
        <strong>{{ statusNames[conflict.status] ?? conflict.status }}</strong> · {{ conflict.reason }}
      </div>
      <blockquote>{{ title(conflict.leftId) }}：{{ conflict.leftQuote }}</blockquote>
      <blockquote>{{ title(conflict.rightId) }}：{{ conflict.rightQuote }}</blockquote>
      <p v-if="conflict.resolutionReason">人工依据：{{ conflict.resolutionReason }}</p>
      <el-button v-if="conflict.status === 'OPEN'" @click="beginResolve(conflict)">核对并处理</el-button>
    </article>
    <el-dialog :model-value="!!editing" title="资料来源与适用条件" width="min(620px, 94vw)" @close="editing = null">
      <el-form v-if="editing" label-position="top" @submit.prevent="saveSource">
        <el-form-item label="知识库"
          ><select v-model="editing.source.spaceId" aria-label="知识库">
            <option v-for="space in spaces" :key="space.id" :value="space.id">{{ space.name }}</option>
          </select></el-form-item
        >
        <el-form-item label="制度 / 资料版本"
          ><el-input v-model="editing.source.version" maxlength="120"
        /></el-form-item>
        <el-form-item label="适用对象 / 范围"
          ><el-input v-model="editing.source.scope" maxlength="500" placeholder="例如：上海研发部、正式员工、国内差旅"
        /></el-form-item>
        <el-form-item label="生效日期"
          ><el-date-picker v-model="editing.source.effectiveFrom" type="date" value-format="YYYY-MM-DD" clearable
        /></el-form-item>
        <el-form-item label="截止日期"
          ><el-date-picker v-model="editing.source.effectiveUntil" type="date" value-format="YYYY-MM-DD" clearable
        /></el-form-item>
        <el-form-item label="发布方 / 核实依据"
          ><el-input v-model="editing.source.authority" maxlength="300"
        /></el-form-item>
        <el-form-item label="资料状态"
          ><select v-model="editing.source.lifecycle" aria-label="资料状态">
            <option value="ACTIVE">使用中</option>
            <option value="SUPERSEDED">已被替代（不参与新检索）</option>
          </select></el-form-item
        >
        <el-button native-type="submit" type="primary" :loading="saving">保存并重新审查</el-button>
      </el-form>
    </el-dialog>
    <el-dialog :model-value="!!resolving" title="人工核对分歧" width="min(620px, 94vw)" @close="resolving = null">
      <el-form v-if="resolving" label-position="top" @submit.prevent="saveResolution">
        <el-form-item label="处理方式"
          ><select v-model="decision" aria-label="处理方式">
            <option value="KEEP_BOTH">保留双方，回答时继续提示分歧</option>
            <option value="NOT_CONFLICT">适用条件不同，并非事实冲突</option>
            <option value="PREFER_SOURCE">已核实，选定适用来源</option>
          </select></el-form-item
        >
        <el-form-item v-if="decision === 'PREFER_SOURCE'" label="适用来源"
          ><select v-model="winner" aria-label="适用来源">
            <option :value="resolving.leftId">{{ title(resolving.leftId) }}</option>
            <option :value="resolving.rightId">{{ title(resolving.rightId) }}</option>
          </select></el-form-item
        >
        <el-form-item label="核实依据（必填）"
          ><el-input v-model="reason" type="textarea" maxlength="1000" :rows="3"
        /></el-form-item>
        <p>决定仅适用于这组原文及当前版本。资料更新后会失效，需重新核对。</p>
        <el-button type="primary" native-type="submit" :loading="saving">保存处理结果</el-button>
      </el-form>
    </el-dialog>
  </section>
</template>
<style scoped>
.governance {
  font-size: var(--os-font-md);
  display: flex;
  flex-direction: column;
  gap: var(--os-space-3);
}
.governance__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--os-space-2);
}
.governance__actions .el-input {
  flex: 1;
  min-width: 160px;
  max-width: 320px;
}
.governance__conflict {
  border: 1px solid var(--el-border-color);
  border-radius: var(--os-radius-md);
  padding: var(--os-space-3);
  line-height: 1.6;
  overflow-wrap: anywhere;
}
blockquote {
  margin: var(--os-space-2) 0;
  border-left: 3px solid var(--el-color-warning);
  padding-left: var(--os-space-2);
  white-space: pre-wrap;
}
select {
  width: 100%;
  min-height: 34px;
  color: var(--el-text-color-primary);
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color);
  border-radius: var(--os-radius-sm);
  padding-inline: var(--os-space-2);
}
.governance__actions {
  padding: var(--os-space-4);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--os-radius-md);
  background: var(--el-bg-color);
  align-items: center;
}
.governance__actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
.governance > h3 {
  margin: var(--os-space-3) 0 0;
  font-size: var(--os-font-lg);
}
</style>
