<script setup lang="ts">
import { Lock, User } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { storeToRefs } from 'pinia'
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { useAuthStore } from '@/stores/auth'

/**
 * 登录页（阶段 15）。
 *
 * 只在 `authEnabled=true` 时可达；关闭认证时路由守卫不会把用户带到这里。
 */
const auth = useAuthStore()
const { loading } = storeToRefs(auth)
const route = useRoute()
const router = useRouter()

const username = ref('')
const password = ref('')
const localError = ref<string | null>(null)

async function submit(): Promise<void> {
  // 防连点（审查 P2）：点击按钮 + 表单 submit + 回车可能同时触发，
  // 登录请求已经在途时直接返回，避免一次点击发两次登录请求
  if (loading.value) {
    return
  }
  const name = username.value.trim()
  if (!name || !password.value) {
    localError.value = '请输入用户名与密码'
    return
  }
  localError.value = null
  const ok = await auth.login(name, password.value)
  if (!ok) {
    // 就地提示：store.error 渲染在本页的表单上方，比弹一个 3 秒消失的 toast 更易读
    localError.value = auth.error ?? '登录失败'
    ElMessage.error(localError.value)
    return
  }
  // 登录前想去的页面（守卫带过来的 redirect），没有就回首页。
  // 只接受站内相对路径，挡住 `//evil.com` 这种协议相对跳转（审查 P2）
  const raw = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
  const redirect = raw.startsWith('/') && !raw.startsWith('//') ? raw : '/'
  await router.replace(redirect)
}
</script>

<template>
  <div class="login">
    <!-- 背景装饰层：两团极淡的主色光晕。纯白/纯灰的登录页会显得「没过设计」，
         光晕是最低成本的空间感（不含图片、不影响对比度） -->
    <div class="login__glow login__glow--a" aria-hidden="true" />
    <div class="login__glow login__glow--b" aria-hidden="true" />

    <el-card class="login__card" shadow="always">
      <!-- 品牌区（阶段 18，T038）：与全站令牌一致的 Logo + 名称 + 一句定位 -->
      <div class="login__brand">
        <span class="login__logo">AI</span>
        <div class="login__brand-text">
          <span class="login__title">Personal AI OS</span>
          <span class="login__tagline">对话 · 任务 · 知识库 · 日程 · 记忆</span>
        </div>
      </div>

      <h1 class="login__heading">登录</h1>
      <p class="login__hint">这个实例已开启访问控制，请先登录后继续。</p>

      <el-form label-position="top" size="large" @submit.prevent="submit">
        <el-form-item label="用户名">
          <el-input v-model="username" autocomplete="username" placeholder="请输入用户名">
            <template #prefix>
              <el-icon><User /></el-icon>
            </template>
          </el-input>
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="password"
            type="password"
            show-password
            autocomplete="current-password"
            placeholder="请输入密码"
          >
            <template #prefix>
              <el-icon><Lock /></el-icon>
            </template>
          </el-input>
        </el-form-item>

        <el-alert v-if="localError" class="login__error" type="error" :closable="false" show-icon :title="localError" />

        <!-- native-type="submit" 单独驱动表单提交；不再绑 @click ——
             两者并存会一次点击发两次登录请求（审查 P2） -->
        <el-button class="login__submit" type="primary" :loading="loading" native-type="submit"> 登录 </el-button>
      </el-form>

      <p class="login__footnote">本地单用户实例 · 凭据只保存在本机</p>
    </el-card>
  </div>
</template>

<style scoped>
/*
 * 登录页布局：撑满外壳剩余高度并居中。
 * 外壳在登录页不渲染侧栏，main 本身就是全宽 —— 这里只需要「水平垂直居中」。
 */
.login {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  /* 减去顶栏高度，避免卡片偏下（外壳 main 是 flex 列容器，这里用 min-height 撑满） */
  min-height: calc(100dvh - var(--os-header-height) - 120px);
  padding: var(--os-space-6) var(--os-space-4);
  overflow: hidden;
}

/* ---- 背景光晕 ---- */

.login__glow {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
  opacity: 0.5;
  pointer-events: none;
}

.login__glow--a {
  top: -120px;
  left: 12%;
  width: 420px;
  height: 420px;
  background: var(--el-color-primary-light-7);
}

.login__glow--b {
  right: 10%;
  bottom: -140px;
  width: 360px;
  height: 360px;
  background: var(--el-color-primary-light-8);
}

/* ---- 卡片 ---- */

.login__card {
  position: relative;
  width: 400px;
  max-width: 100%;
  border-radius: var(--os-radius-xl);
  box-shadow: var(--os-shadow-popup);
}

.login__card :deep(.el-card__body) {
  padding: var(--os-space-7) var(--os-space-6) var(--os-space-6);
}

.login__brand {
  display: flex;
  align-items: center;
  gap: var(--os-space-3);
  margin-bottom: var(--os-space-6);
}

.login__logo {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 42px;
  height: 42px;
  border-radius: var(--os-radius-lg);
  background: linear-gradient(135deg, var(--el-color-primary) 0%, var(--el-color-primary-dark-2) 100%);
  box-shadow: 0 2px 8px 0 rgb(79 70 229 / 32%);
  color: #fff;
  font-size: var(--os-font-md);
  font-weight: 700;
  flex: none;
}

.login__brand-text {
  display: flex;
  flex-direction: column;
  gap: var(--os-space-1);
  min-width: 0;
}

.login__title {
  font-size: var(--os-font-lg);
  font-weight: 600;
}

.login__tagline {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}

/* 卡片内的主标题：品牌区负责「是谁」，这里负责「要做什么」 */
.login__heading {
  margin: 0;
  font-size: var(--os-font-2xl);
  font-weight: 650;
  letter-spacing: -0.2px;
}

.login__hint {
  margin: var(--os-space-2) 0 var(--os-space-5);
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-sm);
  line-height: 1.6;
}

.login__error {
  margin-bottom: var(--os-space-3);
}

.login__submit {
  width: 100%;
  height: 42px;
  margin-top: var(--os-space-2);
  font-size: var(--os-font-md);
}

.login__footnote {
  margin: var(--os-space-5) 0 0;
  padding-top: var(--os-space-4);
  border-top: 1px solid var(--el-border-color-lighter);
  color: var(--el-text-color-placeholder);
  font-size: var(--os-font-xs);
  text-align: center;
}
</style>
