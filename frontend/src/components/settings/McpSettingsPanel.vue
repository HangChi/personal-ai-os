<script setup lang="ts">
import { Connection, DocumentCopy, Folder, Link, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { storeToRefs } from 'pinia'
import { computed, onMounted, ref } from 'vue'
import { useMcpStore } from '@/stores/mcp'

const store = useMcpStore()
const { mcp, loading, error, personalMcp, personalLoading, personalError } = storeToRefs(store)
const reconnecting = ref<string | null>(null)
const busy = computed(() => loading.value || personalLoading.value || reconnecting.value !== null)
const connectedCount = computed(() => mcp.value?.servers.filter((server) => server.available).length ?? 0)

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

function serverIcon(name: string) {
  return name === 'filesystem' ? Folder : name === 'fetch' ? Link : Connection
}
</script>

<template>
  <div class="mcp-settings">
    <section class="mcp-settings__section" aria-labelledby="mcp-connections-title">
      <header class="mcp-settings__heading">
        <div class="mcp-settings__heading-text">
          <h3 id="mcp-connections-title">外部连接</h3>
          <span v-if="mcp?.enabled && mcp.servers.length" class="mcp-settings__count">
            {{ connectedCount }} / {{ mcp.servers.length }} 已连接
          </span>
        </div>
        <el-button :icon="Refresh" :loading="loading || personalLoading" :disabled="busy" @click="store.refresh()">
          刷新
        </el-button>
      </header>

      <el-alert v-if="error" type="error" :closable="false" show-icon title="加载连接失败" :description="error" />
      <el-skeleton v-else-if="loading && !mcp" :rows="3" animated />
      <template v-else-if="mcp">
        <p v-if="!mcp.enabled" class="mcp-settings__empty">外部 MCP 未启用</p>
        <p v-else-if="!mcp.servers.length" class="mcp-settings__empty">暂无外部连接</p>
        <ul v-else class="mcp-settings__connections" aria-label="MCP 接入状态">
          <li v-for="server in mcp.servers" :key="server.name" class="mcp-settings__connection">
            <div class="mcp-settings__connection-row">
              <div class="mcp-settings__identity">
                <span class="mcp-settings__icon" aria-hidden="true"
                  ><el-icon><component :is="serverIcon(server.name)" /></el-icon
                ></span>
                <div class="mcp-settings__name-group">
                  <span class="mcp-settings__name">{{ server.name }}</span>
                  <span
                    class="mcp-settings__status"
                    :class="{
                      'mcp-settings__status--connected': server.available,
                      'mcp-settings__status--failed': !server.available,
                    }"
                  >
                    <span class="mcp-settings__dot" aria-hidden="true" />
                    {{ server.available ? '已连接' : '连接失败' }}
                  </span>
                </div>
              </div>
              <span class="mcp-settings__tool-count"
                ><strong>{{ server.toolCount }}</strong> 个工具</span
              >
              <el-button
                :loading="reconnecting === server.name"
                :disabled="busy"
                :aria-label="`重连 ${server.name}`"
                @click="reconnect(server.name)"
              >
                重连
              </el-button>
            </div>
            <p v-if="server.failureReason" class="mcp-settings__failure" role="status">{{ server.failureReason }}</p>
            <details class="mcp-settings__details">
              <summary>
                自动执行工具 <span class="mcp-settings__count">{{ server.autoTools.length }}</span>
              </summary>
              <ul
                v-if="server.autoTools.length"
                class="mcp-settings__tool-list"
                :aria-label="`${server.name} 自动执行工具`"
              >
                <li v-for="tool in server.autoTools" :key="tool">
                  <code>{{ tool }}</code>
                </li>
              </ul>
              <p class="mcp-settings__policy">
                {{ server.autoTools.length ? '其余工具需确认后执行' : '所有工具均需确认后执行' }}
              </p>
            </details>
          </li>
        </ul>
      </template>
    </section>

    <section class="mcp-settings__section" aria-labelledby="mcp-personal-title">
      <header class="mcp-settings__heading">
        <h3 id="mcp-personal-title">本系统服务</h3>
        <span
          v-if="personalMcp"
          class="mcp-settings__status"
          :class="{ 'mcp-settings__status--connected': personalMcp.enabled }"
        >
          <span class="mcp-settings__dot" aria-hidden="true" />{{ personalMcp.enabled ? '已启用' : '未启用' }}
        </span>
      </header>
      <el-alert
        v-if="personalError"
        type="error"
        :closable="false"
        show-icon
        title="加载本系统服务失败"
        :description="personalError"
      />
      <el-skeleton v-else-if="personalLoading && !personalMcp" :rows="2" animated />
      <template v-else-if="personalMcp">
        <div v-if="personalMcp.enabled" class="mcp-settings__service">
          <div class="mcp-settings__service-meta">
            <span>{{ personalMcp.name }}</span
            ><span class="mcp-settings__count">v{{ personalMcp.version }}</span>
          </div>
          <div class="mcp-settings__endpoint">
            <span class="mcp-settings__count">端点</span>
            <code>{{ personalMcp.endpoint }}</code>
            <el-button text :icon="DocumentCopy" aria-label="复制 MCP 端点路径" @click="copyEndpoint" />
          </div>
        </div>
        <details class="mcp-settings__details mcp-settings__details--personal">
          <summary>
            只读工具 <span class="mcp-settings__count">{{ personalMcp.tools.length }}</span>
          </summary>
          <ul class="mcp-settings__catalog">
            <li v-for="tool in personalMcp.tools" :key="tool.name">
              <code>{{ tool.name }}</code
              ><span>{{ tool.description }}</span>
            </li>
          </ul>
        </details>
      </template>
    </section>
  </div>
