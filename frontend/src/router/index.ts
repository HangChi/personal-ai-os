import { createRouter, createWebHistory } from 'vue-router'

/**
 * 路由表。
 *
 * 阶段 0 只有「总览」与「404」两个页面；后续各阶段按模块追加路由
 * （chat / task / knowledge / calendar）。
 */
const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'home',
      component: () => import('@/views/HomeView.vue'),
      meta: { title: '总览' },
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('@/views/NotFoundView.vue'),
      meta: { title: '页面不存在' },
    },
  ],
})

router.afterEach((to) => {
  const title = to.meta.title as string | undefined
  document.title = title ? `${title} · Personal AI OS` : 'Personal AI OS'
})

export default router
