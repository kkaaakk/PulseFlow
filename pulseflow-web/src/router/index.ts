import { createRouter, createWebHistory } from 'vue-router'

import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', /** 在匹配到路由时动态加载对应页面，减少首屏需要下载的模块。 */ component: () => import('@/views/Login.vue'), meta: { public: true } },
    {
      path: '/',
      /** 在匹配到路由时动态加载对应页面，减少首屏需要下载的模块。 */
      component: () => import('@/layouts/AppShell.vue'),
      children: [
        { path: '', redirect: '/dashboard' },
        { path: 'dashboard', name: 'dashboard', /** 在匹配到路由时动态加载对应页面，减少首屏需要下载的模块。 */ component: () => import('@/views/Dashboard.vue'), meta: { title: 'Dashboard' } },
        { path: 'investigations/:id?', name: 'investigations', /** 在匹配到路由时动态加载对应页面，减少首屏需要下载的模块。 */ component: () => import('@/views/Investigations.vue'), meta: { title: 'Growth Investigation' } },
        { path: 'campaigns', name: 'campaigns', /** 在匹配到路由时动态加载对应页面，减少首屏需要下载的模块。 */ component: () => import('@/views/Campaigns.vue'), meta: { title: 'Campaigns' } },
        { path: 'campaigns/:id', name: 'campaign-detail', /** 在匹配到路由时动态加载对应页面，减少首屏需要下载的模块。 */ component: () => import('@/views/CampaignDetail.vue'), meta: { title: 'Campaign Detail' } },
        { path: 'users', name: 'users', /** 在匹配到路由时动态加载对应页面，减少首屏需要下载的模块。 */ component: () => import('@/views/Users.vue'), meta: { title: 'Users' } },
        { path: 'users/:id', name: 'user-detail', /** 在匹配到路由时动态加载对应页面，减少首屏需要下载的模块。 */ component: () => import('@/views/UserDetail.vue'), meta: { title: 'User 360' } },
        { path: 'events', name: 'events', /** 在匹配到路由时动态加载对应页面，减少首屏需要下载的模块。 */ component: () => import('@/views/Events.vue'), meta: { title: 'Events' } },
        { path: 'deliveries', name: 'deliveries', /** 在匹配到路由时动态加载对应页面，减少首屏需要下载的模块。 */ component: () => import('@/views/Deliveries.vue'), meta: { title: 'Deliveries' } },
        { path: 'attribution', name: 'attribution', /** 在匹配到路由时动态加载对应页面，减少首屏需要下载的模块。 */ component: () => import('@/views/Attribution.vue'), meta: { title: 'Attribution' } },
        { path: 'system', name: 'system', /** 在匹配到路由时动态加载对应页面，减少首屏需要下载的模块。 */ component: () => import('@/views/System.vue'), meta: { title: 'System' } },
      ],
    },
  ],
})

// 导航前恢复登录会话，未认证时跳转登录并记录原目标页面。
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
