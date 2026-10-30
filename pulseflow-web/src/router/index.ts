import { createRouter, createWebHistory } from 'vue-router'

import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', /** 在路由首次需要时动态导入对应页面或布局，由构建工具按模块拆分加载。 */ component: () => import('@/views/Login.vue'), meta: { public: true } },
    {
      path: '/',
      /**
       * 在路由首次需要时动态导入对应页面或布局，由构建工具按模块拆分加载。
       */
      component: () => import('@/layouts/AppShell.vue'),
      children: [
        { path: '', redirect: '/dashboard' },
        { path: 'dashboard', name: 'dashboard', /** 在路由首次需要时动态导入对应页面或布局，由构建工具按模块拆分加载。 */ component: () => import('@/views/Dashboard.vue'), meta: { title: 'Dashboard' } },
        { path: 'investigations/:id?', name: 'investigations', /** 在路由首次需要时动态导入对应页面或布局，由构建工具按模块拆分加载。 */ component: () => import('@/views/Investigations.vue'), meta: { title: 'Growth Investigation' } },
        { path: 'campaigns', name: 'campaigns', /** 在路由首次需要时动态导入对应页面或布局，由构建工具按模块拆分加载。 */ component: () => import('@/views/Campaigns.vue'), meta: { title: 'Campaigns' } },
        { path: 'campaigns/:id', name: 'campaign-detail', /** 在路由首次需要时动态导入对应页面或布局，由构建工具按模块拆分加载。 */ component: () => import('@/views/CampaignDetail.vue'), meta: { title: 'Campaign Detail' } },
        { path: 'users', name: 'users', /** 在路由首次需要时动态导入对应页面或布局，由构建工具按模块拆分加载。 */ component: () => import('@/views/Users.vue'), meta: { title: 'Users' } },
        { path: 'users/:id', name: 'user-detail', /** 在路由首次需要时动态导入对应页面或布局，由构建工具按模块拆分加载。 */ component: () => import('@/views/UserDetail.vue'), meta: { title: 'User 360' } },
        { path: 'events', name: 'events', /** 在路由首次需要时动态导入对应页面或布局，由构建工具按模块拆分加载。 */ component: () => import('@/views/Events.vue'), meta: { title: 'Events' } },
        { path: 'deliveries', name: 'deliveries', /** 在路由首次需要时动态导入对应页面或布局，由构建工具按模块拆分加载。 */ component: () => import('@/views/Deliveries.vue'), meta: { title: 'Deliveries' } },
        { path: 'attribution', name: 'attribution', /** 在路由首次需要时动态导入对应页面或布局，由构建工具按模块拆分加载。 */ component: () => import('@/views/Attribution.vue'), meta: { title: 'Attribution' } },
        { path: 'system', name: 'system', /** 在路由首次需要时动态导入对应页面或布局，由构建工具按模块拆分加载。 */ component: () => import('@/views/System.vue'), meta: { title: 'System' } },
      ],
    },
  ],
})

// 导航前恢复当前会话：已登录用户离开登录页，未登录用户保留目标路径并转到登录页。
router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (to.name === 'login') {
    await auth.restore()
    if (auth.isAuthenticated) return { name: 'dashboard' }
    return true
  }
  await auth.restore()
  if (!auth.isAuthenticated) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  return true
})

export default router
