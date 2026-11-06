<script setup lang="ts">
import {
  ArrowRight,
  Calendar,
  Collection,
  Connection,
  Document,
  DocumentCopy,
  Folder,
  Link,
  List,
  Memo,
  Refresh,
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { storeToRefs } from 'pinia'
import { computed, onMounted, ref, watch } from 'vue'
import { useMcpStore } from '@/stores/mcp'

const props = withDefaults(defineProps<{ active?: boolean }>(), { active: true })

const store = useMcpStore()
const { mcp, loading, error, personalMcp, personalLoading, personalError } = storeToRefs(store)
const reconnecting = ref<string | null>(null)
const toolDrawerOpen = ref(false)
const selectedServerName = ref<string | null>(null)
watch(
  () => props.active,
  (active) => {
    if (!active) toolDrawerOpen.value = false
  },
)
const selectedServer = computed(() => mcp.value?.servers.find((server) => server.name === selectedServerName.value))
const busy = computed(() => loading.value || personalLoading.value || reconnecting.value !== null)
const connectedCount = computed(() => mcp.value?.servers.filter((server) => server.available).length ?? 0)

const serverPresentation: Record<string, { label: string; icon: typeof Connection; tone: string }> = {
  filesystem: { label: '本地文件', icon: Folder, tone: 'primary' },
  fetch: { label: '网页抓取', icon: Link, tone: 'success' },
}
const toolPresentation: Record<string, { label: string; icon: typeof List }> = {
  personal_list_tasks: { label: '任务查询', icon: List },
  personal_search_knowledge: { label: '知识检索', icon: Collection },
  personal_list_calendar: { label: '日程查询', icon: Calendar },
  personal_list_memories: { label: '长期记忆', icon: Memo },
}

const autoToolLabels: Record<string, string> = {
  directory_tree: '查看目录结构',
  get_file_info: '查看文件信息',
  list_allowed_directories: '查看可访问目录',
  list_directory: '浏览目录',
  list_directory_with_sizes: '查看目录与文件大小',
  read_file: '读取文件',
  read_media_file: '读取媒体文件',
  read_multiple_files: '批量读取文件',
  read_text_file: '读取文本文件',
  search_files: '搜索文件',
  fetch: '获取网页内容',
}

function showAutoTools(name: string): void {
  selectedServerName.value = name
  toolDrawerOpen.value = true
}

function serverInfo(name: string) {
  return serverPresentation[name] ?? { label: name, icon: Connection, tone: 'primary' }
}

function toolInfo(name: string) {
  return toolPresentation[name] ?? { label: name, icon: Connection }
}

onMounted(() => void store.refresh())

async function reconnect(name: string): Promise<void> {
  if (busy.value) return
  reconnecting.value = name
  try {
    await store.reconnectServer(name)
    ElMessage.success(`${name} 已连接`)
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '重连失败')
  } finally {
    reconnecting.value = null
  }
}

async function copyEndpoint(): Promise<void> {
  if (!personalMcp.value) return
  try {
    await navigator.clipboard.writeText(personalMcp.value.endpoint)
    ElMessage.success('已复制端点路径')
  } catch {
    ElMessage.error('复制失败，请手动复制')
  }
}
</script>