</template>

<style scoped>
.mcp-settings {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-7);
  min-width: 0;
}
.mcp-settings__section {
  min-width: 0;
}
.mcp-settings__heading,
.mcp-settings__heading-text {
  display: flex;
  align-items: center;
  gap: var(--os-space-3);
}
.mcp-settings__heading {
  justify-content: space-between;
  margin-bottom: var(--os-space-4);
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
  font-size: var(--os-font-xs);
  font-variant-numeric: tabular-nums;
}
.mcp-settings__connections {
  margin: 0;
  padding: 0;
  list-style: none;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--os-radius-lg);
  overflow: hidden;
}
.mcp-settings__connection {
  padding: var(--os-space-4);
}
.mcp-settings__connection + .mcp-settings__connection {
  border-top: 1px solid var(--el-border-color-lighter);
}
.mcp-settings__connection-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  align-items: center;
  gap: var(--os-space-5);
}
.mcp-settings__identity {
  display: flex;
  align-items: center;
  gap: var(--os-space-3);
  min-width: 0;
}
.mcp-settings__icon {
  display: grid;
  place-items: center;
  flex: none;
  width: 36px;
  height: 36px;
  background: var(--el-fill-color-light);
  border-radius: var(--os-radius-md);
  color: var(--el-text-color-regular);
  font-size: var(--os-font-lg);
}
.mcp-settings__name-group {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-1);
  min-width: 0;
}
.mcp-settings__name {
  font-size: var(--os-font-sm);
  font-weight: 600;
  overflow-wrap: anywhere;
}
.mcp-settings__status {
  display: inline-flex;
  align-items: center;
  gap: var(--os-space-1);
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}
.mcp-settings__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--el-text-color-placeholder);
  flex: none;
}
.mcp-settings__status--failed .mcp-settings__dot {
  background: var(--el-color-danger);
}
.mcp-settings__status--connected .mcp-settings__dot {
  background: var(--el-color-success);
}
.mcp-settings__tool-count {
  white-space: nowrap;
}
.mcp-settings__tool-count strong {
  font-size: var(--os-font-md);
  color: var(--el-text-color-primary);
  font-weight: 500;
}
.mcp-settings__details {
  margin-top: var(--os-space-3);
  padding-left: 48px;
  font-size: var(--os-font-xs);
}
.mcp-settings__details summary {
  width: fit-content;
  max-width: 100%;
  color: var(--el-text-color-secondary);
  cursor: pointer;
  padding: var(--os-space-1) 0;
}
.mcp-settings__details summary .mcp-settings__count {
  margin-left: var(--os-space-2);
}
.mcp-settings__details summary:hover {
  color: var(--el-color-primary);
}
.mcp-settings__details summary:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 2px;
  border-radius: var(--os-radius-xs);
}
.mcp-settings__tool-list {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(200px, 100%), 1fr));
  gap: var(--os-space-2);
  list-style: none;
  margin: var(--os-space-3) 0;
  padding: 0;
}
.mcp-settings code {
  font-size: var(--os-font-xs);
  overflow-wrap: anywhere;
}
.mcp-settings__tool-list code {
  color: var(--el-text-color-regular);
}
.mcp-settings__policy {
  margin: var(--os-space-3) 0 0;
  color: var(--el-text-color-secondary);
}
.mcp-settings__failure {
  margin: var(--os-space-3) 0 0;
  padding-left: 48px;
  color: var(--el-color-danger);
  font-size: var(--os-font-xs);
  overflow-wrap: anywhere;
  line-height: 1.6;
}
.mcp-settings__service {
  padding: var(--os-space-4);
  background: var(--el-fill-color-lighter);
  border-radius: var(--os-radius-md);
}
.mcp-settings__service-meta,
.mcp-settings__endpoint {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--os-space-3);
  font-size: var(--os-font-sm);
}
.mcp-settings__endpoint {
  margin-top: var(--os-space-2);
  gap: var(--os-space-2);
}
.mcp-settings__endpoint .el-button {
  margin-left: auto;
}
.mcp-settings__details--personal {
  padding-left: 0;
}
.mcp-settings__catalog {
  list-style: none;
  padding: 0;
  margin: var(--os-space-2) 0 0;
}
.mcp-settings__catalog li {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-1);
  padding: var(--os-space-3) 0;
}
.mcp-settings__catalog li + li {
  border-top: 1px solid var(--el-border-color-extra-light);
}
.mcp-settings__catalog span {
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}
.mcp-settings__empty {
  padding: var(--os-space-5) 0;
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-sm);
}
@media (max-width: 600px) {
  .mcp-settings__connection {
    padding: var(--os-space-3);
  }
  .mcp-settings__connection-row {
    gap: var(--os-space-2);
  }
  .mcp-settings__details,
  .mcp-settings__failure {
    padding-left: 0;
  }
  .mcp-settings__heading-text {
    flex-wrap: wrap;
    gap: var(--os-space-2);
  }
}
</style>
