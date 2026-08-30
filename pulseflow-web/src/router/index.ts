import { createRouter, createWebHistory } from 'vue-router'

import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('@/views/Login.vue'), meta: { public: true } },
    {
      path: '/',
      component: () => import('@/layouts/AppShell.vue'),
      children: [
        { path: '', redirect: '/dashboard' },
        { path: 'dashboard', name: 'dashboard', component: () => import('@/views/Dashboard.vue'), meta: { title: 'Dashboard' } },
        { path: 'copilot', name: 'copilot', component: () => import('@/views/Copilot.vue'), meta: { title: 'AI Campaign Copilot' } },
        { path: 'campaigns', name: 'campaigns', component: () => import('@/views/Campaigns.vue'), meta: { title: 'Campaigns' } },
        { path: 'campaigns/:id', name: 'campaign-detail', component: () => import('@/views/CampaignDetail.vue'), meta: { title: 'Campaign Detail' } },
        { path: 'users', name: 'users', component: () => import('@/views/Users.vue'), meta: { title: 'Users' } },
        { path: 'users/:id', name: 'user-detail', component: () => import('@/views/UserDetail.vue'), meta: { title: 'User 360' } },
        { path: 'events', name: 'events', component: () => import('@/views/Events.vue'), meta: { title: 'Events' } },
        { path: 'deliveries', name: 'deliveries', component: () => import('@/views/Deliveries.vue'), meta: { title: 'Deliveries' } },
        { path: 'attribution', name: 'attribution', component: () => import('@/views/Attribution.vue'), meta: { title: 'Attribution' } },
        { path: 'system', name: 'system', component: () => import('@/views/System.vue'), meta: { title: 'System' } },
      ],
    },
  ],
})

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