<template>
  <div class="mcp-settings">
    <header class="mcp-settings__toolbar">
      <div>
        <h2>MCP 连接与工具</h2>
        <p class="mcp-settings__intro">检查外部服务连接，查看可用工具和自动执行范围。</p>
      </div>
      <el-button :icon="Refresh" :loading="loading || personalLoading" :disabled="busy" @click="store.refresh()">
        刷新状态
      </el-button>
    </header>

    <section class="mcp-settings__section" aria-labelledby="mcp-connections-title">
      <header class="mcp-settings__heading">
        <h3 id="mcp-connections-title">外部连接</h3>
        <span v-if="mcp?.enabled && mcp.servers.length" class="mcp-settings__count">
          {{ connectedCount }} / {{ mcp.servers.length }} 已连接
        </span>
      </header>
      <el-alert v-if="error" type="error" :closable="false" show-icon title="加载连接失败" :description="error" />
      <el-skeleton v-else-if="loading && !mcp" :rows="3" animated />
      <template v-else-if="mcp">
        <div v-if="!mcp.enabled || !mcp.servers.length" class="mcp-settings__empty">
          <el-icon aria-hidden="true"><Connection /></el-icon>
          <span>{{ mcp.enabled ? '暂无外部连接' : '外部 MCP 未启用' }}</span>
        </div>
        <ul v-else class="mcp-settings__connections" aria-label="MCP 接入状态">
          <li v-for="server in mcp.servers" :key="server.name" class="mcp-settings__connection">
            <div class="mcp-settings__card-body">
              <div class="mcp-settings__card-top">
                <span
                  class="mcp-settings__icon"
                  :class="`mcp-settings__icon--${serverInfo(server.name).tone}`"
                  aria-hidden="true"
                  ><el-icon><component :is="serverInfo(server.name).icon" /></el-icon
                ></span>
                <span
                  class="mcp-settings__status"
                  :class="server.available ? 'mcp-settings__status--connected' : 'mcp-settings__status--failed'"
                >
                  <span class="mcp-settings__dot" aria-hidden="true" />
                  {{ server.available ? '已连接' : '连接失败' }}
                </span>
              </div>
              <h4 class="mcp-settings__name">{{ serverInfo(server.name).label }}</h4>
              <code class="mcp-settings__identifier">{{ server.name }}</code>
              <p v-if="server.failureReason" class="mcp-settings__failure" role="status">{{ server.failureReason }}</p>
              <div class="mcp-settings__card-actions">
                <span class="mcp-settings__tool-count"
                  ><strong>{{ server.toolCount }}</strong> 个工具</span
                >
                <el-button
                  text
                  :icon="Refresh"
                  :loading="reconnecting === server.name"
                  :disabled="busy"
                  :aria-label="`重连 ${server.name}`"
                  @click="reconnect(server.name)"
                  >重连</el-button
                >
              </div>
            </div>
            <button
              type="button"
              class="mcp-settings__tools-trigger"
              aria-haspopup="dialog"
              :aria-expanded="toolDrawerOpen && selectedServerName === server.name"
              :aria-label="`${serverInfo(server.name).label} 自动执行工具 ${server.autoTools.length} 项`"
              @click="showAutoTools(server.name)"
            >
              <span
                >自动执行 <span class="mcp-settings__detail-count">{{ server.autoTools.length }}</span></span
              >
              <el-icon aria-hidden="true"><ArrowRight /></el-icon>
            </button>
          </li>
        </ul>
      </template>
    </section>

    <section class="mcp-settings__section" aria-labelledby="mcp-personal-title">
      <header class="mcp-settings__heading"><h3 id="mcp-personal-title">本系统服务</h3></header>
      <el-alert
        v-if="personalError"
        type="error"
        :closable="false"
        show-icon
        title="加载本系统服务失败"
        :description="personalError"
      />
      <el-skeleton v-else-if="personalLoading && !personalMcp" :rows="3" animated />
      <div v-else-if="personalMcp" class="mcp-settings__service">
        <header class="mcp-settings__service-header">
          <div class="mcp-settings__service-identity">
            <span class="mcp-settings__icon mcp-settings__icon--primary" aria-hidden="true"
              ><el-icon><Connection /></el-icon
            ></span>
            <div class="mcp-settings__service-text">
              <h4>个人助理</h4>
              <div class="mcp-settings__service-meta">
                <code>{{ personalMcp.name }}</code
                ><span>v{{ personalMcp.version }}</span>
              </div>
            </div>
          </div>
          <span class="mcp-settings__status" :class="{ 'mcp-settings__status--connected': personalMcp.enabled }">
            <span class="mcp-settings__dot" aria-hidden="true" />{{ personalMcp.enabled ? '已启用' : '未启用' }}
          </span>
        </header>
        <div v-if="personalMcp.enabled" class="mcp-settings__endpoint">
          <span>端点路径</span><code>{{ personalMcp.endpoint }}</code>
          <el-button text :icon="DocumentCopy" aria-label="复制 MCP 端点路径" @click="copyEndpoint">复制</el-button>
        </div>
        <div class="mcp-settings__catalog-heading">
          <span>提供的工具</span><span class="mcp-settings__detail-count">{{ personalMcp.tools.length }}</span>
        </div>
        <ul v-if="personalMcp.tools.length" class="mcp-settings__catalog" aria-label="本系统 MCP 工具">
          <li v-for="tool in personalMcp.tools" :key="tool.name">
            <div class="mcp-settings__tool-heading">
              <el-icon aria-hidden="true"><component :is="toolInfo(tool.name).icon" /></el-icon>
              <h5>{{ toolInfo(tool.name).label }}</h5>
              <span v-if="tool.readOnly" class="mcp-settings__readonly">只读</span>
            </div>
            <p>{{ tool.description }}</p>
            <code>{{ tool.name }}</code>
          </li>
        </ul>
        <p v-else class="mcp-settings__policy">暂无工具</p>
      </div>
    </section>

    <el-drawer
      v-model="toolDrawerOpen"
      class="mcp-tools-drawer"
      title="自动执行工具"
      size="min(460px, 100vw)"
      append-to-body
      destroy-on-close
    >
      <template #header="{ titleId, titleClass }">
        <div class="mcp-auto-tools__heading">
          <h2 :id="titleId" :class="titleClass">自动执行工具</h2>
          <p v-if="selectedServer">
            {{ serverInfo(selectedServer.name).label }}
            <span>· {{ selectedServer.autoTools.length }} 项</span>
          </p>
        </div>
      </template>
      <template v-if="selectedServer">
        <ul
          v-if="selectedServer.autoTools.length"
          class="mcp-auto-tools__list"
          :aria-label="`${selectedServer.name} 自动执行工具`"
        >
          <li v-for="tool in selectedServer.autoTools" :key="tool">
            <span class="mcp-auto-tools__icon" aria-hidden="true"
              ><el-icon><Document /></el-icon
            ></span>
            <div>
              <span v-if="autoToolLabels[tool]" class="mcp-auto-tools__label">{{ autoToolLabels[tool] }}</span>
              <code>{{ tool }}</code>
            </div>
          </li>
        </ul>
        <el-empty v-else description="暂无自动执行工具" :image-size="72" />
      </template>
      <el-empty v-else description="该连接已不在当前列表中" :image-size="72" />
      <template #footer>
        <p class="mcp-auto-tools__policy">其他工具需确认后执行</p>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.mcp-settings {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-6);
  min-width: 0;
  color: var(--el-text-color-primary);
  container: mcp / inline-size;
}
.mcp-settings__toolbar,
.mcp-settings__heading,
.mcp-settings__card-top,
.mcp-settings__card-actions,
.mcp-settings__service-header,
.mcp-settings__service-identity,
.mcp-settings__service-meta,
.mcp-settings__endpoint,
.mcp-settings__catalog-heading,
.mcp-settings__tool-heading {
  display: flex;
  align-items: center;
  gap: var(--os-space-3);
}
.mcp-settings__toolbar,
.mcp-settings__heading,
.mcp-settings__card-top,
.mcp-settings__card-actions,
.mcp-settings__service-header {
  justify-content: space-between;
}
.mcp-settings__toolbar h2 {
  margin: 0;
  font-size: var(--os-font-xl);
  font-weight: 650;
  letter-spacing: 0.02em;
}
.mcp-settings__toolbar .el-button {
  background: var(--el-bg-color);
}
.mcp-settings__section {
  min-width: 0;
}
.mcp-settings__heading {
  margin-bottom: var(--os-space-3);
  flex-wrap: wrap;
}
.mcp-settings__heading h3 {
  margin: 0;
  font-size: var(--os-font-md);
  font-weight: 600;
}
.mcp-settings__count,
.mcp-settings__tool-count {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-sm);
  font-variant-numeric: tabular-nums;
}
.mcp-settings__connections {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(260px, 100%), 1fr));
  gap: var(--os-space-4);
  align-items: start;
  margin: 0;
  padding: 0;
  list-style: none;
}
.mcp-settings__connection,
.mcp-settings__service,
.mcp-settings__empty {
  min-width: 0;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--os-radius-xl);
}
.mcp-settings__card-body {
  padding: var(--os-space-5) var(--os-space-5) var(--os-space-3);
}
.mcp-settings__icon {
  display: grid;
  place-items: center;
  flex: none;
  width: 44px;
  height: 44px;
  border-radius: var(--os-radius-xl);
  font-size: 22px;
}
.mcp-settings__icon--primary {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}
.mcp-settings__icon--success {
  color: var(--el-color-success);
  background: var(--el-color-success-light-9);
}
.mcp-settings__name {
  margin: var(--os-space-4) 0 var(--os-space-1);
  font-size: var(--os-font-lg);
  font-weight: 600;
  overflow-wrap: anywhere;
}
.mcp-settings code {
  font-size: var(--os-font-xs);
  overflow-wrap: anywhere;
}
.mcp-settings__identifier,
.mcp-settings__service-meta {
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}
.mcp-settings__status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex: none;
  padding: var(--os-space-1) var(--os-space-2);
  border-radius: var(--os-radius-sm);
  background: var(--el-fill-color-light);
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
  line-height: 1.5;
  white-space: nowrap;
}
.mcp-settings__dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentColor;
  flex: none;
}
.mcp-settings__status--connected {
  color: var(--el-color-success-dark-2);
  background: var(--el-color-success-light-9);
}
.mcp-settings__status--failed {
  color: var(--el-color-danger-dark-2);
  background: var(--el-color-danger-light-9);
}
.mcp-settings__card-actions {
  margin-top: var(--os-space-4);
}
.mcp-settings__tool-count strong {
  font-size: var(--os-font-lg);
  color: var(--el-text-color-primary);
  font-weight: 600;
  margin-right: 2px;
}
.mcp-settings__card-actions .el-button {
  color: var(--el-text-color-secondary);
}
.mcp-settings__tools-trigger {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--os-space-3);
  width: 100%;
  min-height: 44px;
  padding: var(--os-space-2) var(--os-space-5);
  border: 0;
  border-top: 1px solid var(--el-border-color-extra-light);
  border-radius: 0 0 var(--os-radius-xl) var(--os-radius-xl);
  background: transparent;
  color: var(--el-text-color-regular);
  font-size: var(--os-font-sm);
  cursor: pointer;
}
.mcp-settings__tools-trigger:hover,
.mcp-settings__tools-trigger[aria-expanded='true'] {
  background: var(--el-fill-color-lighter);
  color: var(--el-color-primary);
}
.mcp-settings__tools-trigger:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: -2px;
}
.mcp-settings__tools-trigger > .el-icon {
  color: var(--el-text-color-secondary);
}
.mcp-settings__detail-count {
  display: inline-grid;
  place-items: center;
  min-width: 22px;
  min-height: 20px;
  padding: 0 var(--os-space-1);
  border-radius: var(--os-radius-xs);
  background: var(--el-fill-color);
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
  font-variant-numeric: tabular-nums;
}
.mcp-settings__tools-trigger .mcp-settings__detail-count {
  margin-left: var(--os-space-2);
}
:global(.mcp-tools-drawer .el-drawer__header) {
  margin-bottom: 0;
  padding: var(--os-space-5);
  border-bottom: 1px solid var(--el-border-color-lighter);
}
:global(.mcp-tools-drawer .el-drawer__body) {
  padding: var(--os-space-2) var(--os-space-5);
}
:global(.mcp-tools-drawer .el-drawer__close-btn) {
  min-width: 44px;
  min-height: 44px;
  align-items: center;
  justify-content: center;
}
:global(.mcp-tools-drawer .el-drawer__footer) {
  padding: var(--os-space-4) var(--os-space-5);
  border-top: 1px solid var(--el-border-color-lighter);
}
.mcp-auto-tools__heading h2 {
  margin: 0;
  font-size: var(--os-font-xl);
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.mcp-auto-tools__heading p {
  margin: var(--os-space-2) 0 0;
  font-size: var(--os-font-sm);
  color: var(--el-text-color-regular);
  overflow-wrap: anywhere;
}
.mcp-auto-tools__heading p span {
  color: var(--el-text-color-secondary);
}
.mcp-auto-tools__list {
  margin: 0;
  padding: 0;
  list-style: none;
}
.mcp-auto-tools__list li {
  display: flex;
  align-items: center;
  gap: var(--os-space-3);
  padding: var(--os-space-3) 0;
}
.mcp-auto-tools__list li + li {
  border-top: 1px solid var(--el-border-color-extra-light);
}
.mcp-auto-tools__icon {
  display: grid;
  place-items: center;
  width: 32px;
  height: 36px;
  flex: none;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-light);
  border-radius: var(--os-radius-sm);
  font-size: var(--os-font-lg);
}
.mcp-auto-tools__list li > div {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-1);
  min-width: 0;
}
.mcp-auto-tools__label {
  color: var(--el-text-color-primary);
  font-size: var(--os-font-md);
}
.mcp-auto-tools__list code {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
  line-height: 1.5;
  overflow-wrap: anywhere;
}
.mcp-auto-tools__policy {
  margin: 0;
  text-align: left;
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}
.mcp-settings__policy,
.mcp-settings__failure {
  margin: 0;
  font-size: var(--os-font-xs);
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}
.mcp-settings__failure {
  margin-top: var(--os-space-3);
  color: var(--el-color-danger);
  overflow-wrap: anywhere;
}
.mcp-settings__service {
  padding: var(--os-space-5);
}
.mcp-settings__service-header {
  gap: var(--os-space-4);
}
.mcp-settings__service-identity,
.mcp-settings__service-text {
  min-width: 0;
}
.mcp-settings__service-text h4 {
  margin: 0 0 var(--os-space-1);
  font-size: var(--os-font-lg);
  font-weight: 600;
}
.mcp-settings__service-meta {
  gap: var(--os-space-2);
  flex-wrap: wrap;
  font-size: var(--os-font-xs);
}
.mcp-settings__endpoint {
  margin-top: var(--os-space-5);
  padding: var(--os-space-1) var(--os-space-2) var(--os-space-1) var(--os-space-3);
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-extra-light);
  border-radius: var(--os-radius-md);
  font-size: var(--os-font-xs);
  color: var(--el-text-color-secondary);
}
.mcp-settings__endpoint code {
  flex: 1;
  min-width: 0;
  color: var(--el-text-color-primary);
}
.mcp-settings__endpoint > span {
  flex: none;
}
.mcp-settings__endpoint .el-button {
  flex: none;
}
.mcp-settings__catalog-heading {
  margin: var(--os-space-5) 0 var(--os-space-3);
  font-size: var(--os-font-sm);
  color: var(--el-text-color-regular);
}
.mcp-settings__catalog {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--os-space-3);
  list-style: none;
  padding: 0;
  margin: 0;
}
.mcp-settings__catalog li {
  min-width: 0;
  padding: var(--os-space-3);
  border: 1px solid var(--el-border-color-extra-light);
  border-radius: var(--os-radius-md);
}
.mcp-settings__tool-heading {
  gap: var(--os-space-2);
}
.mcp-settings__tool-heading > .el-icon {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-lg);
  flex: none;
}
.mcp-settings__tool-heading h5 {
  margin: 0;
  font-size: var(--os-font-sm);
  font-weight: 600;
  overflow-wrap: anywhere;
}
.mcp-settings__readonly {
  margin-left: auto;
  flex: none;
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}
.mcp-settings__catalog p {
  margin: var(--os-space-2) 0 var(--os-space-1);
  font-size: var(--os-font-xs);
  line-height: 1.6;
  color: var(--el-text-color-regular);
}
.mcp-settings__catalog code {
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}
.mcp-settings__empty {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--os-space-3);
  padding: var(--os-space-7) var(--os-space-5);
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-sm);
}
.mcp-settings__empty > .el-icon {
  font-size: var(--os-font-xl);
}
@container mcp (max-width: 560px) {
  .mcp-settings__catalog {
    grid-template-columns: minmax(0, 1fr);
  }
}
@media (max-width: 600px) {
  .mcp-settings .el-button {
    min-height: 44px;
  }
  .mcp-settings__service-header {
    align-items: flex-start;
    flex-wrap: wrap;
  }
  .mcp-settings__card-body,
  .mcp-settings__service {
    padding: var(--os-space-4);
  }
  .mcp-settings__card-body {
    padding-bottom: var(--os-space-2);
  }
  .mcp-settings__tools-trigger {
    padding-inline: var(--os-space-4);
  }
  .mcp-settings__endpoint {
    gap: var(--os-space-2);
  }
}
.mcp-settings__intro {
  margin: 6px 0 0;
  max-width: 52ch;
  font-size: var(--os-font-sm);
  line-height: 1.65;
  color: var(--el-text-color-secondary);
}
.mcp-settings__toolbar {
  flex-wrap: wrap;
  gap: var(--os-space-3);
}
</style>
