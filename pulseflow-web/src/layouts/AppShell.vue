<script setup lang="ts">
import { computed, markRaw, ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  ArrowLeft,
  ArrowRight,
  Bell,
  DataAnalysis,
  DataLine,
  MagicStick,
  MessageBox,
  Operation,
  Promotion,
  Setting,
  Lightning,
  User,
} from '@element-plus/icons-vue'

import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const auth = useAuthStore()
const collapsed = ref(false)

const navItems = [
  { label: 'Dashboard', to: '/dashboard', icon: markRaw(DataAnalysis) },
  { label: 'AI Copilot', to: '/copilot', icon: markRaw(MagicStick) },
  { label: 'Campaigns', to: '/campaigns', icon: markRaw(Promotion) },
  { label: 'Users', to: '/users', icon: markRaw(User) },
  { label: 'Events', to: '/events', icon: markRaw(Lightning) },
  { label: 'Deliveries', to: '/deliveries', icon: markRaw(MessageBox) },
  { label: 'Attribution', to: '/attribution', icon: markRaw(DataLine) },
  { label: 'System', to: '/system', icon: markRaw(Setting) },
]

const pageTitle = computed(() => String(route.meta.title ?? 'Dashboard'))
const operatorInitials = computed(() => (auth.session?.displayName || 'OP').slice(0, 2).toUpperCase())
const today = new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date())
</script>

<template>
  <div class="app-shell" :class="{ 'is-collapsed': collapsed }">
    <aside class="sidebar" :class="{ collapsed }">
      <div class="brand">
        <span class="brand-mark"><el-icon><Lightning /></el-icon></span>
        <span v-if="!collapsed">PulseFlow</span>
      </div>
      <nav class="nav-list" aria-label="主导航">
        <router-link v-for="item in navItems" :key="item.to" :to="item.to" class="nav-item">
          <el-icon><component :is="item.icon" /></el-icon>
          <span v-if="!collapsed">{{ item.label }}</span>
        </router-link>
      </nav>
      <div class="sidebar-footer">
        <button class="collapse-button" :aria-label="collapsed ? '展开导航' : '收起导航'" @click="collapsed = !collapsed">
          <el-icon><ArrowRight v-if="collapsed" /><ArrowLeft v-else /></el-icon>
        </button>
      </div>
    </aside>

    <section class="main-column">
      <header class="topbar">
        <div class="topbar-title">{{ pageTitle }}</div>
        <div class="topbar-actions">
          <span class="topbar-date">{{ today }}</span>
          <router-link to="/system" class="topbar-status">基础设施：正常</router-link>
          <el-icon class="topbar-bell"><Bell /></el-icon>
          <div class="operator">
            <span class="operator-avatar">{{ operatorInitials }}</span>
            <span>{{ auth.session?.displayName || 'Operator' }}</span>
            <el-icon><Operation /></el-icon>
          </div>
        </div>
      </header>
      <main class="page-content">
        <router-view />
      </main>
    </section>
  </div>
</template>
