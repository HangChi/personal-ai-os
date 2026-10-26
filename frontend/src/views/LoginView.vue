<script setup lang="ts">
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
  // 登录前想去的页面（守卫带过来的 redirect），没有就回首页
  const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
  await router.replace(redirect)
}
</script>

<template>
  <div class="login">
    <el-card class="login__card" shadow="always">
      <!-- 品牌区（阶段 18，T038）：与全站令牌一致的Logo + 名称 + 一句定位 -->
      <div class="login__brand">
        <span class="login__logo">AI</span>
        <div class="login__brand-text">
          <span class="login__title">Personal AI OS</span>
          <span class="login__tagline">对话 · 任务 · 知识库 · 日程 · 记忆</span>
        </div>
      </div>
      <p class="login__hint">这个实例已开启访问控制，请先登录。</p>

      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名">
          <el-input v-model="username" autocomplete="username" placeholder="用户名" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="password"
            type="password"
            show-password
            autocomplete="current-password"
            placeholder="密码"
            @keydown.enter="submit"
          />
        </el-form-item>

        <el-alert v-if="localError" class="login__error" type="error" :closable="false" show-icon :title="localError" />

        <el-button class="login__submit" type="primary" :loading="loading" native-type="submit" @click="submit">
          登录
        </el-button>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.login {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 60vh;
}

.login__card {
  width: 380px;
  border-radius: var(--os-radius-xl);
  box-shadow: var(--os-shadow-popup);
}

.login__brand {
  display: flex;
  align-items: center;
  gap: var(--os-space-3);
  margin-bottom: var(--os-space-2);
}

.login__logo {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: var(--os-radius-lg);
  background: var(--el-color-primary);
  color: var(--el-color-white);
  font-size: var(--os-font-md);
  font-weight: 700;
  flex: none;
}

.login__brand-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.login__title {
  font-size: var(--os-font-md);
  font-weight: 600;
}

.login__tagline {
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}

.login__hint {
  margin: 0 0 var(--os-space-4);
  color: var(--el-text-color-secondary);
  font-size: var(--os-font-xs);
}

.login__error {
  margin-bottom: var(--os-space-3);
}

.login__submit {
  width: 100%;
}
</style>
